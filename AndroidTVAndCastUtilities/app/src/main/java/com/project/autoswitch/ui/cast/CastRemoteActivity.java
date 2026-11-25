/**
 * @file CastRemoteActivity.java
 * @summary Phone & Google TV Cast Remote screen with device-type icon badges, live volume control where applicable, sticky header, persistent devices, and SwitchCompat power toggles.
 * @description Classifies TVs, Projectors, Speakers, Displays, and Phones from mDNS metadata, exposes SeekBar + 2-up Vol -/+ controls via Cast v2 SET_VOLUME (with ATV Remote keycode fallback), and syncs Google Home OnOff power states.
 * @testing Compiled and lint-checked via Gradle assembleRelease (v2.6).
 */
package com.project.autoswitch.ui.cast;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CastRemoteActivity extends AppCompatActivity {

    private static final String PREFS = "CastUtilitiesPrefs";
    private static final String KEY_PINNED = "pinned_device_ids";
    private static final String KEY_SAVED_DEVICES = "saved_network_devices_v1";
    private static final String CAST_TYPE = "_googlecast._tcp.";
    private static final String PHONE_TYPE = "_castremote._tcp.";
    private static final int RECEIVER_PORT = 8766;

    // Unified dark-neon palette
    private static final int COL_BG = Color.parseColor("#0A0E17");
    private static final int COL_CARD = Color.parseColor("#141B2D");
    private static final int COL_CYAN = Color.parseColor("#06B6D4");
    private static final int COL_NEON_CYAN = Color.parseColor("#00FFFF");
    private static final int COL_VIOLET = Color.parseColor("#7C3AED");
    private static final int COL_MAGENTA = Color.parseColor("#EC4899");
    private static final int COL_AMBER = Color.parseColor("#F59E0B");
    private static final int COL_EMERALD = Color.parseColor("#10B981");
    private static final int COL_CRIMSON = Color.parseColor("#EF4444");
    private static final int COL_SLATE_TOP = Color.parseColor("#2A3550");
    private static final int COL_SLATE_BOT = Color.parseColor("#1C2438");
    private static final int COL_TEXT = Color.parseColor("#E5E7EB");
    private static final int COL_MUTED = Color.parseColor("#9CA3AF");

    // Uniform button geometry
    private static final int BTN_RADIUS_DP = 12;
    private static final int CONTROL_SQUARE_HEIGHT_DP = 54;
    private static final int HEADER_PILL_HEIGHT_DP = 34;

    private enum DeviceKind { TV, PROJECTOR, SPEAKER, DISPLAY, PHONE }

    private static final class DeviceItem {
        final String id;
        final String name;
        String model;
        String state;
        String title;
        String contentId;
        double position;
        final boolean isPhone;
        final String host;
        final int port;
        boolean powerOn;
        double volumeLevel; // 0.0 .. 1.0 (-1 if not fetched yet)
        boolean isMuted;
        String volumeControlType; // "attenuation", "master", or "fixed"

        DeviceItem(String id, String name, String model, String state, String title, boolean isPhone, String host, int port) {
            this.id = id;
            this.name = name;
            this.model = model != null ? model : "";
            this.state = state;
            this.title = title;
            this.isPhone = isPhone;
            this.host = host;
            this.port = port;
            this.powerOn = true;
            this.volumeLevel = 0.50;
            this.isMuted = false;
            this.volumeControlType = "attenuation";
        }
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newCachedThreadPool();
    private final Map<String, DeviceItem> castDevices = new ConcurrentHashMap<>();
    private final Map<String, DeviceItem> phoneDevices = new ConcurrentHashMap<>();
    private Set<String> pinnedIds = new HashSet<>();

    private CastAndTvEngine engine;
    private NsdManager nsdManager;
    private WifiManager.MulticastLock multicastLock;
    private NsdManager.DiscoveryListener castListener;
    private NsdManager.DiscoveryListener phoneListener;

    private boolean isReceiving = false;
    private ServerSocket receiverServer;
    private NsdManager.RegistrationListener regListener;

    private TextView statusView;
    private EditText linkInput;
    private LinearLayout receiveSwitchContainer;
    private SwitchCompat receiveSwitch;
    private LinearLayout devicesContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        GoogleHomePowerBridge.registerActivity(this);
        engine = new CastAndTvEngine(this);
        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        pinnedIds = new HashSet<>(prefs.getStringSet(KEY_PINNED, new HashSet<>()));

        setContentView(buildUi());
        loadSavedDevices();
        renderDevices();
        refreshDevices();
    }

    @Override
    protected void onDestroy() {
        GoogleHomePowerBridge.unregisterActivity(this);
        stopDiscovery();
        stopReceiving();
        engine.cancelPairing();
        worker.shutdownNow();
        super.onDestroy();
    }

    private void runSafeBg(Runnable task) {
        if (isFinishing() || isDestroyed() || worker.isShutdown()) return;
        try {
            worker.execute(task);
        } catch (Exception ignored) {}
    }

    private void runSafeUi(Runnable task) {
        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            task.run();
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // MARK: - Device Type Detection & Icon Badges

    private DeviceKind classifyDevice(DeviceItem d) {
        if (d.isPhone) return DeviceKind.PHONE;
        String combined = (d.name + " " + d.model).toLowerCase(Locale.US);
        if (combined.contains("projector") || combined.contains("fengmi") || combined.contains("formovie")
                || combined.contains("xgimi") || combined.contains("nebula") || combined.contains("epson")
                || combined.contains("benq") || combined.contains("laser")) {
            return DeviceKind.PROJECTOR;
        }
        if (combined.contains("nest hub") || combined.contains("home hub") || combined.contains("smart display")) {
            return DeviceKind.DISPLAY;
        }
        if (combined.contains("mini") || combined.contains("audio") || combined.contains("speaker")
                || combined.contains("soundbar") || combined.contains("google home") || combined.endsWith("group")) {
            return DeviceKind.SPEAKER;
        }
        return DeviceKind.TV;
    }

    private String iconGlyphForKind(DeviceKind kind) {
        switch (kind) {
            case PROJECTOR: return "\uD83D\uDCFD\uFE0F"; // 📽️ Projector
            case SPEAKER:   return "\uD83D\uDD0A";       // 🔊 Speaker / Audio
            case DISPLAY:   return "\uD83D\uDDA5\uFE0F"; // 🖥️ Smart Display
            case PHONE:     return "\uD83D\uDCF1";       // 📱 Phone / Tablet
            case TV:
            default:        return "\uD83D\uDCFA";       // 📺 TV / Google TV
        }
    }

    private String labelForKind(DeviceItem d, DeviceKind kind) {
        if (d.model != null && !d.model.isEmpty() && !d.isPhone) {
            return d.model;
        }
        switch (kind) {
            case PROJECTOR: return "Projector";
            case SPEAKER:   return "Speaker";
            case DISPLAY:   return "Smart Display";
            case PHONE:     return "Cast Receiver";
            case TV:
            default:        return "Google TV / Cast";
        }
    }

    private int[] badgeColorsForKind(DeviceKind kind) {
        switch (kind) {
            case PROJECTOR: return new int[]{COL_AMBER, COL_VIOLET};
            case SPEAKER:   return new int[]{COL_EMERALD, COL_CYAN};
            case DISPLAY:   return new int[]{COL_VIOLET, COL_MAGENTA};
            case PHONE:     return new int[]{COL_MAGENTA, COL_VIOLET};
            case TV:
            default:        return new int[]{COL_CYAN, COL_VIOLET};
        }
    }

    private boolean supportsVolumeControl(DeviceItem d) {
        if (d.isPhone) return false;
        if ("fixed".equalsIgnoreCase(d.volumeControlType)) {
            // Fixed HDMI output only supports volume if paired via Android TV Remote keycodes
            return engine.isPaired(d.id);
        }
        return true;
    }

    // MARK: - UI Factory Helpers

    private StateListDrawable gradientButtonBg(int[] colors, int strokeColorNormal) {
        GradientDrawable focused = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        focused.setCornerRadius(dp(BTN_RADIUS_DP));
        focused.setStroke(dp(2), COL_NEON_CYAN);

        GradientDrawable normal = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        normal.setCornerRadius(dp(BTN_RADIUS_DP));
        if (strokeColorNormal != Color.TRANSPARENT) {
            normal.setStroke(dp(1), strokeColorNormal);
        }

        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_focused}, focused);
        sld.addState(new int[]{android.R.attr.state_pressed}, focused);
        sld.addState(new int[]{}, normal);
        return sld;
    }

    private Button makeControlSquare(String label, int[] gradientColors, int textColor, int strokeNormal) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTextSize(13.5f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackground(gradientButtonBg(gradientColors, strokeNormal));
        b.setPadding(dp(10), dp(8), dp(10), dp(8));
        b.setMinHeight(dp(CONTROL_SQUARE_HEIGHT_DP));
        b.setMinimumHeight(dp(CONTROL_SQUARE_HEIGHT_DP));
        b.setFocusable(true);
        b.setClickable(true);
        return b;
    }

    private void styleSwitchColors(SwitchCompat sw) {
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_checked}
        };
        sw.setThumbTintList(new ColorStateList(states, new int[]{Color.WHITE, Color.parseColor("#CBD5E1")}));
        sw.setTrackTintList(new ColorStateList(states, new int[]{Color.parseColor("#88FFFFFF"), Color.parseColor("#55000000")}));
    }

    private LinearLayout makeSwitchSquare(String titleText, boolean isChecked, SwitchCompat[] outSwitch, Runnable onToggleTap) {
        int[] colors = isChecked
                ? new int[]{COL_EMERALD, COL_CYAN}
                : new int[]{COL_SLATE_TOP, COL_SLATE_BOT};
        int stroke = isChecked ? Color.TRANSPARENT : COL_SLATE_TOP;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(gradientButtonBg(colors, stroke));
        box.setPadding(dp(12), dp(6), dp(10), dp(6));
        box.setMinimumHeight(dp(CONTROL_SQUARE_HEIGHT_DP));
        box.setFocusable(true);
        box.setClickable(true);

        TextView lbl = new TextView(this);
        lbl.setText(titleText);
        lbl.setTextColor(Color.WHITE);
        lbl.setTextSize(13.5f);
        lbl.setTypeface(Typeface.DEFAULT_BOLD);
        lbl.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(lbl);

        SwitchCompat sw = new SwitchCompat(this);
        sw.setFocusable(false);
        sw.setClickable(false);
        sw.setChecked(isChecked);
        styleSwitchColors(sw);
        box.addView(sw);

        if (outSwitch != null && outSwitch.length > 0) {
            outSwitch[0] = sw;
        }
        box.setOnClickListener(v -> onToggleTap.run());
        return box;
    }

    private Button makeHeaderPill(String label, int[] gradientColors, int textColor, int strokeNormal) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTextSize(11.5f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackground(gradientButtonBg(gradientColors, strokeNormal));
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        b.setMinHeight(dp(HEADER_PILL_HEIGHT_DP));
        b.setMinimumHeight(dp(HEADER_PILL_HEIGHT_DP));
        b.setFocusable(true);
        b.setClickable(true);
        return b;
    }

    private LinearLayout makeTwoUpRow(View left, View right, int topMarginDp) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(topMarginDp);
        row.setLayoutParams(rowLp);

        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, dp(CONTROL_SQUARE_HEIGHT_DP), 1f);
        leftLp.rightMargin = dp(8);
        left.setLayoutParams(leftLp);
        row.addView(left);

        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, dp(CONTROL_SQUARE_HEIGHT_DP), 1f);
        right.setLayoutParams(rightLp);
        row.addView(right);

        return row;
    }

    private View buildUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(COL_BG);
        page.setFitsSystemWindows(true);

        // Fixed Sticky Header at Top
        LinearLayout stickyHeader = new LinearLayout(this);
        stickyHeader.setOrientation(LinearLayout.VERTICAL);
        stickyHeader.setBackgroundColor(COL_BG);
        stickyHeader.setPadding(dp(18), dp(14), dp(18), dp(10));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        Button btnBack = makeHeaderPill("← Hub", new int[]{COL_SLATE_TOP, COL_SLATE_BOT}, COL_TEXT, Color.TRANSPARENT);
        btnBack.setOnClickListener(v -> finish());
        topBar.addView(btnBack);

        TextView title = new TextView(this);
        title.setText("CAST REMOTE");
        title.setTextColor(COL_NEON_CYAN);
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(14), 0, 0, 0);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        topBar.addView(title);

        Button btnLog = makeHeaderPill("Log", new int[]{COL_SLATE_TOP, COL_SLATE_BOT}, COL_MUTED, Color.TRANSPARENT);
        btnLog.setOnClickListener(v -> showDiagDialog());
        topBar.addView(btnLog);
        stickyHeader.addView(topBar);

        statusView = new TextView(this);
        statusView.setText("Discovering TVs, projectors, speakers & phones...");
        statusView.setTextColor(COL_MUTED);
        statusView.setTextSize(12.5f);
        statusView.setPadding(0, dp(8), 0, dp(6));
        stickyHeader.addView(statusView);

        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#2200FFFF"));
        divider.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        stickyHeader.addView(divider);

        page.addView(stickyHeader, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Scrollable Body
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        page.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(32));
        scroll.addView(root);

        // Merged Control & Mirror Menu Card
        LinearLayout menuCard = new LinearLayout(this);
        menuCard.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable menuBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{COL_CARD, Color.parseColor("#101626")});
        menuBg.setCornerRadius(dp(14));
        menuBg.setStroke(dp(1), COL_SLATE_TOP);
        menuCard.setBackground(menuBg);
        menuCard.setPadding(dp(14), dp(14), dp(14), dp(14));

        TextView menuTitle = new TextView(this);
        menuTitle.setText("CONTROL & MIRROR MENU");
        menuTitle.setTextColor(COL_NEON_CYAN);
        menuTitle.setTextSize(12f);
        menuTitle.setTypeface(Typeface.DEFAULT_BOLD);
        menuTitle.setPadding(0, 0, 0, dp(8));
        menuCard.addView(menuTitle);

        linkInput = new EditText(this);
        linkInput.setHint("Paste a YouTube link (optional)");
        linkInput.setHintTextColor(COL_MUTED);
        linkInput.setTextColor(COL_TEXT);
        linkInput.setSingleLine(true);
        linkInput.setBackground(gradientButtonBg(new int[]{COL_BG, COL_BG}, COL_SLATE_TOP));
        linkInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        linkInput.setFocusable(true);
        menuCard.addView(linkInput);

        Button btnMirrorAll = makeControlSquare("Mirror All", new int[]{COL_VIOLET, COL_CYAN}, Color.WHITE, Color.TRANSPARENT);
        btnMirrorAll.setOnClickListener(v -> mirrorTargets(null));

        Button btnPlayPinned = makeControlSquare("Play on Pinned", new int[]{COL_CYAN, COL_VIOLET}, Color.WHITE, Color.TRANSPARENT);
        btnPlayPinned.setOnClickListener(v -> playOnPinnedDevices());
        menuCard.addView(makeTwoUpRow(btnMirrorAll, btnPlayPinned, 10));

        Button btnRefresh = makeControlSquare("Refresh", new int[]{COL_SLATE_TOP, COL_VIOLET}, Color.WHITE, Color.TRANSPARENT);
        btnRefresh.setOnClickListener(v -> refreshDevices());

        Button btnStopAll = makeControlSquare("Stop All", new int[]{COL_MAGENTA, COL_AMBER}, Color.WHITE, Color.TRANSPARENT);
        btnStopAll.setOnClickListener(v -> stopAllDevices());
        menuCard.addView(makeTwoUpRow(btnRefresh, btnStopAll, 8));

        SwitchCompat[] recSwOut = new SwitchCompat[1];
        receiveSwitchContainer = makeSwitchSquare("Receive", isReceiving, recSwOut, this::toggleReceiving);
        receiveSwitch = recSwOut[0];

        Button btnPowerOffAll = makeControlSquare("Power Off All", new int[]{COL_CRIMSON, COL_MAGENTA}, Color.WHITE, Color.TRANSPARENT);
        btnPowerOffAll.setOnClickListener(v -> powerOffAllDevices());
        menuCard.addView(makeTwoUpRow(receiveSwitchContainer, btnPowerOffAll, 8));

        root.addView(menuCard);

        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        listLp.topMargin = dp(16);
        devicesContainer = new LinearLayout(this);
        devicesContainer.setOrientation(LinearLayout.VERTICAL);
        devicesContainer.setLayoutParams(listLp);
        root.addView(devicesContainer);

        return page;
    }

    // MARK: - Persistence

    private void loadSavedDevices() {
        try {
            String raw = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SAVED_DEVICES, null);
            if (raw == null || raw.isEmpty()) return;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "");
                String name = o.optString("name", "");
                String model = o.optString("model", "");
                String host = o.optString("host", "");
                int port = o.optInt("port", 8009);
                boolean isPhone = o.optBoolean("isPhone", false);
                if (id.isEmpty() || name.isEmpty() || host.isEmpty()) continue;
                DeviceItem item = new DeviceItem(
                        id, name, model, isPhone ? "SAVED" : "SAVED",
                        isPhone ? "Cast Receiver" : "",
                        isPhone, host, port);
                item.powerOn = o.optBoolean("powerOn", true);
                item.volumeLevel = o.optDouble("volumeLevel", 0.50);
                item.isMuted = o.optBoolean("isMuted", false);
                item.volumeControlType = o.optString("volumeControlType", "attenuation");
                if (isPhone) {
                    phoneDevices.putIfAbsent(id, item);
                } else {
                    castDevices.putIfAbsent(id, item);
                }
            }
        } catch (Exception ignored) {}
    }

    private void saveDevicesToPrefs() {
        try {
            JSONArray arr = new JSONArray();
            List<DeviceItem> all = new ArrayList<>();
            all.addAll(castDevices.values());
            all.addAll(phoneDevices.values());
            for (DeviceItem d : all) {
                JSONObject o = new JSONObject();
                o.put("id", d.id);
                o.put("name", d.name);
                o.put("model", d.model != null ? d.model : "");
                o.put("host", d.host);
                o.put("port", d.port);
                o.put("isPhone", d.isPhone);
                o.put("powerOn", d.powerOn);
                o.put("volumeLevel", d.volumeLevel);
                o.put("isMuted", d.isMuted);
                o.put("volumeControlType", d.volumeControlType);
                arr.put(o);
            }
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_SAVED_DEVICES, arr.toString())
                    .apply();
        } catch (Exception ignored) {}
    }

    private void setStatus(String msg) {
        runSafeUi(() -> statusView.setText(msg));
    }

    // MARK: - Discovery & Live Power/Volume Sync

    private void syncGoogleHomePowerStates() {
        GoogleHomePowerBridge.queryPowerStates(this, statesByLowerName -> {
            boolean changed = false;
            for (DeviceItem tv : castDevices.values()) {
                String norm = tv.name.trim().toLowerCase(Locale.US);
                Boolean liveOn = statesByLowerName.get(norm);
                if (liveOn == null) {
                    for (Map.Entry<String, Boolean> e : statesByLowerName.entrySet()) {
                        if (e.getKey().contains(norm) || norm.contains(e.getKey())) {
                            liveOn = e.getValue();
                            break;
                        }
                    }
                }
                if (liveOn != null && tv.powerOn != liveOn) {
                    tv.powerOn = liveOn;
                    changed = true;
                }
            }
            if (changed) {
                saveDevicesToPrefs();
                runSafeUi(this::renderDevices);
            }
        });
    }

    private void refreshDevices() {
        stopDiscovery();
        setStatus("Discovering TVs, projectors, speakers, & peer devices...");
        acquireMulticastLock();
        syncGoogleHomePowerStates();

        castListener = startBrowse(CAST_TYPE, info -> {
            if (info.getHost() == null) return;
            String host = info.getHost().getHostAddress();
            if (host == null) return;
            Map<String, byte[]> attrs = info.getAttributes();
            String friendly = attrStr(attrs, "fn");
            if (friendly == null || friendly.isEmpty()) friendly = info.getServiceName();
            String model = attrStr(attrs, "md");
            String uid = attrStr(attrs, "id");
            if (uid == null || uid.isEmpty()) uid = info.getServiceName();
            int port = info.getPort() > 0 ? info.getPort() : 8009;
            DeviceItem existing = castDevices.get(uid);
            DeviceItem item = new DeviceItem(uid, friendly,
                    (model != null && !model.isEmpty()) ? model : (existing != null ? existing.model : ""),
                    (existing != null && !"SAVED".equals(existing.state)) ? existing.state : "IDLE",
                    existing != null ? existing.title : "",
                    false, host, port);
            if (existing != null) {
                item.powerOn = existing.powerOn;
                item.volumeLevel = existing.volumeLevel;
                item.isMuted = existing.isMuted;
                item.volumeControlType = existing.volumeControlType;
            }
            castDevices.put(uid, item);
            saveDevicesToPrefs();
            runSafeUi(this::renderDevices);
        });

        phoneListener = startBrowse(PHONE_TYPE, info -> {
            if (info.getHost() == null) return;
            String host = info.getHost().getHostAddress();
            if (host == null) return;
            String name = info.getServiceName();
            String id = "phone:" + name;
            int port = info.getPort() > 0 ? info.getPort() : RECEIVER_PORT;
            phoneDevices.put(id, new DeviceItem(id, name, "Cast Receiver", "ONLINE", "", true, host, port));
            saveDevicesToPrefs();
            runSafeUi(this::renderDevices);
        });

        runSafeBg(() -> {
            try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            stopDiscovery();
            for (DeviceItem tv : castDevices.values()) {
                try {
                    CastAndTvEngine.MediaStatus st = engine.getMediaStatus(tv.host, tv.port);
                    if (st != null) {
                        tv.state = st.state;
                        tv.title = st.title;
                        tv.contentId = st.contentId;
                        tv.position = st.position;
                        if (st.volumeLevel >= 0.0) tv.volumeLevel = st.volumeLevel;
                        tv.isMuted = st.isMuted;
                        if (st.volumeControlType != null && !st.volumeControlType.isEmpty()) {
                            tv.volumeControlType = st.volumeControlType;
                        }
                    }
                } catch (Exception ignored) {}
            }
            saveDevicesToPrefs();
            syncGoogleHomePowerStates();
            runSafeUi(() -> {
                renderDevices();
                int tvs = castDevices.size();
                int phones = phoneDevices.size();
                StringBuilder sb = new StringBuilder("Ready · ");
                if (tvs > 0) sb.append(tvs).append(" Cast device(s)");
                if (tvs > 0 && phones > 0) sb.append(" & ");
                if (phones > 0) sb.append(phones).append(" receiver(s)");
                if (tvs == 0 && phones == 0) sb.append("0 devices");
                setStatus(sb.toString());
            });
        });
    }

    private interface OnResolved { void call(NsdServiceInfo info); }

    @SuppressWarnings("deprecation")
    private NsdManager.DiscoveryListener startBrowse(String type, OnResolved cb) {
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String regType) {}
            @Override public void onServiceFound(NsdServiceInfo service) {
                try {
                    nsdManager.resolveService(service, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo si, int code) {}
                        @Override public void onServiceResolved(NsdServiceInfo si) { cb.call(si); }
                    });
                } catch (Exception ignored) {}
            }
            @Override public void onServiceLost(NsdServiceInfo service) {}
            @Override public void onDiscoveryStopped(String serviceType) {}
            @Override public void onStartDiscoveryFailed(String serviceType, int code) {
                try { nsdManager.stopServiceDiscovery(this); } catch (Exception ignored) {}
            }
            @Override public void onStopDiscoveryFailed(String serviceType, int code) {}
        };
        try { nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener); } catch (Exception ignored) {}
        return listener;
    }

    private void stopDiscovery() {
        if (castListener != null) { try { nsdManager.stopServiceDiscovery(castListener); } catch (Exception ignored) {} castListener = null; }
        if (phoneListener != null) { try { nsdManager.stopServiceDiscovery(phoneListener); } catch (Exception ignored) {} phoneListener = null; }
        if (multicastLock != null && multicastLock.isHeld()) {
            try { multicastLock.release(); } catch (Exception ignored) {}
        }
    }

    private void acquireMulticastLock() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && multicastLock == null) {
                multicastLock = wm.createMulticastLock("CastUtilitiesNsd");
                multicastLock.setReferenceCounted(false);
            }
            if (multicastLock != null) multicastLock.acquire();
        } catch (Exception ignored) {}
    }

    private String attrStr(Map<String, byte[]> attrs, String key) {
        if (attrs == null) return null;
        byte[] val = attrs.get(key);
        return val != null ? new String(val, StandardCharsets.UTF_8) : null;
    }

    // MARK: - Card Rendering (Device Type Icons + 2-up Squares + Volume Controls)

    private void togglePin(DeviceItem dev) {
        if (pinnedIds.contains(dev.id)) {
            pinnedIds.remove(dev.id);
            setStatus("Unpinned " + dev.name + ".");
        } else {
            pinnedIds.add(dev.id);
            setStatus("Pinned " + dev.name + " to top.");
        }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_PINNED, new HashSet<>(pinnedIds))
                .apply();
        renderDevices();
    }

    private void renderDevices() {
        devicesContainer.removeAllViews();
        List<DeviceItem> all = new ArrayList<>();
        all.addAll(castDevices.values());
        all.addAll(phoneDevices.values());
        Collections.sort(all, new Comparator<DeviceItem>() {
            @Override
            public int compare(DeviceItem a, DeviceItem b) {
                boolean pa = pinnedIds.contains(a.id);
                boolean pb = pinnedIds.contains(b.id);
                if (pa != pb) return pa ? -1 : 1;
                return a.name.toLowerCase(Locale.US).compareTo(b.name.toLowerCase(Locale.US));
            }
        });

        for (DeviceItem dev : all) {
            boolean isPinned = pinnedIds.contains(dev.id);
            boolean isPaired = !dev.isPhone && engine.isPaired(dev.id);
            DeviceKind kind = classifyDevice(dev);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable cardBg = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{COL_CARD, Color.parseColor("#101626")});
            cardBg.setCornerRadius(dp(14));
            cardBg.setStroke(dp(isPinned ? 2 : 1), isPinned ? COL_AMBER : COL_SLATE_TOP);
            card.setBackground(cardBg);
            card.setPadding(dp(14), dp(14), dp(14), dp(14));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardLp.bottomMargin = dp(12);
            card.setLayoutParams(cardLp);

            // Card Header: [Device Type Icon Badge] + [Name & Type/State] + [Pair] + [Pin]
            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);

            TextView iconBadge = new TextView(this);
            iconBadge.setText(iconGlyphForKind(kind));
            iconBadge.setTextSize(18f);
            iconBadge.setGravity(Gravity.CENTER);
            GradientDrawable badgeBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, badgeColorsForKind(kind));
            badgeBg.setCornerRadius(dp(10));
            iconBadge.setBackground(badgeBg);
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dp(40), dp(40));
            badgeLp.rightMargin = dp(10);
            iconBadge.setLayoutParams(badgeLp);
            header.addView(iconBadge);

            LinearLayout titleCol = new LinearLayout(this);
            titleCol.setOrientation(LinearLayout.VERTICAL);
            titleCol.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView nameView = new TextView(this);
            nameView.setText(dev.name);
            nameView.setTextColor("PLAYING".equals(dev.state) ? COL_NEON_CYAN : (dev.isPhone ? COL_MAGENTA : COL_TEXT));
            nameView.setTextSize(15f);
            nameView.setTypeface(Typeface.DEFAULT_BOLD);
            titleCol.addView(nameView);

            TextView subView = new TextView(this);
            String mediaExtra = (dev.title != null && !dev.title.isEmpty()) ? " · " + dev.title : "";
            subView.setText(labelForKind(dev, kind) + " · " + dev.state + mediaExtra);
            subView.setTextColor(COL_MUTED);
            subView.setTextSize(11.5f);
            titleCol.addView(subView);
            header.addView(titleCol);

            if (!dev.isPhone) {
                Button btnPair = makeHeaderPill(
                        isPaired ? "Paired ✓" : "Pair",
                        isPaired ? new int[]{COL_EMERALD, COL_CYAN} : new int[]{COL_SLATE_TOP, COL_SLATE_BOT},
                        Color.WHITE,
                        Color.TRANSPARENT);
                LinearLayout.LayoutParams pairLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(HEADER_PILL_HEIGHT_DP));
                pairLp.rightMargin = dp(6);
                btnPair.setLayoutParams(pairLp);
                btnPair.setOnClickListener(v -> pairTv(dev));
                header.addView(btnPair);
            }

            Button btnPin = makeHeaderPill(
                    isPinned ? "★ Pinned" : "☆ Pin",
                    isPinned ? new int[]{COL_AMBER, COL_MAGENTA} : new int[]{COL_SLATE_TOP, COL_SLATE_BOT},
                    isPinned ? Color.WHITE : COL_MUTED,
                    Color.TRANSPARENT);
            btnPin.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(HEADER_PILL_HEIGHT_DP)));
            btnPin.setOnClickListener(v -> togglePin(dev));
            header.addView(btnPin);
            card.addView(header);

            // Row 1 (2 uniform gradient squares): Play Here | Mirror Here
            Button btnPlay = makeControlSquare("Play Here", new int[]{COL_CYAN, COL_VIOLET}, Color.WHITE, Color.TRANSPARENT);
            btnPlay.setOnClickListener(v -> playOnDevice(dev));

            Button btnMirror = makeControlSquare("Mirror Here", new int[]{COL_MAGENTA, COL_VIOLET}, Color.WHITE, Color.TRANSPARENT);
            btnMirror.setOnClickListener(v -> mirrorTargets(dev));
            card.addView(makeTwoUpRow(btnPlay, btnMirror, 12));

            // Row 2 for Cast Devices (2 uniform gradient squares): Live Power SwitchCompat | Stop Cast
            if (!dev.isPhone) {
                LinearLayout powerSwitchBox = makeSwitchSquare(
                        dev.powerOn ? "Power · ON" : "Power · OFF",
                        dev.powerOn,
                        null,
                        () -> togglePowerDevice(dev));

                Button btnStop = makeControlSquare("Stop Cast", new int[]{COL_SLATE_TOP, COL_SLATE_BOT}, COL_TEXT, COL_SLATE_TOP);
                btnStop.setOnClickListener(v -> stopDevice(dev));
                card.addView(makeTwoUpRow(powerSwitchBox, btnStop, 8));
            }

            // Row 3: Volume Control (only where applicable)
            if (supportsVolumeControl(dev)) {
                card.addView(buildVolumeSection(dev));
            }

            devicesContainer.addView(card);
        }
    }

    private View buildVolumeSection(DeviceItem dev) {
        LinearLayout volWrap = new LinearLayout(this);
        volWrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wrapLp.topMargin = dp(8);
        volWrap.setLayoutParams(wrapLp);

        boolean isFixed = "fixed".equalsIgnoreCase(dev.volumeControlType);
        int pct = (int) Math.round(Math.max(0.0, Math.min(1.0, dev.volumeLevel)) * 100.0);

        if (!isFixed) {
            // Interactive Volume Slider bar + % readout + Mute pill
            LinearLayout sliderRow = new LinearLayout(this);
            sliderRow.setOrientation(LinearLayout.HORIZONTAL);
            sliderRow.setGravity(Gravity.CENTER_VERTICAL);
            sliderRow.setBackground(gradientButtonBg(new int[]{COL_BG, COL_SLATE_BOT}, COL_SLATE_TOP));
            sliderRow.setPadding(dp(12), dp(6), dp(8), dp(6));

            TextView volLabel = new TextView(this);
            volLabel.setText(dev.isMuted ? "\uD83D\uDD07 Muted" : ("\uD83D\uDD0A " + pct + "%"));
            volLabel.setTextColor(dev.isMuted ? COL_AMBER : COL_TEXT);
            volLabel.setTextSize(12.5f);
            volLabel.setTypeface(Typeface.DEFAULT_BOLD);
            volLabel.setMinWidth(dp(64));
            sliderRow.addView(volLabel);

            SeekBar seek = new SeekBar(this);
            seek.setMax(100);
            seek.setProgress(pct);
            seek.getProgressDrawable().setColorFilter(COL_CYAN, PorterDuff.Mode.SRC_IN);
            seek.getThumb().setColorFilter(COL_NEON_CYAN, PorterDuff.Mode.SRC_IN);
            seek.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    if (fromUser) {
                        volLabel.setText("\uD83D\uDD0A " + progress + "%");
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                    setDeviceVolume(dev, sb.getProgress() / 100.0);
                }
            });
            sliderRow.addView(seek);

            Button btnMute = makeHeaderPill(
                    dev.isMuted ? "Unmute" : "Mute",
                    dev.isMuted ? new int[]{COL_AMBER, COL_CRIMSON} : new int[]{COL_SLATE_TOP, COL_SLATE_BOT},
                    Color.WHITE,
                    Color.TRANSPARENT);
            btnMute.setOnClickListener(v -> toggleDeviceMute(dev));
            sliderRow.addView(btnMute);

            volWrap.addView(sliderRow);
        }

        // Uniform 2-per-line gradient Volume Step Squares (great for both Phone thumbs & Google TV D-pad!)
        Button btnVolDown = makeControlSquare("\uD83D\uDD09  Vol −", new int[]{COL_SLATE_TOP, COL_SLATE_BOT}, COL_TEXT, COL_SLATE_TOP);
        btnVolDown.setOnClickListener(v -> stepDeviceVolume(dev, -0.10));

        Button btnVolUp = makeControlSquare("\uD83D\uDD0A  Vol +", new int[]{COL_VIOLET, COL_CYAN}, Color.WHITE, Color.TRANSPARENT);
        btnVolUp.setOnClickListener(v -> stepDeviceVolume(dev, +0.10));

        volWrap.addView(makeTwoUpRow(btnVolDown, btnVolUp, isFixed ? 0 : 8));
        return volWrap;
    }

    // MARK: - Volume Actions

    private void setDeviceVolume(DeviceItem dev, double targetLevel) {
        final double clamped = Math.max(0.0, Math.min(1.0, targetLevel));
        dev.volumeLevel = clamped;
        dev.isMuted = false;
        int pct = (int) Math.round(clamped * 100);
        setStatus("Setting " + dev.name + " volume to " + pct + "%...");
        runSafeBg(() -> {
            try {
                double actual = engine.setCastVolume(dev.host, dev.port, clamped);
                dev.volumeLevel = actual;
                saveDevicesToPrefs();
                setStatus(dev.name + " volume: " + Math.round(actual * 100) + "%");
                runSafeUi(this::renderDevices);
            } catch (Exception e) {
                setStatus("Volume failed on " + dev.name + ": " + e.getMessage());
            }
        });
    }

    private void stepDeviceVolume(DeviceItem dev, double delta) {
        if ("fixed".equalsIgnoreCase(dev.volumeControlType) && engine.isPaired(dev.id)) {
            long key = delta > 0 ? CastAndTvEngine.KEYCODE_VOLUME_UP : CastAndTvEngine.KEYCODE_VOLUME_DOWN;
            setStatus((delta > 0 ? "Volume + " : "Volume − ") + dev.name + "...");
            runSafeBg(() -> {
                try {
                    engine.sendRemoteKey(dev.id, dev.host, key);
                    setStatus("Sent volume step to " + dev.name + ".");
                } catch (Exception e) {
                    setStatus("Volume key failed: " + e.getMessage());
                }
            });
            return;
        }
        setDeviceVolume(dev, dev.volumeLevel + delta);
    }

    private void toggleDeviceMute(DeviceItem dev) {
        final boolean targetMute = !dev.isMuted;
        dev.isMuted = targetMute;
        renderDevices();
        setStatus((targetMute ? "Muting " : "Unmuting ") + dev.name + "...");
        runSafeBg(() -> {
            try {
                dev.isMuted = engine.setCastMuted(dev.host, dev.port, targetMute);
                saveDevicesToPrefs();
                setStatus((dev.isMuted ? "Muted " : "Unmuted ") + dev.name + ".");
                runSafeUi(this::renderDevices);
            } catch (Exception e) {
                dev.isMuted = !targetMute;
                runSafeUi(this::renderDevices);
                setStatus("Mute failed: " + e.getMessage());
            }
        });
    }

    // MARK: - Cast / Mirror / Stop / Power Switch Actions

    private void playOnDevice(DeviceItem dev) {
        CastWire.ParsedLink parsed = CastWire.parseYouTubeLink(linkInput.getText().toString());
        if (parsed.videoId == null) {
            setStatus("Paste a valid YouTube link first.");
            return;
        }
        setStatus("Playing on " + dev.name + "...");
        runSafeBg(() -> {
            try {
                if (dev.isPhone) {
                    engine.sendToPhone(dev.host, dev.port, parsed.videoId, parsed.startSeconds);
                } else {
                    String screenId = engine.launchYouTubeAndGetScreenId(dev.host, dev.port);
                    engine.playYouTubeOnScreen(screenId, parsed.videoId, parsed.startSeconds);
                    dev.state = "PLAYING";
                    dev.contentId = parsed.videoId;
                    dev.powerOn = true;
                    saveDevicesToPrefs();
                }
                setStatus("Playing on " + dev.name + ".");
                runSafeUi(this::renderDevices);
            } catch (Exception e) {
                setStatus("Play on " + dev.name + " failed: " + e.getMessage());
            }
        });
    }

    private void playOnPinnedDevices() {
        CastWire.ParsedLink parsed = CastWire.parseYouTubeLink(linkInput.getText().toString());
        if (parsed.videoId == null) {
            setStatus("Paste a valid YouTube link first.");
            return;
        }
        List<DeviceItem> targets = new ArrayList<>();
        for (DeviceItem d : castDevices.values()) {
            if (pinnedIds.contains(d.id)) targets.add(d);
        }
        for (DeviceItem d : phoneDevices.values()) {
            if (pinnedIds.contains(d.id)) targets.add(d);
        }
        if (targets.isEmpty()) targets.addAll(castDevices.values());
        if (targets.isEmpty()) {
            setStatus("No devices available. Tap Refresh first.");
            return;
        }
        setStatus("Casting to " + targets.size() + " device(s)...");
        runSafeBg(() -> {
            int ok = 0;
            for (DeviceItem dev : targets) {
                try {
                    if (dev.isPhone) {
                        engine.sendToPhone(dev.host, dev.port, parsed.videoId, parsed.startSeconds);
                    } else {
                        String screenId = engine.launchYouTubeAndGetScreenId(dev.host, dev.port);
                        engine.playYouTubeOnScreen(screenId, parsed.videoId, parsed.startSeconds);
                        dev.state = "PLAYING";
                        dev.contentId = parsed.videoId;
                        dev.powerOn = true;
                    }
                    ok++;
                } catch (Exception ignored) {}
            }
            saveDevicesToPrefs();
            setStatus("Cast started on " + ok + " of " + targets.size() + " device(s).");
            runSafeUi(this::renderDevices);
        });
    }

    private void mirrorTargets(DeviceItem singleTarget) {
        setStatus("Mirroring...");
        runSafeBg(() -> {
            try {
                DeviceItem source = null;
                for (DeviceItem tv : castDevices.values()) {
                    try {
                        CastAndTvEngine.MediaStatus st = engine.getMediaStatus(tv.host, tv.port);
                        if (st != null && st.contentId != null &&
                                ("PLAYING".equals(st.state) || "PAUSED".equals(st.state) || "BUFFERING".equals(st.state))) {
                            tv.state = st.state;
                            tv.title = st.title;
                            tv.contentId = st.contentId;
                            tv.position = st.position;
                            source = tv;
                            break;
                        }
                    } catch (Exception ignored) {}
                }
                if (source == null || source.contentId == null) {
                    setStatus("Nothing playing to mirror. Start YouTube on one TV first.");
                    return;
                }
                List<DeviceItem> targets = new ArrayList<>();
                if (singleTarget != null) {
                    targets.add(singleTarget);
                } else {
                    for (DeviceItem tv : castDevices.values()) {
                        if (!tv.id.equals(source.id) && !tv.name.toLowerCase(Locale.US).endsWith("group")) {
                            targets.add(tv);
                        }
                    }
                    targets.addAll(phoneDevices.values());
                }
                int okCount = 0;
                int startSec = (int) source.position;
                for (DeviceItem t : targets) {
                    try {
                        if (t.isPhone) {
                            engine.sendToPhone(t.host, t.port, source.contentId, startSec);
                        } else {
                            String screenId = engine.launchYouTubeAndGetScreenId(t.host, t.port);
                            engine.playYouTubeOnScreen(screenId, source.contentId, startSec);
                            t.state = "PLAYING";
                            t.powerOn = true;
                        }
                        okCount++;
                    } catch (Exception ignored) {}
                }
                saveDevicesToPrefs();
                setStatus("Mirroring from " + source.name + " to " + okCount + " device(s).");
                runSafeUi(this::renderDevices);
            } catch (Exception e) {
                setStatus("Mirror failed: " + e.getMessage());
            }
        });
    }

    private void stopDevice(DeviceItem dev) {
        setStatus("Stopping " + dev.name + "...");
        runSafeBg(() -> {
            try {
                engine.stopCastApp(dev.host, dev.port);
                dev.state = "IDLE";
                dev.title = "";
                setStatus("Stopped " + dev.name + ".");
                runSafeUi(this::renderDevices);
            } catch (Exception e) {
                setStatus("Stop failed: " + e.getMessage());
            }
        });
    }

    private void stopAllDevices() {
        setStatus("Stopping all TVs...");
        runSafeBg(() -> {
            for (DeviceItem tv : castDevices.values()) {
                try {
                    engine.stopCastApp(tv.host, tv.port);
                    tv.state = "IDLE";
                    tv.title = "";
                } catch (Exception ignored) {}
            }
            setStatus("Stopped all TVs.");
            runSafeUi(this::renderDevices);
        });
    }

    private void powerOffAllDevices() {
        List<DeviceItem> tvs = new ArrayList<>(castDevices.values());
        if (tvs.isEmpty()) {
            setStatus("No TVs found.");
            return;
        }
        setStatus("Turning off " + tvs.size() + " device(s) via Google Home...");
        for (DeviceItem tv : tvs) {
            GoogleHomePowerBridge.setPower(this, tv.name, false, (success, msg) -> {
                if (success) {
                    tv.powerOn = false;
                    tv.state = "IDLE";
                    saveDevicesToPrefs();
                    runSafeUi(this::renderDevices);
                }
            });
        }
    }

    private void togglePowerDevice(DeviceItem dev) {
        final boolean previousOn = dev.powerOn;
        final boolean targetOn = !previousOn;
        dev.powerOn = targetOn;
        renderDevices();
        setStatus((targetOn ? "Turning ON " : "Turning OFF ") + dev.name + "...");

        GoogleHomePowerBridge.setPower(this, dev.name, targetOn, (success, msg) -> {
            if (isFinishing() || isDestroyed()) return;
            if (success) {
                dev.powerOn = targetOn;
                if (!targetOn) dev.state = "IDLE";
                saveDevicesToPrefs();
                setStatus("Turned " + (targetOn ? "ON " : "OFF ") + msg + " via Google Home.");
                runSafeUi(this::renderDevices);
                return;
            }
            runSafeBg(() -> {
                if (engine.isPaired(dev.id)) {
                    try {
                        engine.sendPower(dev.id, dev.host,
                                targetOn ? CastAndTvEngine.PowerAction.POWER_ON : CastAndTvEngine.PowerAction.POWER_OFF);
                        dev.powerOn = targetOn;
                        if (!targetOn) dev.state = "IDLE";
                        saveDevicesToPrefs();
                        setStatus("Turned " + (targetOn ? "ON " : "OFF ") + dev.name + " via Android TV Remote.");
                        runSafeUi(this::renderDevices);
                        return;
                    } catch (Exception e) {
                        dev.powerOn = previousOn;
                        runSafeUi(this::renderDevices);
                        setStatus("Power switch failed: " + e.getMessage());
                        return;
                    }
                }
                if (targetOn) {
                    try {
                        engine.wakeViaCast(dev.host, dev.port);
                        dev.powerOn = true;
                        saveDevicesToPrefs();
                        setStatus("Woke " + dev.name + " via Cast HDMI-CEC.");
                        runSafeUi(this::renderDevices);
                        return;
                    } catch (Exception ignored) {}
                } else {
                    try {
                        engine.beginPairing(dev.id, dev.host);
                        dev.powerOn = previousOn;
                        runSafeUi(() -> {
                            renderDevices();
                            showPinDialog(dev);
                        });
                        return;
                    } catch (Exception ignored) {}
                }
                dev.powerOn = previousOn;
                runSafeUi(this::renderDevices);
                setStatus("Power switch unavailable (" + msg + ").");
            });
        });
    }

    private void pairTv(DeviceItem dev) {
        setStatus("Connecting to " + dev.name + " for PIN pairing...");
        runSafeBg(() -> {
            try {
                engine.beginPairing(dev.id, dev.host);
                runSafeUi(() -> showPinDialog(dev));
            } catch (Exception e) {
                setStatus("Pair " + dev.name + " failed: " + e.getMessage());
            }
        });
    }

    private void showDiagDialog() {
        String report = GoogleHomePowerBridge.readDiagnosticReport(this);
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(report.length() > 8000 ? report.substring(report.length() - 8000) : report);
        tv.setTextSize(11f);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(16), dp(16), dp(16), dp(16));
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("Diagnostic & Crash Log")
                .setView(sv)
                .setPositiveButton("Close", null)
                .setNeutralButton("Copy Log", (d, w) -> {
                    GoogleHomePowerBridge.copyToClipboard(this, "Cast Utilities Log", report);
                    Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void showPinDialog(DeviceItem dev) {
        EditText pinInput = new EditText(this);
        pinInput.setHint("e.g. A1B2C3");
        pinInput.setInputType(InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        pinInput.setTextSize(18f);
        pinInput.setPadding(dp(12), dp(12), dp(12), dp(12));

        new AlertDialog.Builder(this)
                .setTitle("Pair with " + dev.name)
                .setMessage("Enter the 6-character hex code shown on your TV.")
                .setView(pinInput)
                .setCancelable(false)
                .setPositiveButton("Confirm Pair", (d, w) -> {
                    String pin = pinInput.getText().toString().trim();
                    setStatus("Verifying PIN...");
                    runSafeBg(() -> {
                        try {
                            engine.completePairing(pin);
                            setStatus("Paired with " + dev.name + "!");
                            runSafeUi(this::renderDevices);
                        } catch (Exception e) {
                            engine.cancelPairing();
                            setStatus("Pairing failed: " + e.getMessage());
                        }
                    });
                })
                .setNegativeButton("Cancel", (d, w) -> {
                    engine.cancelPairing();
                    setStatus("Pairing cancelled.");
                })
                .show();
    }

    // MARK: - Device Cast Receiver Switch (Port 8766)

    private void updateReceiveSwitchUi() {
        runSafeUi(() -> {
            if (receiveSwitch != null) receiveSwitch.setChecked(isReceiving);
            if (receiveSwitchContainer != null) {
                int[] cols = isReceiving ? new int[]{COL_EMERALD, COL_CYAN} : new int[]{COL_SLATE_TOP, COL_SLATE_BOT};
                receiveSwitchContainer.setBackground(gradientButtonBg(cols, isReceiving ? Color.TRANSPARENT : COL_SLATE_TOP));
            }
        });
    }

    private void toggleReceiving() {
        if (isReceiving) stopReceiving(); else startReceiving();
    }

    private void startReceiving() {
        try {
            ServerSocket server = new ServerSocket(RECEIVER_PORT);
            receiverServer = server;
            isReceiving = true;
            String name = "Cast Utilities " + Build.MODEL;
            updateReceiveSwitchUi();
            setStatus("Receiving as " + name + " - keep app open.");

            runSafeBg(() -> {
                while (isReceiving && !server.isClosed()) {
                    try {
                        Socket client = server.accept();
                        runSafeBg(() -> handleIncomingCast(client));
                    } catch (Exception e) {
                        break;
                    }
                }
            });

            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(name);
            info.setServiceType(PHONE_TYPE);
            info.setPort(RECEIVER_PORT);
            regListener = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo si) {}
                @Override public void onRegistrationFailed(NsdServiceInfo si, int code) {}
                @Override public void onServiceUnregistered(NsdServiceInfo si) {}
                @Override public void onUnregistrationFailed(NsdServiceInfo si, int code) {}
            };
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, regListener);
        } catch (Exception e) {
            isReceiving = false;
            updateReceiveSwitchUi();
            setStatus("Could not start receiver: " + e.getMessage());
        }
    }

    private void stopReceiving() {
        isReceiving = false;
        if (regListener != null) { try { nsdManager.unregisterService(regListener); } catch (Exception ignored) {} regListener = null; }
        if (receiverServer != null) { try { receiverServer.close(); } catch (Exception ignored) {} receiverServer = null; }
        updateReceiveSwitchUi();
    }

    private void handleIncomingCast(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(4000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String reqLine = reader.readLine();
            if (reqLine == null) return;
            int contentLen = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase(Locale.US).startsWith("content-length:")) {
                    try { contentLen = Math.min(65536, Math.max(0, Integer.parseInt(line.substring(15).trim()))); } catch (Exception ignored) {}
                }
            }
            String body = "";
            if (contentLen > 0) {
                char[] buf = new char[contentLen];
                int read = 0;
                while (read < contentLen) {
                    int n = reader.read(buf, read, contentLen - read);
                    if (n < 0) break;
                    read += n;
                }
                body = new String(buf, 0, read);
            }
            String resp;
            if (reqLine.startsWith("POST /play")) {
                try {
                    JSONObject obj = new JSONObject(body);
                    String vid = obj.optString("video_id", "").trim();
                    int start = Math.max(0, obj.optInt("start", 0));
                    if (vid.matches("^[A-Za-z0-9_-]{1,32}$")) {
                        runSafeUi(() -> {
                            setStatus("Incoming cast: " + vid);
                            try {
                                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + vid + "&t=" + start + "s"));
                                startActivity(i);
                            } catch (Exception e) {
                                Toast.makeText(this, "No YouTube app available", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                } catch (Exception ignored) {}
                resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}";
            } else if (reqLine.startsWith("GET /ping")) {
                resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}";
            } else {
                resp = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
            }
            s.getOutputStream().write(resp.getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
        } catch (Exception ignored) {}
    }
}
