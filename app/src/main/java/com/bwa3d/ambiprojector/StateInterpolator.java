package com.bwa3d.ambiprojector;

import android.os.SystemClock;
import android.view.Choreographer;

/**
 * VSYNC-driven RGB interpolation for the projector.
 *
 * It never buffers a future source frame. Every packet immediately becomes the new target and the
 * current visible state becomes the new start state. Rendering is scheduled by Choreographer so
 * updates land on the next physical display frame instead of a Handler timer. Large scene changes
 * intentionally get a very short catch-up time.
 */
public final class StateInterpolator implements Choreographer.FrameCallback {
    public interface Listener { void onInterpolated(AmbilightState state); }

    private final Listener listener;
    private final Choreographer choreographer;
    private AmbilightState displayed = AmbilightState.black();
    private AmbilightState start = displayed;
    private AmbilightState target = displayed;
    private long startAt;
    private long lastTargetAt;
    private long sourceIntervalMs = 100L;
    private long lastRenderNs;
    private boolean running;
    private boolean enabled = true;
    private int durationMs = 46;
    private float adaptive = 0.88f;
    private int renderHz = 60;

    public StateInterpolator(Listener listener) {
        this.listener = listener;
        this.choreographer = Choreographer.getInstance();
    }

    public void start() {
        if (running) return;
        running = true;
        lastRenderNs = 0L;
        choreographer.postFrameCallback(this);
    }

    public void stop() {
        running = false;
        choreographer.removeFrameCallback(this);
    }

    public void setEnabled(boolean value) { enabled = value; }
    public void setDurationMs(int value) { durationMs = clamp(value, 0, 140); }
    public void setAdaptive(float value) { adaptive = clamp(value, 0f, 1f); }
    public void setRenderHz(int value) { renderHz = clamp(value, 30, 120); }

    public void push(AmbilightState next) {
        if (next == null) return;
        long now = SystemClock.uptimeMillis();
        if (lastTargetAt > 0) {
            long measured = clamp(now - lastTargetAt, 16L, 500L);
            // Follow changing source FPS quickly without reacting to one odd frame.
            sourceIntervalMs = Math.round(sourceIntervalMs * 0.55f + measured * 0.45f);
        }
        lastTargetAt = now;

        // Never queue. Start from exactly what should be visible at this instant.
        displayed = current(now);
        start = displayed;
        target = next;
        startAt = now;

        if (!enabled || durationMs <= 0) {
            displayed = next;
            emit(next);
        }
    }

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        long minNs = 1_000_000_000L / Math.max(30, renderHz);
        if (lastRenderNs == 0L || frameTimeNanos - lastRenderNs >= minNs - 750_000L) {
            lastRenderNs = frameTimeNanos;
            long now = SystemClock.uptimeMillis();
            AmbilightState s = current(now);
            displayed = s;
            emit(s);
        }
        choreographer.postFrameCallback(this);
    }

    private AmbilightState current(long now) {
        if (!enabled || target == null || start == null || durationMs <= 0) return target;

        // Never spend most of a source-frame chasing an already old target.
        int sourceBound = Math.max(14, Math.round(sourceIntervalMs * 0.52f));
        int base = Math.min(durationMs, sourceBound);
        float change = difference(start, target);

        // Large changes get close to one-display-frame response. Small changes retain fluidity.
        float urgency = clamp((change - 0.055f) / 0.46f, 0f, 1f);
        float fastFactor = 1f - adaptive * 0.78f * urgency;
        int effective = Math.max(10, Math.round(base * fastFactor));
        float t = clamp((now - startAt) / (float)effective, 0f, 1f);
        if (t >= 1f) return target;

        // Fast-start ease-out: unlike smoothstep it does not have a zero slope at t=0.
        float u = 1f - t;
        float eased = 1f - u * u;
        return blend(start, target, eased);
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
        sum+=difference(a.top,b.top); n+=Math.min(a.top.length,b.top.length);
        sum+=difference(a.bottom,b.bottom); n+=Math.min(a.bottom.length,b.bottom.length);
        sum+=difference(a.left,b.left); n+=Math.min(a.left.length,b.left.length);
        sum+=difference(a.right,b.right); n+=Math.min(a.right.length,b.right.length);
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
