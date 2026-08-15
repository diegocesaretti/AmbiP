package com.bwa3d.ambip.tvsource;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Shared runtime state, compact ambip-light-v3 encoder and on-demand capture API state. */
public final class SourceHub {
    private SourceHub() {}

    public static final int H_SEGMENTS = 32;
    public static final int V_SEGMENTS = 18;
    public static final int WEB_PORT = 8080;
    private static final String PREFS = "ambip_tv_source";
    private static final int CONFIG_VERSION = 5;
    private static final int BINARY_HEADER = 18;

    private static volatile boolean active;
    private static volatile String status = "Idle";
    private static volatile String webUrl = "http://127.0.0.1:" + WEB_PORT;
    private static volatile String webUrls = webUrl;
    private static volatile float fps;
    private static volatile int width;
    private static volatile int height;
    private static volatile int clients;
    private static volatile byte[] latestBinary;
    private static final AtomicLong sequence = new AtomicLong();

    // Latest processed light capture. Reused arrays avoid allocations in the TV hot path.
    private static final int[] latestTop = new int[H_SEGMENTS];
    private static final int[] latestRight = new int[V_SEGMENTS];
    private static final int[] latestBottom = new int[H_SEGMENTS];
    private static final int[] latestLeft = new int[V_SEGMENTS];
    private static volatile long latestCaptureSequence;
    private static volatile long latestCaptureAtMs;

    // Full low-resolution snapshot is produced only when explicitly requested through the API.
    private static final Object SNAPSHOT_LOCK = new Object();
    private static volatile boolean snapshotRequested;
    private static volatile long snapshotVersion;
    private static volatile byte[] latestSnapshotJpeg;

    public static volatile int targetFps = 8;
    public static volatile float stripRatio = 0.08f;
    public static volatile int samplesPerZone = 2;
    public static volatile boolean autoStartOnBoot = false;
    /** Optional HTTP endpoint receiving compact processed capture JSON. Empty means disabled. */
    public static volatile String pushUrl = "";
    public static volatile int pushFps = 2;

