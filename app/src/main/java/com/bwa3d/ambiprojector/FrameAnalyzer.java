package com.bwa3d.ambiprojector;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

/** CPU-light proof-of-concept analyzer. */
public final class FrameAnalyzer implements ImageAnalysis.Analyzer {
    public interface Listener { void onAmbilightFrame(AmbilightState state); }

    private final Listener listener;
    private final AtomicReference<Float> cropScale = new AtomicReference<>(0.80f);
    private volatile float smoothing = 0.72f;
    private volatile float brightness = 1.0f;

    private final float[][] smoothTop = new float[AmbilightState.H_SEGMENTS][3];
    private final float[][] smoothBottom = new float[AmbilightState.H_SEGMENTS][3];
    private final float[][] smoothLeft = new float[AmbilightState.V_SEGMENTS][3];
    private final float[][] smoothRight = new float[AmbilightState.V_SEGMENTS][3];
    private boolean smoothingPrimed = false;

    private long fpsWindowStart = System.nanoTime();
    private int fpsFrames = 0;
    private float fps = 0f;

    public FrameAnalyzer(Listener listener) { this.listener = listener; }
    public void setCropScale(float value) { cropScale.set(clamp(value, 0.45f, 0.98f)); }
    public float getCropScale() { return cropScale.get(); }
    public void setSmoothing(float value) { smoothing = clamp(value, 0f, 0.95f); }
    public float getSmoothing() { return smoothing; }
    public void setBrightness(float value) { brightness = clamp(value, 0.25f, 2.0f); }
    public float getBrightness() { return brightness; }

    @Override
    public void analyze(@NonNull ImageProxy image) {
        try {
            if (image.getPlanes().length < 1) return;
            final int width = image.getWidth();
            final int height = image.getHeight();
            final ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            final ByteBuffer buffer = plane.getBuffer();
            final int rowStride = plane.getRowStride();
            final int pixelStride = plane.getPixelStride();
            if (pixelStride < 4 || width <= 0 || height <= 0) return;

            RectI crop = centeredTvCrop(width, height, cropScale.get());
            int strip = Math.max(3, Math.min(crop.w, crop.h) / 28);

            int[] top = sampleHorizontal(buffer, rowStride, pixelStride, crop.x, crop.y,
                    crop.w, strip, AmbilightState.H_SEGMENTS);
            int[] bottom = sampleHorizontal(buffer, rowStride, pixelStride, crop.x,
                    crop.y + crop.h - strip, crop.w, strip, AmbilightState.H_SEGMENTS);
            int[] left = sampleVertical(buffer, rowStride, pixelStride, crop.x, crop.y,
                    strip, crop.h, AmbilightState.V_SEGMENTS);
            int[] right = sampleVertical(buffer, rowStride, pixelStride,
                    crop.x + crop.w - strip, crop.y, strip, crop.h, AmbilightState.V_SEGMENTS);

            updateFps();
            applySmoothing(top, smoothTop);
            applySmoothing(bottom, smoothBottom);
            applySmoothing(left, smoothLeft);
            applySmoothing(right, smoothRight);
            smoothingPrimed = true;

            listener.onAmbilightFrame(new AmbilightState(top, bottom, left, right,
                    fps, width, height, cropScale.get()));
        } finally {
            image.close();
        }
    }

    private int[] sampleHorizontal(ByteBuffer buffer, int rowStride, int pixelStride,
                                   int x, int y, int w, int h, int segments) {
        int[] out = new int[segments];
        for (int s = 0; s < segments; s++) {
            int x0 = x + (s * w) / segments;
            int x1 = x + ((s + 1) * w) / segments;
            out[s] = averageRegion(buffer, rowStride, pixelStride, x0, y,
                    Math.max(1, x1 - x0), h);
        }
        return out;
    }

    private int[] sampleVertical(ByteBuffer buffer, int rowStride, int pixelStride,
                                 int x, int y, int w, int h, int segments) {
        int[] out = new int[segments];
        for (int s = 0; s < segments; s++) {
            int y0 = y + (s * h) / segments;
            int y1 = y + ((s + 1) * h) / segments;
            out[s] = averageRegion(buffer, rowStride, pixelStride, x, y0,
                    w, Math.max(1, y1 - y0));
        }
        return out;
    }

    private int averageRegion(ByteBuffer buffer, int rowStride, int pixelStride,
                              int x, int y, int w, int h) {
        long r = 0, g = 0, b = 0;
        int count = 0;
        int stepX = Math.max(1, w / 8);
        int stepY = Math.max(1, h / 5);
        for (int yy = y; yy < y + h; yy += stepY) {
            for (int xx = x; xx < x + w; xx += stepX) {
                int p = yy * rowStride + xx * pixelStride;
                if (p < 0 || p + 3 >= buffer.limit()) continue;
                int rr = buffer.get(p + 1) & 0xFF;
                int gg = buffer.get(p + 2) & 0xFF;
                int bb = buffer.get(p + 3) & 0xFF;
                r += rr; g += gg; b += bb; count++;
            }
        }
        if (count == 0) return 0xFF000000;
        int rr = tone((int) (r / count));
        int gg = tone((int) (g / count));
        int bb = tone((int) (b / count));
        return 0xFF000000 | (rr << 16) | (gg << 8) | bb;
    }

    private int tone(int value) { return clampInt(Math.round(value * brightness), 0, 255); }

    private void applySmoothing(int[] colors, float[][] memory) {
        float oldW = smoothing;
        float newW = 1f - oldW;
        for (int i = 0; i < colors.length; i++) {
            float r = (colors[i] >> 16) & 0xFF;
            float g = (colors[i] >> 8) & 0xFF;
            float b = colors[i] & 0xFF;
            if (!smoothingPrimed) {
                memory[i][0] = r; memory[i][1] = g; memory[i][2] = b;
            } else {
                memory[i][0] = memory[i][0] * oldW + r * newW;
                memory[i][1] = memory[i][1] * oldW + g * newW;
                memory[i][2] = memory[i][2] * oldW + b * newW;
            }
            colors[i] = 0xFF000000
                    | (clampInt(Math.round(memory[i][0]), 0, 255) << 16)
                    | (clampInt(Math.round(memory[i][1]), 0, 255) << 8)
                    | clampInt(Math.round(memory[i][2]), 0, 255);
        }
    }

    private RectI centeredTvCrop(int frameW, int frameH, float scale) {
        int w = Math.round(frameW * scale);
        int h = Math.round(w * 9f / 16f);
        if (h > frameH * scale) {
            h = Math.round(frameH * scale);
            w = Math.round(h * 16f / 9f);
        }
        w = Math.min(w, frameW);
        h = Math.min(h, frameH);
        return new RectI((frameW - w) / 2, (frameH - h) / 2, w, h);
    }

    private void updateFps() {
        fpsFrames++;
        long now = System.nanoTime();
        long elapsed = now - fpsWindowStart;
        if (elapsed >= 700_000_000L) {
            fps = (float) (fpsFrames * 1_000_000_000.0 / elapsed);
            fpsFrames = 0;
            fpsWindowStart = now;
        }
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    private static int clampInt(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static final class RectI {
        final int x, y, w, h;
        RectI(int x, int y, int w, int h) { this.x = x; this.y = y; this.w = w; this.h = h; }
    }
}
