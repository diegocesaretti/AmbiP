package com.bwa3d.ambip.tvsource;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
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

/** Lightweight TV launcher. Once capture starts, this activity goes completely idle in background. */
public final class TvSourceActivity extends ComponentActivity {
    private static final int CAPTURE_REQUEST = 3101;
    private TextView status, web, stats;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            boolean active = SourceHub.isActive();
            status.setText("Source: " + SourceHub.getStatus());
            status.setTextColor(active ? 0xff81c784 : 0xffffb74d);
            web.setText(active ? "Phone settings: " + SourceHub.getWebUrls() : "Phone settings: available after capture starts");
            stats.setText(String.format(Locale.US, "%.1f fps · %d×%d analysis · %d client(s)",
                    SourceHub.getFps(), SourceHub.getWidth(), SourceHub.getHeight(), SourceHub.getClients()));
            handler.postDelayed(this, 1500L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        SourceHub.load(this);
        buildUi();
    }

    @Override protected void onStart() {
        super.onStart();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override protected void onStop() {
        // Critical on low-end TVs: do not keep polling/updating a hidden Activity behind Stremio.
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
        root.setPadding(dp(48), dp(36), dp(48), dp(36));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = text("AmbiP TV Source · ECO", 30, Color.WHITE);
        root.addView(title);
        TextView desc = text("The TV now does only a tiny 160px capture and sparse RGB edge sampling. Smoothing, color shaping and light-energy processing run on the projector.", 17, 0xffb9c2ce);
        desc.setGravity(Gravity.CENTER);
        desc.setPadding(0, dp(10), 0, dp(28));
        root.addView(desc, new LinearLayout.LayoutParams(-1, -2));

        status = text("Source: idle", 20, 0xffffb74d); root.addView(status);
        web = text("Phone settings: —", 18, 0xff81d4fa); web.setTextIsSelectable(true); web.setPadding(0,dp(10),0,dp(8)); root.addView(web);
        stats = text("0 fps", 15, 0xffaab2bd); stats.setPadding(0,0,0,dp(28)); root.addView(stats);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        buttons.addView(button("START CAPTURE", v -> requestCapture()));
        buttons.addView(button("STOP", v -> stopCapture()));
        root.addView(buttons);

        TextView steps = text("1. Start capture and approve Android screen sharing.\n2. Press HOME and open Stremio / your player.\n3. Try each LAN URL shown above from your phone; Wi-Fi and Ethernet addresses are both reported when present.\n4. Use the web page to lower FPS/sampling further if this TV still feels heavy.\n\nRecommended for older Oreo TVs: 8–10 fps and 2–3 samples per zone.", 16, 0xffc9d0da);
        steps.setPadding(0, dp(30), 0, 0);
        root.addView(steps, new LinearLayout.LayoutParams(-1, -2));

        setContentView(scroll);
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

    private TextView button(String label, View.OnClickListener listener) {
        TextView b = text(label, 17, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setPadding(dp(24), dp(17), dp(24), dp(17));
        b.setBackgroundColor(0xff303842);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2,-2);
        p.setMargins(dp(8),dp(8),dp(8),dp(8));
        b.setLayoutParams(p);
        b.setOnClickListener(listener);
        return b;
    }

    private TextView text(String value, float size, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); return t;
    }
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}

    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
