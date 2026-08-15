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
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;

/**
 * Android TV source service. Frames are consumed locally and discarded; only ambip-light-v1 data
 * (RGB + luminance + saturation per edge sample) is published to LAN clients.
 */
public final class TvCaptureService extends Service {
    public static final String ACTION_START = "com.bwa3d.ambip.tvsource.START";
    public static final String ACTION_STOP = "com.bwa3d.ambip.tvsource.STOP";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    private static final String TAG = "AmbiPTvCapture";
    private static final String CHANNEL_ID = "ambip_tv_source";
    private static final int NOTIFICATION_ID = 4201;

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
    private int[] prevTop, prevRight, prevBottom, prevLeft;

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

        startForeground(NOTIFICATION_ID, buildNotification("Starting capture…"));
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
        captureThread = new HandlerThread("AmbiPTvLightCapture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        webServer = new SourceWebServer(this, SourceHub.WEB_PORT);
        String url = webServer.start();

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int realW = Math.max(1, dm.widthPixels);
        int realH = Math.max(1, dm.heightPixels);
        if (realW >= realH) {
            captureWidth = 320;
            captureHeight = Math.max(120, Math.round(320f * realH / realW));
        } else {
            captureHeight = 320;
            captureWidth = Math.max(120, Math.round(320f * realW / realH));
        }
        captureWidth = even(captureWidth);
        captureHeight = even(captureHeight);

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);

        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = manager.getMediaProjection(resultCode, resultData);
        if (mediaProjection == null) throw new IllegalStateException("MediaProjection unavailable");
        mediaProjection.registerCallback(projectionCallback, captureHandler);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "AmbiP TV Light Source",
                captureWidth,
                captureHeight,
                Math.max(160, dm.densityDpi),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                captureHandler);

        fpsWindowStart = SystemClock.elapsedRealtime();
        SourceHub.setActive(true, "LIVE");
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(
                NOTIFICATION_ID, buildNotification("Streaming light data · " + url));
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

            int[] top = new int[SourceHub.H_SEGMENTS];
            int[] bottom = new int[SourceHub.H_SEGMENTS];
            int[] left = new int[SourceHub.V_SEGMENTS];
            int[] right = new int[SourceHub.V_SEGMENTS];
            sampleEdges(buffer, pixelStride, rowStride, captureWidth, captureHeight, top, bottom, left, right);

            tune(top); tune(right); tune(bottom); tune(left);
            prevTop = smooth(top, prevTop, SourceHub.smoothing);
            prevRight = smooth(right, prevRight, SourceHub.smoothing);
            prevBottom = smooth(bottom, prevBottom, SourceHub.smoothing);
            prevLeft = smooth(left, prevLeft, SourceHub.smoothing);

            fpsWindowFrames++;
            long elapsed = now - fpsWindowStart;
            if (elapsed >= 900L) {
                fps = fpsWindowFrames * 1000f / Math.max(1L, elapsed);
                fpsWindowFrames = 0;
                fpsWindowStart = now;
            }

            String json = SourceHub.publish(prevTop, prevRight, prevBottom, prevLeft, fps, captureWidth, captureHeight);
            if (webServer != null) webServer.broadcast(json);
        } catch (Throwable t) {
            Log.w(TAG, "frame", t);
        } finally {
            if (image != null) image.close();
        }
    }

    private static void sampleEdges(ByteBuffer src, int pixelStride, int rowStride, int w, int h,
                                    int[] top, int[] bottom, int[] left, int[] right) {
        int stripY = Math.max(2, Math.round(h * SourceHub.stripRatio));
        int stripX = Math.max(2, Math.round(w * SourceHub.stripRatio));
        for (int i=0;i<top.length;i++) {
            int x0=i*w/top.length, x1=Math.max(x0+1,(i+1)*w/top.length);
            top[i]=averageRect(src,pixelStride,rowStride,w,h,x0,0,x1,stripY);
            bottom[i]=averageRect(src,pixelStride,rowStride,w,h,x0,h-stripY,x1,h);
        }
        for (int i=0;i<left.length;i++) {
            int y0=i*h/left.length, y1=Math.max(y0+1,(i+1)*h/left.length);
            left[i]=averageRect(src,pixelStride,rowStride,w,h,0,y0,stripX,y1);
            right[i]=averageRect(src,pixelStride,rowStride,w,h,w-stripX,y0,w,y1);
        }
    }

    private static int averageRect(ByteBuffer src,int pixelStride,int rowStride,int w,int h,int x0,int y0,int x1,int y1) {
        x0=clamp(x0,0,w-1); x1=clamp(x1,x0+1,w); y0=clamp(y0,0,h-1); y1=clamp(y1,y0+1,h);
        long r=0,g=0,b=0,n=0; int stepX=Math.max(1,(x1-x0)/10), stepY=Math.max(1,(y1-y0)/5); int limit=src.limit();
        for(int y=y0;y<y1;y+=stepY){int row=y*rowStride;for(int x=x0;x<x1;x+=stepX){int p=row+x*pixelStride;if(p+2>=limit)continue;r+=src.get(p)&255;g+=src.get(p+1)&255;b+=src.get(p+2)&255;n++;}}
        if(n==0)return 0xff000000;
        return 0xff000000|(((int)(r/n))<<16)|(((int)(g/n))<<8)|((int)(b/n));
    }

    private static void tune(int[] a) { for (int i=0;i<a.length;i++) a[i]=SourceHub.tuneColor(a[i]); }

    private static int[] smooth(int[] current, int[] previous, float amount) {
        if (previous == null || previous.length != current.length || amount <= 0f) return current.clone();
        float old = Math.max(0f, Math.min(0.95f, amount)), fresh = 1f-old;
        int[] out = new int[current.length];
        for (int i=0;i<current.length;i++) {
            int a=previous[i], b=current[i];
            int r=Math.round(((a>>16)&255)*old+((b>>16)&255)*fresh);
            int g=Math.round(((a>>8)&255)*old+((b>>8)&255)*fresh);
            int bl=Math.round((a&255)*old+(b&255)*fresh);
            out[i]=0xff000000|(r<<16)|(g<<8)|bl;
        }
        return out;
    }

    private Notification buildNotification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
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
        c.setDescription("Processes TV screen colors locally and streams only light metadata on the LAN");
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
    }

    private static int even(int v){return (v&1)==0?v:v+1;}
    private static int clamp(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}

    @Nullable @Override public IBinder onBind(Intent intent){return null;}

    @Override public void onDestroy() {
        SourceHub.setActive(false, "Stopped — start a new capture session from the TV app");
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
