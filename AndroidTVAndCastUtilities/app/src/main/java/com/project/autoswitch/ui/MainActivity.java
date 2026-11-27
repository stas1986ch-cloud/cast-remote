/**
 * @file MainActivity.java
 * @summary Main launcher hub for Google Screen Control and Cast with drag-and-drop tile reordering and hide/show tile controls.
 * @description Adapts GridLayout columns to screen width, persists custom tile order and hidden tile IDs in SharedPreferences, and routes to HDMI Auto-Boot, Cast Device Controls, and Nest Monitor.
 * @testing Verified via Gradle testDebugUnitTest, lintVitalRelease, and assembleRelease.
 */
package com.project.autoswitch.ui;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.card.MaterialCardView;
import com.project.autoswitch.ui.cast.CastRemoteActivity;
import com.project.autoswitch.ui.nest.NestMonitorActivity;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
    private static final String HUB_PREFS = "HubTilePrefs";
    private static final String KEY_HUB_ORDER = "hub_tile_order_v1";
    private static final String KEY_HUB_HIDDEN = "hub_tile_hidden_v1";

    private static final String TILE_HDMI = "hdmi";
    private static final String TILE_CAST = "cast";
    private static final String TILE_NEST = "nest";

    private GridLayout hubGrid;
    private Button btnHubHiddenTiles;
    private TextView hdmiSubtitle;
    private TextView castSubtitle;
    private TextView nestSubtitle;

    private final Map<String, MaterialCardView> tileViews = new HashMap<>();
    private final Map<String, TextView> hideButtons = new HashMap<>();
    private List<String> tileOrder = new ArrayList<>();
    private Set<String> hiddenTiles = new HashSet<>();
    private boolean showHiddenTiles = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        hubGrid = findViewById(R.id.hub_grid);
        btnHubHiddenTiles = findViewById(R.id.btn_hub_hidden_tiles);
        hdmiSubtitle = findViewById(R.id.tile_hdmi_subtitle);
        castSubtitle = findViewById(R.id.tile_cast_subtitle);
        nestSubtitle = findViewById(R.id.tile_nest_subtitle);

        if (hubGrid != null) {
            float dpWidth = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
            int cols = dpWidth >= 700f ? 3 : (dpWidth >= 460f ? 2 : 1);
            hubGrid.setColumnCount(cols);
        }

        SharedPreferences prefs = getSharedPreferences(HUB_PREFS, Context.MODE_PRIVATE);
        hiddenTiles = new HashSet<>(prefs.getStringSet(KEY_HUB_HIDDEN, new HashSet<>()));
        loadTileOrder();

        MaterialCardView hdmiTile = findViewById(R.id.tile_hdmi_settings);
        MaterialCardView castTile = findViewById(R.id.tile_cast_remote);
        MaterialCardView nestTile = findViewById(R.id.tile_nest_monitor);

        setupHubTile(TILE_HDMI, hdmiTile, () -> startActivity(new Intent(MainActivity.this, HdmiSettingsActivity.class)));
        setupHubTile(TILE_CAST, castTile, () -> startActivity(new Intent(MainActivity.this, CastRemoteActivity.class)));
        setupHubTile(TILE_NEST, nestTile, () -> startActivity(new Intent(MainActivity.this, NestMonitorActivity.class)));

        if (btnHubHiddenTiles != null) {
            btnHubHiddenTiles.setOnClickListener(v -> {
                showHiddenTiles = !showHiddenTiles;
                renderHubTiles();
            });
        }

        renderHubTiles();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void setupHubTile(String tileId, MaterialCardView card, Runnable onOpen) {
        if (card == null) return;
        tileViews.put(tileId, card);

        // Add subtle drag handle (top-left) and Hide/Show pill (top-right) inside the card
        TextView dragBadge = new TextView(this);
        dragBadge.setText("☰");
        dragBadge.setTextColor(Color.parseColor("#9CA3AF"));
        dragBadge.setTextSize(14f);
        dragBadge.setPadding(dp(8), dp(6), dp(8), dp(6));
        FrameLayout.LayoutParams dragLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        dragLp.setMargins(dp(4), dp(4), 0, 0);
        card.addView(dragBadge, dragLp);

        TextView hideBtn = new TextView(this);
        hideBtn.setTextSize(11f);
        hideBtn.setTypeface(Typeface.DEFAULT_BOLD);
        hideBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(Color.parseColor("#CC1E293B"));
        pillBg.setCornerRadius(dp(8));
        hideBtn.setBackground(pillBg);
        FrameLayout.LayoutParams hideLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        hideLp.setMargins(0, dp(8), dp(8), 0);
        hideBtn.setOnClickListener(v -> toggleHideTile(tileId));
        card.addView(hideBtn, hideLp);
        hideButtons.put(tileId, hideBtn);

        card.setOnClickListener(v -> onOpen.run());

        // Long-press tile or drag handle to start drag-and-drop reorder
        View.OnLongClickListener startDrag = v -> {
            ClipData data = ClipData.newPlainText("hub_tile_id", tileId);
            View.DragShadowBuilder shadow = new View.DragShadowBuilder(card);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                card.startDragAndDrop(data, shadow, tileId, 0);
            } else {
                @SuppressWarnings("deprecation")
                boolean started = card.startDrag(data, shadow, tileId, 0);
            }
            return true;
        };
        card.setOnLongClickListener(startDrag);
        dragBadge.setOnLongClickListener(startDrag);
        dragBadge.setOnClickListener(v -> Toast.makeText(this, "Hold tile and drag onto another tile to reorder", Toast.LENGTH_SHORT).show());

        // Drop target for reordering
        card.setOnDragListener((v, event) -> {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return true;
                case DragEvent.ACTION_DRAG_ENTERED:
                    card.setStrokeWidth(dp(2));
                    card.setStrokeColor(Color.parseColor("#00FFFF"));
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                case DragEvent.ACTION_DRAG_ENDED:
                    card.setStrokeWidth(0);
                    return true;
                case DragEvent.ACTION_DROP:
                    card.setStrokeWidth(0);
                    if (event.getLocalState() instanceof String) {
                        reorderTiles((String) event.getLocalState(), tileId);
                    }
                    return true;
                default:
                    return true;
            }
        });
    }

    private void loadTileOrder() {
        List<String> defaults = Arrays.asList(TILE_HDMI, TILE_CAST, TILE_NEST);
        tileOrder.clear();
        try {
            String raw = getSharedPreferences(HUB_PREFS, Context.MODE_PRIVATE).getString(KEY_HUB_ORDER, null);
            if (raw != null && !raw.isEmpty()) {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    String id = arr.optString(i, "");
                    if (defaults.contains(id) && !tileOrder.contains(id)) tileOrder.add(id);
                }
            }
        } catch (Exception ignored) {}
        for (String id : defaults) {
            if (!tileOrder.contains(id)) tileOrder.add(id);
        }
    }

    private void saveTileOrder() {
        JSONArray arr = new JSONArray();
        for (String id : tileOrder) arr.put(id);
        getSharedPreferences(HUB_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_HUB_ORDER, arr.toString())
                .apply();
    }

    private void reorderTiles(String draggedId, String targetId) {
        if (draggedId == null || targetId == null || draggedId.equals(targetId)) return;
        int from = tileOrder.indexOf(draggedId);
        int to = tileOrder.indexOf(targetId);
        if (from < 0 || to < 0) return;
        tileOrder.remove(from);
        tileOrder.add(to, draggedId);
        saveTileOrder();
        renderHubTiles();
    }

    private void toggleHideTile(String tileId) {
        if (hiddenTiles.contains(tileId)) {
            hiddenTiles.remove(tileId);
        } else {
            hiddenTiles.add(tileId);
        }
        getSharedPreferences(HUB_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_HUB_HIDDEN, new HashSet<>(hiddenTiles))
                .apply();
        renderHubTiles();
    }

    private void renderHubTiles() {
        if (hubGrid == null) return;
        hubGrid.removeAllViews();

        int hiddenCount = hiddenTiles.size();
        if (btnHubHiddenTiles != null) {
            if (showHiddenTiles) {
                btnHubHiddenTiles.setText("Showing Hidden Tiles (" + hiddenCount + ")");
                btnHubHiddenTiles.setTextColor(Color.parseColor("#00FFFF"));
            } else {
                btnHubHiddenTiles.setText("Hidden Tiles (" + hiddenCount + ")");
                btnHubHiddenTiles.setTextColor(hiddenCount > 0 ? Color.parseColor("#F59E0B") : Color.parseColor("#9CA3AF"));
            }
        }

        for (String id : tileOrder) {
            MaterialCardView card = tileViews.get(id);
            if (card == null) continue;
            boolean isHidden = hiddenTiles.contains(id);
            if (isHidden && !showHiddenTiles) continue;

            card.setAlpha(isHidden ? 0.50f : 1.0f);
            TextView hideBtn = hideButtons.get(id);
            if (hideBtn != null) {
                hideBtn.setText(isHidden ? "Show" : "Hide");
                hideBtn.setTextColor(isHidden ? Color.parseColor("#10B981") : Color.parseColor("#9CA3AF"));
            }

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(210);
            lp.height = dp(210);
            int m = dp(12);
            lp.setMargins(m, m, m, m);
            card.setLayoutParams(lp);
            hubGrid.addView(card);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateArmedStatus();
        updateCastSummary();
        updateNestSummary();
    }

    private void updateArmedStatus() {
        if (hdmiSubtitle == null) return;
        SharedPreferences prefs = getSharedPreferences("ProjectPrefs", Context.MODE_PRIVATE);
        boolean isConfigured = prefs.getBoolean("is_configured", false);
        String targetHdmi = prefs.getString("target_hdmi", "");
        String targetName = prefs.getString("target_hdmi_name", "");

        if (isConfigured && !targetHdmi.isEmpty() && !"OFF".equals(targetHdmi)) {
            String display = !targetName.isEmpty() ? targetName : targetHdmi;
            hdmiSubtitle.setText("ARMED: " + display);
            hdmiSubtitle.setTextColor(Color.parseColor("#00FFFF"));
        } else {
            hdmiSubtitle.setText("AUTOSWITCH: OFF");
            hdmiSubtitle.setTextColor(Color.parseColor("#8899A6"));
        }
    }

    private void updateCastSummary() {
        if (castSubtitle == null) return;
        SharedPreferences prefs = getSharedPreferences("CastUtilitiesPrefs", Context.MODE_PRIVATE);
        Set<String> pinned = prefs.getStringSet("pinned_device_ids", new HashSet<>());
        Set<String> paired = prefs.getStringSet("atv_paired_ids", new HashSet<>());
        int pinCount = pinned != null ? pinned.size() : 0;
        int pairCount = paired != null ? paired.size() : 0;
        if (pinCount > 0 || pairCount > 0) {
            castSubtitle.setText(pinCount + " Pinned · " + pairCount + " Paired TVs");
        } else {
            castSubtitle.setText("Pin, Mirror & TV Power");
        }
    }

    private void updateNestSummary() {
        if (nestSubtitle == null) return;
        SharedPreferences prefs = getSharedPreferences("NestMonitorPrefs", Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean("monitor_enabled", false);
        float delta = prefs.getFloat("delta_degrees_f", 2.0f);
        float minT = prefs.getFloat("min_temp_f", 62.0f);
        if (enabled) {
            nestSubtitle.setText(String.format(Locale.US, "ARMED · ±%.0f°F / ≤%.0f°F", delta, minT));
            nestSubtitle.setTextColor(Color.parseColor("#10B981"));
        } else {
            nestSubtitle.setText("Temp Alerts & Reset");
            nestSubtitle.setTextColor(Color.parseColor("#8899A6"));
        }
    }
}
