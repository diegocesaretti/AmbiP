package com.bwa3d.ambip.tvsource;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Shared runtime state and ambip-light-v1 encoder. No image/frame bytes ever leave this process. */
public final class SourceHub {
    private SourceHub() {}

    public static final int H_SEGMENTS = 32;
    public static final int V_SEGMENTS = 18;
    public static final int WEB_PORT = 8080;
    private static final String PREFS = "ambip_tv_source";

    private static volatile boolean active;
    private static volatile String status = "Idle";
    private static volatile String webUrl = "http://0.0.0.0:" + WEB_PORT;
    private static volatile String latestJson = "{\"v\":1,\"active\":false}";
    private static volatile float fps;
    private static volatile int width;
    private static volatile int height;
    private static volatile int clients;
    private static final AtomicLong sequence = new AtomicLong();

    public static volatile int targetFps = 15;
    public static volatile float stripRatio = 0.10f;
    public static volatile float smoothing = 0.45f;
    public static volatile float brightness = 1.00f;
    public static volatile float saturation = 1.00f;

    public static void load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        targetFps = clamp(p.getInt("targetFps", 15), 5, 30);
        stripRatio = clamp(p.getFloat("stripRatio", 0.10f), 0.03f, 0.25f);
        smoothing = clamp(p.getFloat("smoothing", 0.45f), 0f, 0.95f);
        brightness = clamp(p.getFloat("brightness", 1f), 0.40f, 2f);
        saturation = clamp(p.getFloat("saturation", 1f), 0f, 2f);
    }

    public static void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("targetFps", targetFps)
                .putFloat("stripRatio", stripRatio)
                .putFloat("smoothing", smoothing)
                .putFloat("brightness", brightness)
                .putFloat("saturation", saturation)
                .apply();
    }

    public static void applySettings(Context context, Integer fpsValue, Float strip, Float smooth, Float bright, Float sat) {
        if (fpsValue != null) targetFps = clamp(fpsValue, 5, 30);
        if (strip != null) stripRatio = clamp(strip, 0.03f, 0.25f);
        if (smooth != null) smoothing = clamp(smooth, 0f, 0.95f);
        if (bright != null) brightness = clamp(bright, 0.40f, 2f);
        if (sat != null) saturation = clamp(sat, 0f, 2f);
        save(context);
    }

    public static void setActive(boolean value, String text) { active = value; status = text == null ? "" : text; }
    public static boolean isActive() { return active; }
    public static String getStatus() { return status; }
    public static String getWebUrl() { return webUrl; }
    public static void setWebUrl(String value) { if (value != null && !value.isEmpty()) webUrl = value; }
    public static String getLatestJson() { return latestJson; }
    public static float getFps() { return fps; }
    public static int getWidth() { return width; }
    public static int getHeight() { return height; }
    public static int getClients() { return clients; }
    public static void setClients(int value) { clients = Math.max(0, value); }

    /**
     * Protocol ambip-light-v1 sample = [R,G,B,L,S].
     * R/G/B: 0..255, L: perceptual luminance 0..255, S: HSV saturation 0..255.
     */
    public static String publish(int[] top, int[] right, int[] bottom, int[] left,
                                 float captureFps, int sourceWidth, int sourceHeight) {
        fps = captureFps;
        width = sourceWidth;
        height = sourceHeight;
        StringBuilder sb = new StringBuilder(9000);
        sb.append('{');
        sb.append("\"v\":1,\"protocol\":\"ambip-light-v1\",\"active\":true,");
        sb.append("\"seq\":").append(sequence.incrementAndGet()).append(',');
        sb.append("\"t\":").append(System.currentTimeMillis()).append(',');
        sb.append("\"fps\":").append(String.format(Locale.US, "%.1f", captureFps)).append(',');
        sb.append("\"w\":").append(sourceWidth).append(',');
        sb.append("\"h\":").append(sourceHeight).append(',');
        appendSamples(sb, "top", top); sb.append(',');
        appendSamples(sb, "right", right); sb.append(',');
        appendSamples(sb, "bottom", bottom); sb.append(',');
        appendSamples(sb, "left", left);
        sb.append('}');
        latestJson = sb.toString();
        return latestJson;
    }

    public static String settingsJson() {
        return String.format(Locale.US,
                "{\"active\":%s,\"status\":\"%s\",\"url\":\"%s\",\"clients\":%d,\"fps\":%d,\"strip\":%.3f,\"smoothing\":%.3f,\"brightness\":%.3f,\"saturation\":%.3f}",
                active ? "true" : "false", escape(status), escape(webUrl), clients,
                targetFps, stripRatio, smoothing, brightness, saturation);
    }

    private static void appendSamples(StringBuilder sb, String name, int[] colors) {
        sb.append('\"').append(name).append("\":[");
        for (int i = 0; i < colors.length; i++) {
            if (i > 0) sb.append(',');
            int c = colors[i];
            int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
            int l = clamp(Math.round((0.2126f * r + 0.7152f * g + 0.0722f * b)), 0, 255);
            float[] hsv = new float[3];
            Color.RGBToHSV(r, g, b, hsv);
            int s = clamp(Math.round(hsv[1] * 255f), 0, 255);
            sb.append('[').append(r).append(',').append(g).append(',').append(b).append(',').append(l).append(',').append(s).append(']');
        }
        sb.append(']');
    }

    public static int tuneColor(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = clamp(hsv[1] * saturation, 0f, 1f);
        hsv[2] = clamp(hsv[2] * brightness, 0f, 1f);
        return Color.HSVToColor(hsv);
    }

    private static String escape(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\""); }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