    public static void load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int version = p.getInt("configVersion", 0);
        if (version < 2) {
            targetFps = 8;
            stripRatio = 0.08f;
            samplesPerZone = 2;
            autoStartOnBoot = false;
            pushUrl = "";
            pushFps = 2;
            p.edit().putInt("configVersion", CONFIG_VERSION)
                    .putInt("targetFps", targetFps)
                    .putFloat("stripRatio", stripRatio)
                    .putInt("samplesPerZone", samplesPerZone)
                    .putBoolean("autoStartOnBoot", false)
                    .putString("pushUrl", "")
                    .putInt("pushFps", 2)
                    .remove("smoothing").remove("brightness").remove("saturation").apply();
            return;
        }
        targetFps = clamp(p.getInt("targetFps", 8), 4, 30);
        stripRatio = clamp(p.getFloat("stripRatio", 0.08f), 0.03f, 0.20f);
        samplesPerZone = clamp(p.getInt("samplesPerZone", 2), 1, 6);
        autoStartOnBoot = p.getBoolean("autoStartOnBoot", false);
        pushUrl = safe(p.getString("pushUrl", "")).trim();
        pushFps = clamp(p.getInt("pushFps", 2), 1, 10);
        if (version < CONFIG_VERSION) p.edit().putInt("configVersion", CONFIG_VERSION).apply();
    }

    public static void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("configVersion", CONFIG_VERSION)
                .putInt("targetFps", targetFps)
                .putFloat("stripRatio", stripRatio)
                .putInt("samplesPerZone", samplesPerZone)
                .putBoolean("autoStartOnBoot", autoStartOnBoot)
                .putString("pushUrl", pushUrl)
                .putInt("pushFps", pushFps)
                .apply();
    }

    public static void applySettings(Context context, Integer fpsValue, Float strip, Integer samples,
                                     Boolean autostart, String newPushUrl, Integer newPushFps) {
        if (fpsValue != null) targetFps = clamp(fpsValue, 4, 30);
        if (strip != null) stripRatio = clamp(strip, 0.03f, 0.20f);
        if (samples != null) samplesPerZone = clamp(samples, 1, 6);
        if (autostart != null) autoStartOnBoot = autostart;
        if (newPushUrl != null) pushUrl = newPushUrl.trim();
        if (newPushFps != null) pushFps = clamp(newPushFps, 1, 10);
        save(context);
    }

    public static void setActive(boolean value, String text) {
        active = value;
        status = text == null ? "" : text;
        if (!value) {
            latestBinary = null;
            snapshotRequested = false;
        }
    }
    public static boolean isActive() { return active; }
    public static String getStatus() { return status; }
    public static String getWebUrl() { return webUrl; }
    public static String getWebUrls() { return webUrls; }
    public static float getFps() { return fps; }
    public static int getWidth() { return width; }
    public static int getHeight() { return height; }
    public static int getClients() { return clients; }
    public static void setClients(int value) { clients = Math.max(0, value); }
    public static byte[] getLatestBinary() { return latestBinary; }

    public static void setWebAddresses(String primary, String all) {
        if (primary != null && !primary.isEmpty()) webUrl = primary;
        if (all != null && !all.isEmpty()) webUrls = all;
    }

    /**
     * Builds a ~318-byte packet instead of JSON. Layout:
     * ABP3, version, flags, seq, fps*100, width, height, H count, V count, then RGB bytes in
     * top/right/bottom/left order.
     */
    public static byte[] publishBinary(int[] top, int[] right, int[] bottom, int[] left,
                                       float captureFps, int sourceWidth, int sourceHeight) {
        fps = captureFps;
        width = sourceWidth;
        height = sourceHeight;
        long seq = sequence.incrementAndGet();
        latestCaptureSequence = seq;
        latestCaptureAtMs = System.currentTimeMillis();
        copyColors(top, latestTop, H_SEGMENTS);
        copyColors(right, latestRight, V_SEGMENTS);
        copyColors(bottom, latestBottom, H_SEGMENTS);
        copyColors(left, latestLeft, V_SEGMENTS);

        int bytes = BINARY_HEADER + (H_SEGMENTS + V_SEGMENTS + H_SEGMENTS + V_SEGMENTS) * 3;
        ByteBuffer b = ByteBuffer.allocate(bytes).order(ByteOrder.BIG_ENDIAN);
        b.put((byte)'A').put((byte)'B').put((byte)'P').put((byte)'3');
        b.put((byte)3).put((byte)0);
        b.putInt((int)seq);
        b.putShort((short)clamp(Math.round(captureFps * 100f),0,65535));
        b.putShort((short)clamp(sourceWidth,0,65535));
        b.putShort((short)clamp(sourceHeight,0,65535));
        b.put((byte)H_SEGMENTS).put((byte)V_SEGMENTS);
        putColors(b,top,H_SEGMENTS);
        putColors(b,right,V_SEGMENTS);
        putColors(b,bottom,H_SEGMENTS);
        putColors(b,left,V_SEGMENTS);
        latestBinary = b.array();
        return latestBinary;
    }

    private static void putColors(ByteBuffer b,int[] colors,int count) {
        for(int i=0;i<count;i++) {
            int c = colors[Math.min(i,colors.length-1)];
            b.put((byte)((c>>16)&255));
            b.put((byte)((c>>8)&255));
            b.put((byte)(c&255));
        }
    }

    private static void copyColors(int[] src,int[] dst,int count) {
        if(src==null||src.length==0)return;
        for(int i=0;i<count;i++)dst[i]=src[Math.min(i,src.length-1)];
    }

    /** Compact processed capture JSON. Encoding happens only when an API client asks for it. */
    public static String latestCaptureJson() {
        StringBuilder s=new StringBuilder(1600);
        s.append('{')
                .append("\"protocol\":\"ambip-capture-v1\",")
                .append("\"seq\":").append(latestCaptureSequence).append(',')
                .append("\"timestampMs\":").append(latestCaptureAtMs).append(',')
                .append("\"active\":").append(active).append(',')
                .append("\"fps\":").append(String.format(Locale.US,"%.2f",fps)).append(',')
                .append("\"w\":").append(width).append(',')
                .append("\"h\":").append(height).append(',');
        appendArray(s,"top",latestTop);s.append(',');
        appendArray(s,"right",latestRight);s.append(',');
        appendArray(s,"bottom",latestBottom);s.append(',');
        appendArray(s,"left",latestLeft);
        return s.append('}').toString();
    }

    private static void appendArray(StringBuilder s,String name,int[] colors){
        s.append('\"').append(name).append("\":[");
        for(int i=0;i<colors.length;i++){
            if(i>0)s.append(',');
            s.append(colors[i]&0x00ffffff);
        }
        s.append(']');
    }

    /** Marks that the next captured 128x72 frame should be encoded as JPEG. */
    public static long requestSnapshot() {
        synchronized (SNAPSHOT_LOCK) {
            long before = snapshotVersion;
            snapshotRequested = true;
            return before;
        }
    }

    /** Called from the capture thread. Returns true only once for each outstanding API request. */
    public static boolean consumeSnapshotRequest() {
        synchronized (SNAPSHOT_LOCK) {
            if (!snapshotRequested) return false;
            snapshotRequested = false;
            return true;
        }
    }

    public static void publishSnapshot(byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) return;
        synchronized (SNAPSHOT_LOCK) {
            latestSnapshotJpeg = jpeg;
            snapshotVersion++;
            SNAPSHOT_LOCK.notifyAll();
        }
    }

    public static byte[] awaitSnapshot(long previousVersion,long timeoutMs) {
        long deadline=System.currentTimeMillis()+Math.max(1L,timeoutMs);
        synchronized (SNAPSHOT_LOCK) {
            while(snapshotVersion<=previousVersion && active) {
                long remaining=deadline-System.currentTimeMillis();
                if(remaining<=0)break;
                try{SNAPSHOT_LOCK.wait(remaining);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
            }
            return latestSnapshotJpeg;
        }
    }

    /** Lightweight diagnostics; no per-frame JSON encoding is done. */
    public static String stateJson() {
        return String.format(Locale.US,
                "{\"v\":3,\"protocol\":\"ambip-light-v3-binary\",\"active\":%s,\"fpsActual\":%.1f,\"w\":%d,\"h\":%d,\"clients\":%d,\"seq\":%d}",
                active ? "true" : "false", fps, width, height, clients, latestCaptureSequence);
    }

    public static String settingsJson() {
        return String.format(Locale.US,
                "{\"active\":%s,\"status\":\"%s\",\"url\":\"%s\",\"urls\":\"%s\",\"clients\":%d,\"fps\":%d,\"fpsActual\":%.1f,\"strip\":%.3f,\"samples\":%d,\"autostart\":%s,\"analysisW\":%d,\"analysisH\":%d,\"protocol\":\"v3-binary\",\"pushUrl\":\"%s\",\"pushFps\":%d,\"captureApi\":\"/api/capture/latest\",\"snapshotApi\":\"/api/capture/snapshot.jpg\"}",
                active ? "true" : "false", escape(status), escape(webUrl), escape(webUrls), clients,
                targetFps, fps, stripRatio, samplesPerZone, autoStartOnBoot ? "true" : "false", width, height,
                escape(pushUrl), pushFps);
    }

    private static String safe(String s){return s==null?"":s;}
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
