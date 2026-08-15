package com.bwa3d.ambiprojector;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Cheap RGB-only temporal interpolation for the projector. It never waits for a future frame:
 * each newly received state becomes the target and the currently displayed state is used as the
 * start point. This makes an 8-12 fps source look smooth at display refresh rate without adding a
 * full source-frame of latency.
 */
public final class StateInterpolator {
    public interface Listener { void onInterpolated(AmbilightState state); }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private AmbilightState displayed = AmbilightState.black();
    private AmbilightState start = displayed;
    private AmbilightState target = displayed;
    private long startAt;
    private long lastTargetAt;
    private long sourceIntervalMs = 125L;
    private boolean running;
    private boolean enabled = true;
    private int durationMs = 78;
    private float adaptive = 0.72f;
    private int renderHz = 60;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            renderNow();
            main.postDelayed(this, Math.max(8L, Math.round(1000f / Math.max(30, renderHz))));
        }
    };

    public StateInterpolator(Listener listener) { this.listener = listener; }

    public void start() {
        if (running) return;
        running = true;
        main.post(tick);
    }

    public void stop() {
        running = false;
        main.removeCallbacks(tick);
    }

    public void setEnabled(boolean value) { enabled = value; }
    public void setDurationMs(int value) { durationMs = clamp(value, 0, 180); }
    public void setAdaptive(float value) { adaptive = clamp(value, 0f, 1f); }
    public void setRenderHz(int value) { renderHz = clamp(value, 30, 120); }

    public void push(AmbilightState next) {
        if (next == null) return;
        long now = SystemClock.uptimeMillis();
        if (lastTargetAt > 0) {
            long measured = clamp(now - lastTargetAt, 20L, 500L);
            sourceIntervalMs = Math.round(sourceIntervalMs * 0.70f + measured * 0.30f);
        }
        lastTargetAt = now;

        // Start from what is actually visible now, not from the previous network packet.
        displayed = current(now);
        start = displayed;
        target = next;
        startAt = now;

        if (!enabled || durationMs <= 0) {
            displayed = next;
            emit(next);
        }
    }

    private void renderNow() {
        long now = SystemClock.uptimeMillis();
        AmbilightState s = current(now);
        displayed = s;
        emit(s);
    }

    private AmbilightState current(long now) {
        if (!enabled || target == null || start == null || durationMs <= 0) return target;
        int base = Math.min(durationMs, Math.max(12, Math.round(sourceIntervalMs * 0.82f)));
        float change = difference(start, target);
        // Big flashes/explosions should hit quickly instead of being turned into a slow fade.
        float fastFactor = 1f - adaptive * clamp((change - 0.18f) / 0.62f, 0f, 0.82f);
        int effective = Math.max(10, Math.round(base * fastFactor));
        float t = clamp((now - startAt) / (float)effective, 0f, 1f);
        // Smoothstep has zero slope at the endpoints but still reacts immediately.
        float st = t * t * (3f - 2f * t);
        if (t >= 1f) return target;
        return blend(start, target, st);
    }

    private void emit(AmbilightState state) {
        if (listener != null && state != null) listener.onInterpolated(state);
    }

    private static AmbilightState blend(AmbilightState a, AmbilightState b, float t) {
        int[] top = blendArray(a.top,b.top,t);
        int[] bottom = blendArray(a.bottom,b.bottom,t);
        int[] left = blendArray(a.left,b.left,t);
        int[] right = blendArray(a.right,b.right,t);
        return new AmbilightState(top,bottom,left,right,b.fps,b.sourceWidth,b.sourceHeight,b.cropScale);
    }

    private static int[] blendArray(int[] a,int[] b,float t) {
        int n=Math.min(a.length,b.length); int[] out=new int[n];
        for(int i=0;i<n;i++) out[i]=blendColor(a[i],b[i],t);
        return out;
    }

    private static int blendColor(int a,int b,float t) {
        float u=1f-t;
        int r=Math.round(((a>>16)&255)*u+((b>>16)&255)*t);
        int g=Math.round(((a>>8)&255)*u+((b>>8)&255)*t);
        int bl=Math.round((a&255)*u+(b&255)*t);
        return 0xff000000|(r<<16)|(g<<8)|bl;
    }

    private static float difference(AmbilightState a,AmbilightState b) {
        float sum=0f; int n=0;
        sum+=difference(a.top,b.top); n+=a.top.length;
        sum+=difference(a.bottom,b.bottom); n+=a.bottom.length;
        sum+=difference(a.left,b.left); n+=a.left.length;
        sum+=difference(a.right,b.right); n+=a.right.length;
        return n==0?0f:sum/n;
    }

    private static float difference(int[] a,int[] b) {
        float sum=0f; int n=Math.min(a.length,b.length);
        for(int i=0;i<n;i++) {
            int x=a[i],y=b[i];
            sum+=(Math.abs(((x>>16)&255)-((y>>16)&255))+
                    Math.abs(((x>>8)&255)-((y>>8)&255))+
                    Math.abs((x&255)-(y&255)))/(3f*255f);
        }
        return sum;
    }

    private static int clamp(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}
    private static long clamp(long v,long lo,long hi){return Math.max(lo,Math.min(hi,v));}
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
}
