/**
 * @file CastRemoteActivity.java
 * @summary Redesigned Phone & Google TV Cast Remote screen with uniform gradient buttons, 2-per-line control squares, and Power On/Off toggle.
 * @description Renders a consistent dark-neon gradient UI where every menu and device control shares the same rounded shape in 2-column rows, backed by GoogleHomePowerBridge, CastAndTvEngine, and SharedPreferences pinning.
 * @testing Compiled and lint-checked via Gradle signed assembleRelease (v2.1) with D-pad focus states and touch targets verified.
 */
package com.project.autoswitch.ui.cast;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
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
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
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

    // Uniform button geometry (all controls share identical shape & radius)
    private static final int BTN_RADIUS_DP = 12;
    private static final int CONTROL_SQUARE_HEIGHT_DP = 56;
    private static final int HEADER_PILL_HEIGHT_DP = 36;

    private static final class DeviceItem {
        final String id;
        final String name;
        String state;
        String title;
        String contentId;
        double position;
        final boolean isPhone;
        final String host;
        final int port;
        boolean powerOn;

        DeviceItem(String id, String name, String state, String title, boolean isPhone, String host, int port) {
            this.id = id;
            this.name = name;
            this.state = state;
            this.title = title;
            this.isPhone = isPhone;
            this.host = host;
            this.port = port;
            // Default to ON when discovered on network so first tap turns TV OFF cleanly.
            this.powerOn = true;
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
    private Button btnReceiveToggle;
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
        refreshDevices();
    }

    @Override
    protected void onDestroy() {
        stopDiscovery();
        stopReceiving();
        engine.cancelPairing();
        worker.shutdownNow();
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /**
     * Unified gradient button background with identical rounded shape and neon D-pad focus border.
     */
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

    /**
     * Creates a uniform control square button (identical height, shape, typography, and gradient style).
     */
    private Button makeControlSquare(String label, int[] gradientColors, int textColor, int strokeNormal) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTextSize(14f);
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

    /**
     * Creates a compact header pill button (same rounded gradient shape, smaller height for Pin/Pair/Back).
     */
    private Button makeHeaderPill(String label, int[] gradientColors, int textColor, int strokeNormal) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTextSize(12f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackground(gradientButtonBg(gradientColors, strokeNormal));
        b.setPadding(dp(12), dp(4), dp(12), dp(4));
        b.setMinHeight(dp(HEADER_PILL_HEIGHT_DP));
        b.setMinimumHeight(dp(HEADER_PILL_HEIGHT_DP));
        b.setFocusable(true);
        b.setClickable(true);
        return b;
    }

    /**
     * Places exactly 2 identical control squares side-by-side in a single row.
     */
    private LinearLayout makeTwoUpRow(Button left, Button right, int topMarginDp) {
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

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COL_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(32));
        scroll.addView(root);

        // Header bar
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
        topBar.addView(title);
        root.addView(topBar);

        statusView = new TextView(this);
        statusView.setText("Discovering TVs & phones...");
        statusView.setTextColor(COL_MUTED);
        statusView.setTextSize(13f);
        statusView.setPadding(0, dp(10), 0, dp(10));
        root.addView(statusView);

        // YouTube link input (same rounded corner radius)
        linkInput = new EditText(this);
        linkInput.setHint("Paste a YouTube link (optional)");
        linkInput.setHintTextColor(COL_MUTED);
        linkInput.setTextColor(COL_TEXT);
        linkInput.setSingleLine(true);
        linkInput.setBackground(gradientButtonBg(new int[]{COL_CARD, COL_CARD}, COL_SLATE_TOP));
        linkInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        linkInput.setFocusable(true);
        root.addView(linkInput);

        // Top Menu: 2-per-line uniform gradient control squares
        Button btnMirrorAll = makeControlSquare("Mirror Playing", new int[]{COL_VIOLET, COL_CYAN}, Color.WHITE, Color.TRANSPARENT);
        btnMirrorAll.setOnClickListener(v -> mirrorTargets(null));

        Button btnRefresh = makeControlSquare("Refresh", new int[]{COL_CYAN, COL_VIOLET}, Color.WHITE, Color.TRANSPARENT);
        btnRefresh.setOnClickListener(v -> refreshDevices());
        root.addView(makeTwoUpRow(btnMirrorAll, btnRefresh, 12));

        Button btnStopAll = makeControlSquare("Stop All", new int[]{COL_MAGENTA, COL_AMBER}, Color.WHITE, Color.TRANSPARENT);
        btnStopAll.setOnClickListener(v -> stopAllDevices());

        btnReceiveToggle = makeControlSquare("Receive: OFF", new int[]{COL_SLATE_TOP, COL_SLATE_BOT}, COL_TEXT, COL_SLATE_TOP);
        btnReceiveToggle.setOnClickListener(v -> toggleReceiving());
        root.addView(makeTwoUpRow(btnStopAll, btnReceiveToggle, 8));

        // Spacer before device list
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        listLp.topMargin = dp(16);
        devicesContainer = new LinearLayout(this);
        devicesContainer.setOrientation(LinearLayout.VERTICAL);
        devicesContainer.setLayoutParams(listLp);
        root.addView(devicesContainer);

        return scroll;
    }

    private void setStatus(String msg) {
        mainHandler.post(() -> statusView.setText(msg));
    }

    // MARK: - Discovery

    private void refreshDevices() {
        stopDiscovery();
        setStatus("Discovering Chromecasts, Google TVs, & peer devices...");
        acquireMulticastLock();

        castListener = startBrowse(CAST_TYPE, info -> {
            if (info.getHost() == null) return;
            String host = info.getHost().getHostAddress();
            if (host == null) return;
            Map<String, byte[]> attrs = info.getAttributes();
            String friendly = attrStr(attrs, "fn");
            if (friendly == null || friendly.isEmpty()) friendly = info.getServiceName();
            String uid = attrStr(attrs, "id");
            if (uid == null || uid.isEmpty()) uid = info.getServiceName();
            int port = info.getPort() > 0 ? info.getPort() : 8009;
            DeviceItem existing = castDevices.get(uid);
            DeviceItem item = new DeviceItem(uid, friendly,
                    existing != null ? existing.state : "IDLE",
                    existing != null ? existing.title : "",
                    false, host, port);
            if (existing != null) item.powerOn = existing.powerOn;
            castDevices.put(uid, item);
            mainHandler.post(this::renderDevices);
        });

        phoneListener = startBrowse(PHONE_TYPE, info -> {
            if (info.getHost() == null) return;
            String host = info.getHost().getHostAddress();
            if (host == null) return;
            String name = info.getServiceName();
            String id = "phone:" + name;
            int port = info.getPort() > 0 ? info.getPort() : RECEIVER_PORT;
            phoneDevices.put(id, new DeviceItem(id, name, "PHONE", "Cast Receiver", true, host, port));
            mainHandler.post(this::renderDevices);
        });

        worker.execute(() -> {
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
                    }
                } catch (Exception ignored) {}
            }
            mainHandler.post(() -> {
                renderDevices();
                int tvs = castDevices.size();
                int phones = phoneDevices.size();
                StringBuilder sb = new StringBuilder("Found ");
                if (tvs > 0) sb.append(tvs).append(" TVs");
                if (tvs > 0 && phones > 0) sb.append(" and ");
                if (phones > 0) sb.append(phones).append(" devices");
                if (tvs == 0 && phones == 0) sb.append("0 devices");
                sb.append(".");
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

    // MARK: - Pinning & Uniform 2-per-Line Card Rendering

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

            // Card Header: Device Name/Status + compact Pin (& Pair for TVs) pills
            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);

            TextView label = new TextView(this);
            String titleSuffix = (dev.title != null && !dev.title.isEmpty()) ? " · " + dev.title : "";
            label.setText(dev.name + " · " + dev.state + titleSuffix);
            label.setTextColor("PLAYING".equals(dev.state) ? COL_NEON_CYAN : (dev.isPhone ? COL_MAGENTA : COL_TEXT));
            label.setTextSize(15f);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            header.addView(label);

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

            // Row 2 for TVs (2 uniform gradient squares): Power Toggle (ON/OFF) | Stop
            if (!dev.isPhone) {
                String powerLabel = dev.powerOn ? "Power: ON" : "Power: OFF";
                int[] powerColors = dev.powerOn
                        ? new int[]{COL_EMERALD, COL_CYAN}
                        : new int[]{COL_CRIMSON, COL_MAGENTA};
                Button btnPowerToggle = makeControlSquare(powerLabel, powerColors, Color.WHITE, Color.TRANSPARENT);
                btnPowerToggle.setOnClickListener(v -> togglePowerDevice(dev));

                Button btnStop = makeControlSquare("Stop Cast", new int[]{COL_SLATE_TOP, COL_SLATE_BOT}, COL_TEXT, COL_SLATE_TOP);
                btnStop.setOnClickListener(v -> stopDevice(dev));
                card.addView(makeTwoUpRow(btnPowerToggle, btnStop, 8));
            }

            devicesContainer.addView(card);
        }
    }

    // MARK: - Cast / Mirror / Stop / Power Toggle Actions

    private void playOnDevice(DeviceItem dev) {
        CastWire.ParsedLink parsed = CastWire.parseYouTubeLink(linkInput.getText().toString());
        if (parsed.videoId == null) {
            setStatus("Paste a valid YouTube link first.");
            return;
        }
        setStatus("Playing on " + dev.name + "...");
        worker.execute(() -> {
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
                setStatus("Playing on " + dev.name + ".");
                mainHandler.post(this::renderDevices);
            } catch (Exception e) {
                setStatus("Play on " + dev.name + " failed: " + e.getMessage());
            }
        });
    }

    private void mirrorTargets(DeviceItem singleTarget) {
        setStatus("Mirroring...");
        worker.execute(() -> {
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
                setStatus("Mirroring from " + source.name + " to " + okCount + " device(s).");
                mainHandler.post(this::renderDevices);
            } catch (Exception e) {
                setStatus("Mirror failed: " + e.getMessage());
            }
        });
    }

    private void stopDevice(DeviceItem dev) {
        setStatus("Stopping " + dev.name + "...");
        worker.execute(() -> {
            try {
                engine.stopCastApp(dev.host, dev.port);
                dev.state = "IDLE";
                dev.title = "";
                setStatus("Stopped " + dev.name + ".");
                mainHandler.post(this::renderDevices);
            } catch (Exception e) {
                setStatus("Stop failed: " + e.getMessage());
            }
        });
    }

    private void stopAllDevices() {
        setStatus("Stopping all TVs...");
        worker.execute(() -> {
            for (DeviceItem tv : castDevices.values()) {
                try {
                    engine.stopCastApp(tv.host, tv.port);
                    tv.state = "IDLE";
                    tv.title = "";
                } catch (Exception ignored) {}
            }
            setStatus("Stopped all TVs.");
            mainHandler.post(this::renderDevices);
        });
    }

    /**
     * Single Power Toggle: switches TV between ON and OFF via Google Home SDK OnOff trait,
     * falling back to Android TV Remote v2 and Cast HDMI-CEC wake.
     */
    private void togglePowerDevice(DeviceItem dev) {
        final boolean targetOn = !dev.powerOn;
        setStatus((targetOn ? "Turning ON " : "Turning OFF ") + dev.name + " via Google Home...");
        GoogleHomePowerBridge.setPower(this, dev.name, targetOn, (success, msg) -> {
            if (success) {
                dev.powerOn = targetOn;
                if (!targetOn) dev.state = "IDLE";
                setStatus("Turned " + (targetOn ? "ON " : "OFF ") + msg + " via Google Home.");
                mainHandler.post(this::renderDevices);
                return;
            }
            worker.execute(() -> {
                if (engine.isPaired(dev.id)) {
                    try {
                        engine.sendPower(dev.id, dev.host,
                                targetOn ? CastAndTvEngine.PowerAction.POWER_ON : CastAndTvEngine.PowerAction.POWER_OFF);
                        dev.powerOn = targetOn;
                        if (!targetOn) dev.state = "IDLE";
                        setStatus("Turned " + (targetOn ? "ON " : "OFF ") + dev.name + " via Android TV Remote.");
                        mainHandler.post(this::renderDevices);
                        return;
                    } catch (Exception e) {
                        setStatus("Power toggle failed: " + e.getMessage());
                        return;
                    }
                }
                if (targetOn) {
                    try {
                        engine.wakeViaCast(dev.host, dev.port);
                        dev.powerOn = true;
                        setStatus("Woke " + dev.name + " via Cast HDMI-CEC.");
                        mainHandler.post(this::renderDevices);
                        return;
                    } catch (Exception ignored) {}
                } else {
                    try {
                        engine.beginPairing(dev.id, dev.host);
                        mainHandler.post(() -> showPinDialog(dev));
                        return;
                    } catch (Exception ignored) {}
                }
                setStatus("Could not toggle power on " + dev.name + " (" + msg + ").");
            });
        });
    }

    private void pairTv(DeviceItem dev) {
        setStatus("Connecting to " + dev.name + " for PIN pairing...");
        worker.execute(() -> {
            try {
                engine.beginPairing(dev.id, dev.host);
                mainHandler.post(() -> showPinDialog(dev));
            } catch (Exception e) {
                setStatus("Pair " + dev.name + " failed: " + e.getMessage());
            }
        });
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
                    worker.execute(() -> {
                        try {
                            engine.completePairing(pin);
                            setStatus("Paired with " + dev.name + "!");
                            mainHandler.post(this::renderDevices);
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

    // MARK: - Device Cast Receiver Toggle (Port 8766)

    private void toggleReceiving() {
        if (isReceiving) stopReceiving(); else startReceiving();
    }

    private void startReceiving() {
        try {
            ServerSocket server = new ServerSocket(RECEIVER_PORT);
            receiverServer = server;
            isReceiving = true;
            String name = "Cast Utilities " + Build.MODEL;
            btnReceiveToggle.setText("Receive: ON");
            btnReceiveToggle.setTextColor(Color.WHITE);
            btnReceiveToggle.setBackground(gradientButtonBg(new int[]{COL_EMERALD, COL_CYAN}, Color.TRANSPARENT));
            setStatus("Receiving as " + name + " - keep app open.");

            worker.execute(() -> {
                while (isReceiving && !server.isClosed()) {
                    try {
                        Socket client = server.accept();
                        worker.execute(() -> handleIncomingCast(client));
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
            setStatus("Could not start receiver: " + e.getMessage());
        }
    }

    private void stopReceiving() {
        isReceiving = false;
        if (regListener != null) { try { nsdManager.unregisterService(regListener); } catch (Exception ignored) {} regListener = null; }
        if (receiverServer != null) { try { receiverServer.close(); } catch (Exception ignored) {} receiverServer = null; }
        mainHandler.post(() -> {
            if (btnReceiveToggle != null) {
                btnReceiveToggle.setText("Receive: OFF");
                btnReceiveToggle.setTextColor(COL_TEXT);
                btnReceiveToggle.setBackground(gradientButtonBg(new int[]{COL_SLATE_TOP, COL_SLATE_BOT}, COL_SLATE_TOP));
            }
        });
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
                        mainHandler.post(() -> {
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
