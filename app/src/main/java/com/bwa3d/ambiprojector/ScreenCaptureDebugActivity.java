package com.bwa3d.ambiprojector;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.core.content.ContextCompat;

import java.util.Locale;

/** Minimal launcher UI for the screen-capture-to-web prototype. */
public final class ScreenCaptureDebugActivity extends ComponentActivity {
    private static final int CAPTURE_REQUEST = 2201;

    private TextView status;
    private TextView url;
    private TextView stats;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateStatus();
            handler.postDelayed(this, 600L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        handler.post(refresh);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xff090b0e);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(30));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = text("AmbiP · Screen Debug", 24, Color.WHITE);
        root.addView(title);
        TextView description = text("Captura la pantalla Android con MediaProjection, calcula los colores de los bordes y los publica por Wi‑Fi en una página web local.", 14, 0xffaeb7c5);
        description.setPadding(0, dp(8), 0, dp(20));
        root.addView(description);

        status = text("Estado: detenido", 16, 0xffffb74d);
        status.setPadding(0, 0, 0, dp(8));
        root.addView(status);
        url = text("Web: —", 16, 0xff81d4fa);
        url.setTextIsSelectable(true);
        url.setPadding(0, 0, 0, dp(8));
        root.addView(url);
        stats = text("0.0 fps", 14, 0xffcfd8dc);
        stats.setPadding(0, 0, 0, dp(18));
        root.addView(stats);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        buttons.addView(button("INICIAR CAPTURA", v -> requestCapture()));
        buttons.addView(button("DETENER", v -> stopCapture()));
        root.addView(buttons);

        TextView steps = text("1. Tocá INICIAR CAPTURA.\n2. Elegí compartir toda la pantalla (o Stremio si Android ofrece compartir una sola app).\n3. Volvé a Stremio y reproducí una película.\n4. Desde una PC/celular en la misma red abrí la URL mostrada arriba.\n\nSi la película aparece en el preview web, podemos usar captura digital para el Ambilight. Si la UI aparece pero el video queda negro, ese contenido está protegido.", 14, 0xffc4cad4);
        steps.setPadding(0, dp(22), 0, 0);
        root.addView(steps);

        setContentView(scroll);
    }

    private void requestCapture() {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), CAPTURE_REQUEST);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != CAPTURE_REQUEST) return;
        if (resultCode != Activity.RESULT_OK || data == null) {
            ScreenCaptureHub.setActive(false, "Permission cancelled");
            updateStatus();
            return;
        }
        Intent service = new Intent(this, ScreenCaptureService.class);
        service.setAction(ScreenCaptureService.ACTION_START);
        service.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);
        ContextCompat.startForegroundService(this, service);
        status.setText("Estado: iniciando…");
        status.setTextColor(0xffffb74d);
    }

    private void stopCapture() {
        Intent service = new Intent(this, ScreenCaptureService.class);
        service.setAction(ScreenCaptureService.ACTION_STOP);
        startService(service);
        handler.postDelayed(this::updateStatus, 250L);
    }

    private void updateStatus() {
        boolean active = ScreenCaptureHub.isActive();
        status.setText("Estado: " + ScreenCaptureHub.getStatus());
        status.setTextColor(active ? 0xff81c784 : 0xffffb74d);
        url.setText(active ? "Web: " + ScreenCaptureHub.getWebUrl() : "Web: —");
        stats.setText(String.format(Locale.US, "%.1f fps · %d×%d · WebSocket + preview JPEG",
                ScreenCaptureHub.getFps(), ScreenCaptureHub.getWidth(), ScreenCaptureHub.getHeight()));
    }

    private TextView button(String label, View.OnClickListener listener) {
        TextView b = text(label, 14, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(15), dp(12), dp(15), dp(12));
        b.setBackgroundColor(0xff303842);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.setMargins(dp(5), dp(5), dp(5), dp(5));
        b.setLayoutParams(p);
        b.setOnClickListener(listener);
        return b;
    }

    private TextView text(String value, float size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}