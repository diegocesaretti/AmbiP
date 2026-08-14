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

/** Projection output layer: ambient halo, TV mask and four contextual text zones. */
public final class AmbilightView extends View {
    public interface GestureListener {
        void onSingleTap();
        void onLongPress();
        void onDoubleTap();
    }

    public enum Zone { TOP, BOTTOM, LEFT, RIGHT }

    private static final class Overlay {
        String text;
        long until;
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GestureDetector gestures;
    private volatile AmbilightState state = AmbilightState.black();
    private boolean debug = true;
    private GestureListener gestureListener;
    private final Overlay topOverlay = new Overlay();
    private final Overlay bottomOverlay = new Overlay();
    private final Overlay leftOverlay = new Overlay();
    private final Overlay rightOverlay = new Overlay();
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
        showContextOverlay(Zone.TOP, text, durationMs);
    }

    public void showContextOverlay(Zone zone, String text, long durationMs) {
        Overlay o = overlay(zone);
        o.text = text;
        o.until = SystemClock.uptimeMillis() + durationMs;
        invalidate();
    }

    public void showContextDemo(long durationMs) {
        showContextOverlay(Zone.TOP, "TOP · title / score / status", durationMs);
        showContextOverlay(Zone.BOTTOM, "BOTTOM · subtitles / extra info", durationMs);
        showContextOverlay(Zone.LEFT, "LEFT\ncontext\nzone", durationMs);
        showContextOverlay(Zone.RIGHT, "RIGHT\nalerts\nzone", durationMs);
    }

    private Overlay overlay(Zone zone) {
        switch (zone) {
            case BOTTOM: return bottomOverlay;
            case LEFT: return leftOverlay;
            case RIGHT: return rightOverlay;
            default: return topOverlay;
        }
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

        drawContextZones(canvas, tv);
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

    private void drawContextZones(Canvas canvas, RectF tv) {
        long now = SystemClock.uptimeMillis();
        textPaint.setColor(Color.WHITE);
        textPaint.setShadowLayer(10f, 0f, 2f, Color.BLACK);
        textPaint.setTextSize(Math.max(20f, getHeight() * 0.032f));

        drawHorizontalOverlay(canvas, topOverlay, now, tv.centerX(), Math.max(textPaint.getTextSize()+12f, tv.top*0.55f), Paint.Align.CENTER);
        drawHorizontalOverlay(canvas, bottomOverlay, now, tv.centerX(), tv.bottom + (getHeight()-tv.bottom)*0.55f, Paint.Align.CENTER);
        drawVerticalOverlay(canvas, leftOverlay, now, Math.max(14f, tv.left*0.18f), tv.centerY(), Paint.Align.LEFT);
        drawVerticalOverlay(canvas, rightOverlay, now, getWidth()-Math.max(14f, (getWidth()-tv.right)*0.18f), tv.centerY(), Paint.Align.RIGHT);

        textPaint.clearShadowLayer();
        if (isAnyOverlayAlive(now)) postInvalidateDelayed(200);
    }

    private void drawHorizontalOverlay(Canvas c, Overlay o, long now, float x, float y, Paint.Align align) {
        if (!alive(o, now)) return;
        textPaint.setTextAlign(align);
        c.drawText(o.text == null ? "" : o.text.replace("\n", " "), x, y, textPaint);
    }

    private void drawVerticalOverlay(Canvas c, Overlay o, long now, float x, float centerY, Paint.Align align) {
        if (!alive(o, now)) return;
        textPaint.setTextAlign(align);
        String[] lines = (o.text == null ? "" : o.text).split("\\n");
        float line = textPaint.getTextSize()*1.25f;
        float y = centerY - (lines.length-1)*line*0.5f;
        for (String s : lines) { c.drawText(s, x, y, textPaint); y += line; }
    }

    private boolean alive(Overlay o, long now) {
        if (o.text == null || now > o.until) { o.text = null; return false; }
        return true;
    }

    private boolean isAnyOverlayAlive(long now) {
        return alive(topOverlay, now) || alive(bottomOverlay, now) || alive(leftOverlay, now) || alive(rightOverlay, now);
    }

    private void drawDebug(Canvas canvas, RectF tv) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setColor(0x88FFFFFF);
        canvas.drawRect(tv, paint);
        paint.setStyle(Paint.Style.FILL);

        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(Math.max(15f, getHeight() * 0.022f));
        textPaint.setColor(Color.WHITE);
        float x = 18f;
        float y = 28f;
        float line = textPaint.getTextSize() * 1.35f;
        canvas.drawText("Ambi Projector v0.2", x, y, textPaint); y += line;
        canvas.drawText(String.format("Camera: %dx%d   %.1f fps", state.sourceWidth, state.sourceHeight, state.fps), x, y, textPaint); y += line;
        canvas.drawText("Tap: preview  |  Long: calibrate  |  Double: 4 text zones", x, y, textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return gestures.onTouchEvent(event) || super.onTouchEvent(event);
    }
}
