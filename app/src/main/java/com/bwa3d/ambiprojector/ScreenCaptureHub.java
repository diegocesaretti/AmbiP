package com.bwa3d.ambiprojector;

import java.util.Locale;

/** Thread-safe-ish shared state between the capture service, web server and debug UI. */
public final class ScreenCaptureHub {
    private ScreenCaptureHub() {}

    private static volatile boolean active = false;
    private static volatile String status = "Idle";
    private static volatile String webUrl = "http://0.0.0.0:8080";
    private static volatile String latestJson = "{\"active\":false}";
    private static volatile byte[] previewJpeg;
    private static volatile float fps = 0f;
    private static volatile int width = 0;
    private static volatile int height = 0;

    public static void setActive(boolean value, String newStatus) {
        active = value;
        status = newStatus == null ? "" : newStatus;
    }

    public static boolean isActive() { return active; }
    public static String getStatus() { return status; }
    public static String getWebUrl() { return webUrl; }
    public static String getLatestJson() { return latestJson; }
    public static byte[] getPreviewJpeg() { return previewJpeg; }
    public static float getFps() { return fps; }
    public static int getWidth() { return width; }
    public static int getHeight() { return height; }

    public static void setWebUrl(String value) {
        if (value != null && !value.isEmpty()) webUrl = value;
    }

    public static void publish(int[] top, int[] bottom, int[] left, int[] right,
                               float captureFps, int sourceWidth, int sourceHeight,
                               byte[] jpegOrNull) {
        fps = captureFps;
        width = sourceWidth;
        height = sourceHeight;
        if (jpegOrNull != null) previewJpeg = jpegOrNull;

        StringBuilder sb = new StringBuilder(4096);
        sb.append('{');
        sb.append("\"active\":true,");
        sb.append("\"fps\":").append(String.format(Locale.US, "%.1f", captureFps)).append(',');
        sb.append("\"width\":").append(sourceWidth).append(',');
        sb.append("\"height\":").append(sourceHeight).append(',');
        sb.append("\"timestamp\":").append(System.currentTimeMillis()).append(',');
        appendColors(sb, "top", top); sb.append(',');
        appendColors(sb, "right", right); sb.append(',');
        appendColors(sb, "bottom", bottom); sb.append(',');
        appendColors(sb, "left", left);
        sb.append('}');
        latestJson = sb.toString();
    }

    private static void appendColors(StringBuilder sb, String name, int[] colors) {
        sb.append('\"').append(name).append("\":[");
        for (int i = 0; i < colors.length; i++) {
            if (i > 0) sb.append(',');
            int c = colors[i];
            sb.append('\"').append(String.format(Locale.US, "#%02X%02X%02X",
                    (c >> 16) & 0xff, (c >> 8) & 0xff, c & 0xff)).append('\"');
        }
        sb.append(']');
    }
}