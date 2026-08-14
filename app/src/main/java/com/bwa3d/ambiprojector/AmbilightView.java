package com.bwa3d.ambiprojector;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;

/**
 * Projection output layer.
 * Today: ambient halo + black TV mask + debug HUD.
 * Later: projection mapping, notifications and contextual text are separate overlays here.
 */
public final class AmbilightView extends View {
    public interface GestureListener {
        void onSingleTap();
        void onLongPress();
        void onDoubleTap();
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GestureDetector gestures;
    private volatile AmbilightState state = AmbilightState.black();
    private boolean debug = true;
    private GestureListener gestureListener;

    private String overlayText = null;
    private long overlayUntil = 0L;

    private float maskWidthRatio = 0.60f;

    public AmbilightView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        textPaint.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL));
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(@NonNull MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                if (gestureListener != null) gestureListener.onSingleTap();
                return true;
            }
            @Override public void onLongPress(@NonNull MotionEvent e) {
                if (gestureListener != null) gestureListener.onLongPress();
            }
            @Override public boolean onDoubleTap(@NonNull MotionEvent e) {
                if (gestureListener != null) gestureListener.onDoubleTap();
                return true;
            }
        });
    }

    public void setGestureListener(GestureListener listener) { gestureListener = listener; }
    public void setState(AmbilightState newState) { state = newState; postInvalidateOnAnimation(); }
    public void setDebug(boolean enabled) { debug = enabled; invalidate(); }
    public boolean isDebug() { return debug; }

    public void showContextOverlay(String text, long durationMs) {
        overlayText = text;
        overlayUntil = SystemClock.uptimeMillis() + durationMs;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) return;

        canvas.drawColor(Color.BLACK);
        RectF tv = tvMask(w, h);
        drawTop(canvas, tv, state.top);
        drawBottom(canvas, tv, state.bottom);
        drawLeft(canvas, tv, state.left);
        drawRight(canvas, tv, state.right);

        paint.setShader(null);
        paint.setColor(Color.BLACK);
        canvas.drawRect(tv, paint);

        drawContextOverlay(canvas, tv);
        if (debug) drawDebug(canvas, tv);
    }

    private RectF tvMask(int w, int h) {
        float mw = w * maskWidthRatio;
        float mh = mw * 9f / 16f;
        if (mh > h * 0.66f) {
            mh = h * 0.66f;
            mw = mh * 16f / 9f;
        }
        float l = (w - mw) * 0.5f;
        float t = (h - mh) * 0.5f;
        return new RectF(l, t, l + mw, t + mh);
    }

    private void drawTop(Canvas c, RectF tv, int[] colors) {
        float sw = tv.width() / colors.length;
        for (int i = 0; i < colors.length; i++) {
            float l = tv.left + i * sw;
            float r = l + sw + 1;
            paint.setShader(new LinearGradient(0, 0, 0, tv.top,
                    Color.BLACK, colors[i], Shader.TileMode.CLAMP));
            c.drawRect(l, 0, r, tv.top + 1, paint);
        }
    }

    private void drawBottom(Canvas c, RectF tv, int[] colors) {
        float sw = tv.width() / colors.length;
        for (int i = 0; i < colors.length; i++) {
            float l = tv.left + i * sw;
            float r = l + sw + 1;
            paint.setShader(new LinearGradient(0, tv.bottom, 0, getHeight(),
                    colors[i], Color.BLACK, Shader.TileMode.CLAMP));
            c.drawRect(l, tv.bottom - 1, r, getHeight(), paint);
        }
    }

    private void drawLeft(Canvas c, RectF tv, int[] colors) {
        float sh = tv.height() / colors.length;
        for (int i = 0; i < colors.length; i++) {
            float t = tv.top + i * sh;
            float b = t + sh + 1;
            paint.setShader(new LinearGradient(0, 0, tv.left, 0,
                    Color.BLACK, colors[i], Shader.TileMode.CLAMP));
            c.drawRect(0, t, tv.left + 1, b, paint);
        }
    }

    private void drawRight(Canvas c, RectF tv, int[] colors) {
        float sh = tv.height() / colors.length;
        for (int i = 0; i < colors.length; i++) {
            float t = tv.top + i * sh;
            float b = t + sh + 1;
            paint.setShader(new LinearGradient(tv.right, 0, getWidth(), 0,
                    colors[i], Color.BLACK, Shader.TileMode.CLAMP));
            c.drawRect(tv.right - 1, t, getWidth(), b, paint);
        }
    }

    private void drawContextOverlay(Canvas canvas, RectF tv) {
        if (overlayText == null || SystemClock.uptimeMillis() > overlayUntil) {
            overlayText = null;
            return;
        }
        textPaint.setTextSize(Math.max(22f, getHeight() * 0.038f));
        textPaint.setColor(Color.WHITE);
        textPaint.setShadowLayer(10f, 0f, 2f, Color.BLACK);
        float x = tv.left;
        float y = Math.max(textPaint.getTextSize() + 12f, tv.top - 24f);
        canvas.drawText(overlayText, x, y, textPaint);
        textPaint.clearShadowLayer();
        postInvalidateDelayed(250);
    }

    private void drawDebug(Canvas canvas, RectF tv) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setColor(0x88FFFFFF);
        canvas.drawRect(tv, paint);
        paint.setStyle(Paint.Style.FILL);

        textPaint.setTextSize(Math.max(15f, getHeight() * 0.022f));
        textPaint.setColor(Color.WHITE);
        float x = 18f;
        float y = 28f;
        float line = textPaint.getTextSize() * 1.35f;
        canvas.drawText("Ambi Projector v0.1", x, y, textPaint); y += line;
        canvas.drawText(String.format("Camera: %dx%d   %.1f fps", state.sourceWidth, state.sourceHeight, state.fps), x, y, textPaint); y += line;
        canvas.drawText(String.format("TV crop: %.0f%%", state.cropScale * 100f), x, y, textPaint); y += line;
        canvas.drawText("Tap: camera preview  |  Long: crop  |  Double: text overlay", x, y, textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return gestures.onTouchEvent(event) || super.onTouchEvent(event);
    }
}
