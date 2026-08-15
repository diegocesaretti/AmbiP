package com.bwa3d.ambiprojector;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Captures the Android display at low resolution and publishes edge colors to the LAN debug page. */
public final class ScreenCaptureService extends Service {
    public static final String ACTION_START = "com.bwa3d.ambiprojector.START_SCREEN_CAPTURE";
    public static final String ACTION_STOP = "com.bwa3d.ambiprojector.STOP_SCREEN_CAPTURE";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    private static final String TAG = "AmbiScreenCapture";
    private static final String CHANNEL_ID = "ambip_screen_capture";
    private static final int NOTIFICATION_ID = 4102;
    private static final int WEB_PORT = 8080;
    private static final long FRAME_INTERVAL_MS = 65L;
    private static final long PREVIEW_INTERVAL_MS = 700L;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private DebugWebServer webServer;
    private long lastFrameAt;
    private long lastPreviewAt;
    private long fpsWindowStart;
    private int fpsWindowFrames;
    private float fps;
    private int captureWidth;
    private int captureHeight;

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override public void onStop() {
            Log.i(TAG, "MediaProjection stopped by system/user");
            ScreenCaptureHub.setActive(false, "Projection stopped");
            stopSelf();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) return START_NOT_STICKY;

        startForeground(NOTIFICATION_ID, buildNotification("Starting screen capture…"));
        if (mediaProjection != null) return START_NOT_STICKY;

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData;
        if (Build.VERSION.SDK_INT >= 33) resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        else resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultCode == 0 || resultData == null) {
            ScreenCaptureHub.setActive(false, "Missing capture permission");
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            startCapture(resultCode, resultData);
        } catch (Throwable t) {
            Log.e(TAG, "startCapture", t);
            ScreenCaptureHub.setActive(false, "Capture error: " + t.getClass().getSimpleName());
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startCapture(int resultCode, Intent resultData) throws Exception {
        captureThread = new HandlerThread("AmbiPScreenCapture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        webServer = new DebugWebServer(WEB_PORT);
        String url = webServer.start();

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int realW = Math.max(1, dm.widthPixels);
        int realH = Math.max(1, dm.heightPixels);
        if (realW >= realH) {
            captureWidth = 480;
            captureHeight = Math.max(180, Math.round(480f * realH / realW));
        } else {
            captureHeight = 480;
            captureWidth = Math.max(180, Math.round(480f * realW / realH));
        }
        captureWidth = alignEven(captureWidth);
        captureHeight = alignEven(captureHeight);

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);

        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = manager.getMediaProjection(resultCode, resultData);
        if (mediaProjection == null) throw new IllegalStateException("MediaProjection unavailable");
        mediaProjection.registerCallback(projectionCallback, captureHandler);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "AmbiP Screen Debug",
                captureWidth,
                captureHeight,
                Math.max(160, dm.densityDpi),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                captureHandler);

        fpsWindowStart = SystemClock.elapsedRealtime();
        ScreenCaptureHub.setActive(true, "LIVE · " + url);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification("LIVE · " + url));
        Log.i(TAG, "Capture started " + captureWidth + "x" + captureHeight + " at " + url);
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            long now = SystemClock.elapsedRealtime();
            if (now - lastFrameAt < FRAME_INTERVAL_MS) return;
            lastFrameAt = now;

            Image.Plane[] planes = image.getPlanes();
            if (planes.length == 0) return;
            Image.Plane plane = planes[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            if (pixelStride < 3 || rowStride <= 0) return;

            int[] top = new int[AmbilightState.H_SEGMENTS];
            int[] bottom = new int[AmbilightState.H_SEGMENTS];
            int[] left = new int[AmbilightState.V_SEGMENTS];
            int[] right = new int[AmbilightState.V_SEGMENTS];
            sampleEdges(buffer, pixelStride, rowStride, captureWidth, captureHeight, top, bottom, left, right);

            fpsWindowFrames++;
            long elapsed = now - fpsWindowStart;
            if (elapsed >= 900L) {
                fps = fpsWindowFrames * 1000f / Math.max(1L, elapsed);
                fpsWindowFrames = 0;
                fpsWindowStart = now;
            }

            byte[] jpeg = null;
            if (now - lastPreviewAt >= PREVIEW_INTERVAL_MS) {
                lastPreviewAt = now;
                jpeg = makePreview(buffer, pixelStride, rowStride, captureWidth, captureHeight);
            }

            ScreenCaptureHub.publish(top, bottom, left, right, fps, captureWidth, captureHeight, jpeg);
            if (webServer != null) webServer.broadcast(ScreenCaptureHub.getLatestJson());
        } catch (Throwable t) {
            Log.w(TAG, "frame", t);
        } finally {
            if (image != null) image.close();
        }
    }

    private static void sampleEdges(ByteBuffer src, int pixelStride, int rowStride, int w, int h,
                                    int[] top, int[] bottom, int[] left, int[] right) {
        int stripY = Math.max(3, Math.round(h * 0.10f));
        int stripX = Math.max(3, Math.round(w * 0.07f));
        for (int i = 0; i < top.length; i++) {
            int x0 = i * w / top.length;
            int x1 = Math.max(x0 + 1, (i + 1) * w / top.length);
            top[i] = averageRect(src, pixelStride, rowStride, w, h, x0, 0, x1, stripY);
            bottom[i] = averageRect(src, pixelStride, rowStride, w, h, x0, h - stripY, x1, h);
        }
        for (int i = 0; i < left.length; i++) {
            int y0 = i * h / left.length;
            int y1 = Math.max(y0 + 1, (i + 1) * h / left.length);
            left[i] = averageRect(src, pixelStride, rowStride, w, h, 0, y0, stripX, y1);
            right[i] = averageRect(src, pixelStride, rowStride, w, h, w - stripX, y0, w, y1);
        }
    }

    private static int averageRect(ByteBuffer src, int pixelStride, int rowStride, int w, int h,
                                   int x0, int y0, int x1, int y1) {
        x0 = clamp(x0, 0, w - 1); x1 = clamp(x1, x0 + 1, w);
        y0 = clamp(y0, 0, h - 1); y1 = clamp(y1, y0 + 1, h);
        long r = 0, g = 0, b = 0, n = 0;
        int stepX = Math.max(1, (x1 - x0) / 12);
        int stepY = Math.max(1, (y1 - y0) / 5);
        int limit = src.limit();
        for (int y = y0; y < y1; y += stepY) {
            int row = y * rowStride;
            for (int x = x0; x < x1; x += stepX) {
                int p = row + x * pixelStride;
                if (p + 2 >= limit) continue;
                r += src.get(p) & 0xff;
                g += src.get(p + 1) & 0xff;
                b += src.get(p + 2) & 0xff;
                n++;
            }
        }
        if (n == 0) return 0xff000000;
        int rr = (int) (r / n), gg = (int) (g / n), bb = (int) (b / n);
        return 0xff000000 | (rr << 16) | (gg << 8) | bb;
    }

    private static byte[] makePreview(ByteBuffer src, int pixelStride, int rowStride, int w, int h) {
        Bitmap padded = null;
        Bitmap cropped = null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(24 * 1024);
        try {
            int paddedWidth = rowStride / pixelStride;
            padded = Bitmap.createBitmap(paddedWidth, h, Bitmap.Config.ARGB_8888);
            ByteBuffer copy = src.duplicate();
            copy.rewind();
            padded.copyPixelsFromBuffer(copy);
            cropped = paddedWidth == w ? padded : Bitmap.createBitmap(padded, 0, 0, w, h);
            cropped.compress(Bitmap.CompressFormat.JPEG, 68, out);
            return out.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "preview", t);
            return null;
        } finally {
            if (cropped != null && cropped != padded) cropped.recycle();
            if (padded != null) padded.recycle();
            try { out.close(); } catch (Exception ignored) {}
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("AmbiP Screen Debug")
                .setContentText(text)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "AmbiP screen capture", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Screen capture used by the local AmbiP debug web page");
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(channel);
    }

    private static int alignEven(int v) { return (v & 1) == 0 ? v : v + 1; }
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        ScreenCaptureHub.setActive(false, "Stopped");
        if (imageReader != null) { try { imageReader.setOnImageAvailableListener(null, null); } catch (Throwable ignored) {} }
        if (virtualDisplay != null) { try { virtualDisplay.release(); } catch (Throwable ignored) {} virtualDisplay = null; }
        if (mediaProjection != null) {
            try { mediaProjection.unregisterCallback(projectionCallback); } catch (Throwable ignored) {}
            try { mediaProjection.stop(); } catch (Throwable ignored) {}
            mediaProjection = null;
        }
        if (imageReader != null) { try { imageReader.close(); } catch (Throwable ignored) {} imageReader = null; }
        if (webServer != null) { webServer.stop(); webServer = null; }
        if (captureThread != null) { captureThread.quitSafely(); captureThread = null; }
        stopForeground(true);
        super.onDestroy();
    }
}