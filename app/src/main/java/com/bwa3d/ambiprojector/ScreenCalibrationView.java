package com.bwa3d.ambiprojector;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;

/** Interactive overlay used on top of the camera preview to define the TV quadrilateral. */
public final class ScreenCalibrationView extends View {
    public interface Listener {
        void onCornersChanged(float[] normalizedCorners);
    }

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private float[] corners = new float[]{0.15f,0.20f, 0.85f,0.20f, 0.85f,0.80f, 0.15f,0.80f};
    private int active = -1;
    private Listener listener;

    public ScreenCalibrationView(Context context) {
        super(context);
        setBackgroundColor(Color.TRANSPARENT);
        linePaint.setColor(0xFFFFFFFF);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(3));
        handlePaint.setColor(0xFFFFFFFF);
        handlePaint.setStyle(Paint.Style.FILL);
        shadePaint.setColor(0x66000000);
        shadePaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(dp(16));
        textPaint.setShadowLayer(dp(4), 0, dp(1), Color.BLACK);
    }

    public void setListener(Listener listener) { this.listener = listener; }

    public void setCorners(float[] c) {
        if (c == null || c.length != 8) return;
        corners = c.clone();
        invalidate();
    }

    public float[] getCorners() { return corners.clone(); }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        path.reset();
        path.moveTo(corners[0]*w, corners[1]*h);
        path.lineTo(corners[2]*w, corners[3]*h);
        path.lineTo(corners[4]*w, corners[5]*h);
        path.lineTo(corners[6]*w, corners[7]*h);
        path.close();

        canvas.save();
        canvas.clipOutPath(path);
        canvas.drawColor(0x55000000);
        canvas.restore();
        canvas.drawPath(path, linePaint);

        for (int i = 0; i < 4; i++) {
            float x = corners[i*2]*w, y = corners[i*2+1]*h;
            canvas.drawCircle(x, y, dp(14), handlePaint);
            linePaint.setColor(0xFF000000);
            canvas.drawCircle(x, y, dp(6), linePaint);
            linePaint.setColor(0xFFFFFFFF);
        }

        canvas.drawText("Drag the 4 corners to match the TV", dp(16), h - dp(22), textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX(), y = event.getY();
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return true;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                active = nearestCorner(x, y, w, h);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (active >= 0) {
                    corners[active*2] = clamp(x / w, 0.01f, 0.99f);
                    corners[active*2+1] = clamp(y / h, 0.01f, 0.99f);
                    enforceOrder();
                    invalidate();
                    if (listener != null) listener.onCornersChanged(corners.clone());
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                active = -1;
                return true;
            default:
                return true;
        }
    }

    private int nearestCorner(float x, float y, int w, int h) {
        int best = 0;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float dx = x - corners[i*2]*w;
            float dy = y - corners[i*2+1]*h;
            float d = dx*dx + dy*dy;
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    private void enforceOrder() {
        // Keep a sane clockwise quadrilateral without being overly restrictive.
        corners[0] = Math.min(corners[0], corners[2] - 0.03f);
        corners[6] = Math.min(corners[6], corners[4] - 0.03f);
        corners[1] = Math.min(corners[1], corners[7] - 0.03f);
        corners[3] = Math.min(corners[3], corners[5] - 0.03f);
    }

    private float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
