package com.bwa3d.ambip.tvsource;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Shared runtime state and compact ambip-light-v2 encoder. */
public final class SourceHub {
    private SourceHub() {}

    public static final int H_SEGMENTS = 32;
    public static final int V_SEGMENTS = 18;
    public static final int WEB_PORT = 8080;
    private static final String PREFS = "ambip_tv_source";
    private static final int CONFIG_VERSION = 2;

    private static volatile boolean active;
    private static volatile String status = "Idle";
    private static volatile String webUrl = "http://127.0.0.1:" + WEB_PORT;
    private static volatile String webUrls = webUrl;
    private static volatile String latestJson = "{\"v\":2,\"protocol\":\"ambip-light-v2\",\"active\":false}";
    private static volatile float fps;
    private static volatile int width;
    private static volatile int height;
    private static volatile int clients;
    private static final AtomicLong sequence = new AtomicLong();

    public static volatile int targetFps = 8;
    public static volatile float stripRatio = 0.08f;
    public static volatile int samplesPerZone = 2;

    public static void load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.getInt("configVersion", 0) < CONFIG_VERSION) {
            targetFps = 8;
            stripRatio = 0.08f;
            samplesPerZone = 2;
            p.edit().putInt("configVersion", CONFIG_VERSION)
                    .putInt("targetFps", targetFps)
                    .putFloat("stripRatio", stripRatio)
                    .putInt("samplesPerZone", samplesPerZone)
                    .remove("smoothing").remove("brightness").remove("saturation").apply();
            return;
        }
        targetFps = clamp(p.getInt("targetFps", 8), 4, 20);
        stripRatio = clamp(p.getFloat("stripRatio", 0.08f), 0.03f, 0.20f);
        samplesPerZone = clamp(p.getInt("samplesPerZone", 2), 1, 6);
    }

    public static void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("configVersion", CONFIG_VERSION)
                .putInt("targetFps", targetFps)
                .putFloat("stripRatio", stripRatio)
                .putInt("samplesPerZone", samplesPerZone)
                .apply();
    }

    public static void applySettings(Context context, Integer fpsValue, Float strip, Integer samples) {
        if (fpsValue != null) targetFps = clamp(fpsValue, 4, 20);
        if (strip != null) stripRatio = clamp(strip, 0.03f, 0.20f);
        if (samples != null) samplesPerZone = clamp(samples, 1, 6);
        save(context);
    }

    public static void setActive(boolean value, String text) {
        active = value;
        status = text == null ? "" : text;
        if (!value) latestJson = "{\"v\":2,\"protocol\":\"ambip-light-v2\",\"active\":false}";
    }
    public static boolean isActive() { return active; }
    public static String getStatus() { return status; }
    public static String getWebUrl() { return webUrl; }
    public static String getWebUrls() { return webUrls; }
    public static String getLatestJson() { return latestJson; }
    public static float getFps() { return fps; }
    public static int getWidth() { return width; }
    public static int getHeight() { return height; }
    public static int getClients() { return clients; }
    public static void setClients(int value) { clients = Math.max(0, value); }

    public static void setWebAddresses(String primary, String all) {
        if (primary != null && !primary.isEmpty()) webUrl = primary;
        if (all != null && !all.isEmpty()) webUrls = all;
    }

    public static String publish(int[] top, int[] right, int[] bottom, int[] left,
                                 float captureFps, int sourceWidth, int sourceHeight) {
        fps = captureFps;
        width = sourceWidth;
        height = sourceHeight;
        StringBuilder sb = new StringBuilder(1800);
        sb.append('{');
        sb.append("\"v\":2,\"protocol\":\"ambip-light-v2\",\"active\":true,");
        sb.append("\"seq\":").append(sequence.incrementAndGet()).append(',');
        sb.append("\"t\":").append(System.currentTimeMillis()).append(',');
        sb.append("\"fps\":").append(String.format(Locale.US, "%.1f", captureFps)).append(',');
        sb.append("\"w\":").append(sourceWidth).append(',');
        sb.append("\"h\":").append(sourceHeight).append(',');
        appendPacked(sb, "top", top); sb.append(',');
        appendPacked(sb, "right", right); sb.append(',');
        appendPacked(sb, "bottom", bottom); sb.append(',');
        appendPacked(sb, "left", left);
        sb.append('}');
        latestJson = sb.toString();
        return latestJson;
    }

    public static String settingsJson() {
        return String.format(Locale.US,
                "{\"active\":%s,\"status\":\"%s\",\"url\":\"%s\",\"urls\":\"%s\",\"clients\":%d,\"fps\":%d,\"strip\":%.3f,\"samples\":%d,\"analysisW\":%d,\"analysisH\":%d}",
                active ? "true" : "false", escape(status), escape(webUrl), escape(webUrls), clients,
                targetFps, stripRatio, samplesPerZone, width, height);
    }

    private static void appendPacked(StringBuilder sb, String name, int[] colors) {
        sb.append('\"').append(name).append("\":[");
        for (int i = 0; i < colors.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(colors[i] & 0x00ffffff);
        }
        sb.append(']');
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
