package com.bwa3d.ambip.tvsource;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
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
import android.os.Process;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;

/**
 * Very small Android TV capture worker. The TV only downsamples the display and extracts packed RGB
 * edge samples. Temporal/color/energy processing happens on the projector.
 */
public final class TvCaptureService extends Service {
    public static final String ACTION_START = "com.bwa3d.ambip.tvsource.START";
    public static final String ACTION_STOP = "com.bwa3d.ambip.tvsource.STOP";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    private static final String TAG = "AmbiPTvCapture";
    private static final String CHANNEL_ID = "ambip_tv_source";
    private static final int NOTIFICATION_ID = 4201;
    private static final int CAPTURE_LONG_SIDE = 160;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private SourceWebServer webServer;
    private long lastFrameAt;
    private long fpsWindowStart;
    private int fpsWindowFrames;
    private float fps;
    private int captureWidth;
    private int captureHeight;

    // Reused arrays: no per-frame color-array allocation.
    private final int[] top = new int[SourceHub.H_SEGMENTS];
    private final int[] bottom = new int[SourceHub.H_SEGMENTS];
    private final int[] left = new int[SourceHub.V_SEGMENTS];
    private final int[] right = new int[SourceHub.V_SEGMENTS];

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override public void onStop() {
            SourceHub.setActive(false, "Capture permission ended");
            stopSelf();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        SourceHub.load(this);
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;

        startForeground(NOTIFICATION_ID, buildNotification("Starting light capture…"));
        if (mediaProjection != null) return START_NOT_STICKY;

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData;
        if (Build.VERSION.SDK_INT >= 33) resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        else resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultCode == 0 || resultData == null) {
            SourceHub.setActive(false, "Missing MediaProjection consent");
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            startCapture(resultCode, resultData);
        } catch (Throwable t) {
            Log.e(TAG, "startCapture", t);
            SourceHub.setActive(false, "Capture error: " + t.getClass().getSimpleName());
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startCapture(int resultCode, Intent resultData) throws Exception {
        captureThread = new HandlerThread("AmbiPTvLightCapture", Process.THREAD_PRIORITY_BACKGROUND);
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        // Server is deliberately tiny and binds on all interfaces. It also reports every usable LAN IP.
        webServer = new SourceWebServer(this, SourceHub.WEB_PORT);
        String url = webServer.start();

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int realW = Math.max(1, dm.widthPixels);
        int realH = Math.max(1, dm.heightPixels);
        if (realW >= realH) {
            captureWidth = CAPTURE_LONG_SIDE;
            captureHeight = Math.max(72, Math.round(CAPTURE_LONG_SIDE * realH / (float)realW));
        } else {
            captureHeight = CAPTURE_LONG_SIDE;
            captureWidth = Math.max(72, Math.round(CAPTURE_LONG_SIDE * realW / (float)realH));
        }
        captureWidth = even(captureWidth);
        captureHeight = even(captureHeight);

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);

        MediaProjectionManager manager = (MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = manager.getMediaProjection(resultCode, resultData);
        if (mediaProjection == null) throw new IllegalStateException("MediaProjection unavailable");
        mediaProjection.registerCallback(projectionCallback, captureHandler);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "AmbiP TV Light Source",
                captureWidth,
                captureHeight,
                Math.max(120, Math.min(dm.densityDpi, 240)),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                captureHandler);

        fpsWindowStart = SystemClock.elapsedRealtime();
        SourceHub.setActive(true, "LIVE · ECO capture");
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(
                NOTIFICATION_ID, buildNotification("Light data · " + url));
        Log.i(TAG, "TV source started " + captureWidth + "x" + captureHeight + " · " + url);
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            long now = SystemClock.elapsedRealtime();
            long minInterval = Math.max(1L, Math.round(1000f / Math.max(1, SourceHub.targetFps)));
            if (now - lastFrameAt < minInterval) return;
            lastFrameAt = now;

            Image.Plane[] planes = image.getPlanes();
            if (planes.length == 0) return;
            Image.Plane plane = planes[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            if (pixelStride < 3 || rowStride <= 0) return;

            sampleEdgesSparse(buffer, pixelStride, rowStride, captureWidth, captureHeight,
                    top, bottom, left, right, SourceHub.samplesPerZone);

            fpsWindowFrames++;
            long elapsed = now - fpsWindowStart;
            if (elapsed >= 1000L) {
                fps = fpsWindowFrames * 1000f / Math.max(1L, elapsed);
                fpsWindowFrames = 0;
                fpsWindowStart = now;
            }

            String json = SourceHub.publish(top, right, bottom, left, fps, captureWidth, captureHeight);
            if (webServer != null) webServer.broadcast(json);
        } catch (Throwable t) {
            Log.w(TAG, "frame", t);
        } finally {
            if (image != null) image.close();
        }
    }

