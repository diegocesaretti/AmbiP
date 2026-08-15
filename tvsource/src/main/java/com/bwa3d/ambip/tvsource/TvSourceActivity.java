package com.bwa3d.ambip.tvsource;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import java.util.Locale;

/** Lightweight Android TV launcher with explicit D-pad focus visuals. */
public final class TvSourceActivity extends ComponentActivity {
    public static final String EXTRA_BOOT_REQUEST = "ambipBootCaptureRequest";
    private static final int CAPTURE_REQUEST = 3101;
    private TextView status, web, stats, autoButton, startButton;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            boolean active = SourceHub.isActive();
            status.setText("Source: " + SourceHub.getStatus());
            status.setTextColor(active ? 0xff81c784 : 0xffffb74d);
            web.setText(active ? "TV diagnostics: " + SourceHub.getWebUrls() : "TV diagnostics: available after capture starts");
            stats.setText(String.format(Locale.US, "%.1f fps · %d×%d analysis · %d client(s) · TARGET %d FPS",
                    SourceHub.getFps(), SourceHub.getWidth(), SourceHub.getHeight(), SourceHub.getClients(), SourceHub.targetFps));
            if (autoButton != null) autoButton.setText(SourceHub.autoStartOnBoot ? "AUTO BOOT: ON" : "AUTO BOOT: OFF");
            handler.postDelayed(this, 1500L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        SourceHub.load(this);
        buildUi();
        if (getIntent().getBooleanExtra(EXTRA_BOOT_REQUEST,false) && SourceHub.autoStartOnBoot && !SourceHub.isActive()) {
            handler.postDelayed(this::requestCapture, 900L);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override protected void onStop() {
        handler.removeCallbacks(refresh);
        super.onStop();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xff080a0d);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(48), dp(32), dp(48), dp(34));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = text("AmbiP TV Source · v0.4", 30, Color.WHITE);
        root.addView(title);
        TextView desc = text("Lean TV capture source. The projector handles interpolation, Color Cloud, dynamics and the unified phone Control Center.", 17, 0xffb9c2ce);
        desc.setGravity(Gravity.CENTER);
        desc.setPadding(0, dp(10), 0, dp(24));
        root.addView(desc, new LinearLayout.LayoutParams(-1, -2));

        status = text("Source: idle", 20, 0xffffb74d); root.addView(status);
        web = text("TV diagnostics: —", 17, 0xff81d4fa); web.setTextIsSelectable(true); web.setPadding(0,dp(9),0,dp(7)); root.addView(web);
        stats = text("0 fps", 16, 0xffaab2bd); stats.setPadding(0,0,0,dp(22)); root.addView(stats);

        TextView portal = text("MAIN SETTINGS: open http://PROJECTOR-IP:8081 on your phone", 16, 0xffb2ffb7);
        portal.setGravity(Gravity.CENTER);
        portal.setPadding(dp(12), dp(10), dp(12), dp(16));
        root.addView(portal);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER);
        startButton = tvButton("START CAPTURE", v -> requestCapture());
        TextView stopButton = tvButton("STOP", v -> stopCapture());
        row1.addView(startButton);
        row1.addView(stopButton);
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER);
        TextView fpsDown = tvButton("FPS −", v -> adjustFps(-1));
        autoButton = tvButton(SourceHub.autoStartOnBoot ? "AUTO BOOT: ON" : "AUTO BOOT: OFF", v -> toggleAutoBoot());
        TextView fpsUp = tvButton("FPS +", v -> adjustFps(1));
        row2.addView(fpsDown);
        row2.addView(autoButton);
        row2.addView(fpsUp);
        root.addView(row2);

        TextView focusHelp = text("Use the D-pad. The selected control turns bright cyan with a white border.", 15, 0xff81d4fa);
        focusHelp.setGravity(Gravity.CENTER);
        focusHelp.setPadding(0,dp(16),0,dp(8));
        root.addView(focusHelp);

        TextView steps = text("START CAPTURE → approve Android screen sharing → HOME → open Stremio/player.\n\nFPS − / + gives quick TV-side latency tuning. For every other setting, use the single AmbiP Control Center on the projector at port 8081.", 16, 0xffc9d0da);
        steps.setPadding(0, dp(18), 0, 0);
        root.addView(steps, new LinearLayout.LayoutParams(-1, -2));

        setContentView(scroll);
        startButton.post(startButton::requestFocus);
    }

    private void adjustFps(int delta) {
        int next = Math.max(4, Math.min(30, SourceHub.targetFps + delta));
        SourceHub.applySettings(this, next, null, null, null);
        stats.setText(String.format(Locale.US, "%.1f fps · %d×%d analysis · %d client(s) · TARGET %d FPS",
                SourceHub.getFps(), SourceHub.getWidth(), SourceHub.getHeight(), SourceHub.getClients(), SourceHub.targetFps));
    }

    private void toggleAutoBoot() {
        SourceHub.applySettings(this,null,null,null,!SourceHub.autoStartOnBoot);
        if(autoButton!=null) autoButton.setText(SourceHub.autoStartOnBoot ? "AUTO BOOT: ON" : "AUTO BOOT: OFF");
    }

    private void requestCapture() {
        MediaProjectionManager m = (MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(m.createScreenCaptureIntent(), CAPTURE_REQUEST);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != CAPTURE_REQUEST) return;
        if (resultCode != Activity.RESULT_OK || data == null) {
            SourceHub.setActive(false, "Permission cancelled");
            return;
        }
        Intent service = new Intent(this, TvCaptureService.class);
        service.setAction(TvCaptureService.ACTION_START);
        service.putExtra(TvCaptureService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(TvCaptureService.EXTRA_RESULT_DATA, data);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
        SourceHub.setActive(false, "Starting…");
    }

    private void stopCapture() {
        Intent i = new Intent(this, TvCaptureService.class);
        i.setAction(TvCaptureService.ACTION_STOP);
        startService(i);
    }

    private TextView tvButton(String label, View.OnClickListener listener) {
        TextView b = text(label, 16, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setMinWidth(dp(180));
        b.setMinHeight(dp(64));
        b.setPadding(dp(20), dp(15), dp(20), dp(15));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2,-2);
        p.setMargins(dp(8),dp(8),dp(8),dp(8));
        b.setLayoutParams(p);
        b.setOnClickListener(listener);
        b.setOnFocusChangeListener((v, focused) -> applyFocusVisual((TextView)v, focused));
        applyFocusVisual(b, false);
        return b;
    }

    private void applyFocusVisual(TextView b, boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        if (focused) {
            bg.setColor(0xff29b6f6);
            bg.setStroke(dp(4), Color.WHITE);
            b.setTextColor(0xff06131a);
            b.setScaleX(1.07f);
            b.setScaleY(1.07f);
            b.setElevation(dp(10));
        } else {
            bg.setColor(0xff303842);
            bg.setStroke(dp(2), 0xff596574);
            b.setTextColor(Color.WHITE);
            b.setScaleX(1f);
            b.setScaleY(1f);
            b.setElevation(dp(2));
        }
        b.setBackground(bg);
    }

    private TextView text(String value, float size, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); return t;
    }
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}

    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