    /**
     * Instead of averaging rectangles, sample only a few points through each border strip.
     * 100 zones x 3 samples = ~300 pixel reads/frame with the default settings.
     */
    private static void sampleEdgesSparse(ByteBuffer src, int pixelStride, int rowStride, int w, int h,
                                          int[] top, int[] bottom, int[] left, int[] right, int samples) {
        int stripY = Math.max(2, Math.round(h * SourceHub.stripRatio));
        int stripX = Math.max(2, Math.round(w * SourceHub.stripRatio));
        int count = Math.max(1, Math.min(6, samples));

        for (int i = 0; i < top.length; i++) {
            int x0 = i * w / top.length;
            int x1 = Math.max(x0 + 1, (i + 1) * w / top.length);
            int x = (x0 + x1 - 1) / 2;
            top[i] = averageVerticalSamples(src, pixelStride, rowStride, w, h, x, 0, stripY, count);
            bottom[i] = averageVerticalSamples(src, pixelStride, rowStride, w, h, x, h - stripY, h, count);
        }
        for (int i = 0; i < left.length; i++) {
            int y0 = i * h / left.length;
            int y1 = Math.max(y0 + 1, (i + 1) * h / left.length);
            int y = (y0 + y1 - 1) / 2;
            left[i] = averageHorizontalSamples(src, pixelStride, rowStride, w, h, 0, stripX, y, count);
            right[i] = averageHorizontalSamples(src, pixelStride, rowStride, w, h, w - stripX, w, y, count);
        }
    }

    private static int averageVerticalSamples(ByteBuffer src, int ps, int rs, int w, int h,
                                              int x, int y0, int y1, int count) {
        long r=0,g=0,b=0,n=0;
        int limit=src.limit();
        for(int i=0;i<count;i++){
            int y = y0 + Math.round((y1-y0-1) * ((i+0.5f)/count));
            int p = clamp(y,0,h-1)*rs + clamp(x,0,w-1)*ps;
            if(p+2>=limit) continue;
            r+=src.get(p)&255; g+=src.get(p+1)&255; b+=src.get(p+2)&255; n++;
        }
        return pack(r,g,b,n);
    }

    private static int averageHorizontalSamples(ByteBuffer src, int ps, int rs, int w, int h,
                                                int x0, int x1, int y, int count) {
        long r=0,g=0,b=0,n=0;
        int limit=src.limit();
        for(int i=0;i<count;i++){
            int x = x0 + Math.round((x1-x0-1) * ((i+0.5f)/count));
            int p = clamp(y,0,h-1)*rs + clamp(x,0,w-1)*ps;
            if(p+2>=limit) continue;
            r+=src.get(p)&255; g+=src.get(p+1)&255; b+=src.get(p+2)&255; n++;
        }
        return pack(r,g,b,n);
    }

    private static int pack(long r,long g,long b,long n){
        if(n<=0)return 0xff000000;
        return 0xff000000|(((int)(r/n))<<16)|(((int)(g/n))<<8)|((int)(b/n));
    }

    private Notification buildNotification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("AmbiP TV Source")
                .setContentText(text)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel c = new NotificationChannel(CHANNEL_ID, "AmbiP TV light source", NotificationManager.IMPORTANCE_LOW);
        c.setDescription("Low-load TV edge color source");
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
    }

    private static int even(int v){return (v&1)==0?v:v+1;}
    private static int clamp(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}

    @Nullable @Override public IBinder onBind(Intent intent){return null;}

    @Override public void onDestroy() {
        SourceHub.setActive(false, "Stopped — start capture again from the TV app");
        if(imageReader!=null)try{imageReader.setOnImageAvailableListener(null,null);}catch(Throwable ignored){}
        if(virtualDisplay!=null){try{virtualDisplay.release();}catch(Throwable ignored){}virtualDisplay=null;}
        if(mediaProjection!=null){try{mediaProjection.unregisterCallback(projectionCallback);}catch(Throwable ignored){}try{mediaProjection.stop();}catch(Throwable ignored){}mediaProjection=null;}
        if(imageReader!=null){try{imageReader.close();}catch(Throwable ignored){}imageReader=null;}
        if(webServer!=null){webServer.stop();webServer=null;}
        if(captureThread!=null){captureThread.quitSafely();captureThread=null;}
        stopForeground(true);
        super.onDestroy();
    }
}
