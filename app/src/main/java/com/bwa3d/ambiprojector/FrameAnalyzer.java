package com.bwa3d.ambiprojector;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

/** CPU-light analyzer with manual quadrilateral calibration and a simple auto detector. */
public final class FrameAnalyzer implements ImageAnalysis.Analyzer {
    public interface Listener { void onAmbilightFrame(AmbilightState state); }
    public interface DetectionListener { void onDetected(float[] corners, float confidence); }

    private final Listener listener;
    private volatile DetectionListener detectionListener;
    private final AtomicReference<float[]> corners = new AtomicReference<>(new float[]{
            0.15f, 0.20f,  0.85f, 0.20f,  0.85f, 0.80f,  0.15f,0.80f
    });
    private volatile boolean autoDetectRequested = false;
    private volatile float smoothing = 0.68f;
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
    public void setDetectionListener(DetectionListener listener) { this.detectionListener = listener; }

    public void setCorners(float[] normalizedCorners) {
        if (normalizedCorners == null || normalizedCorners.length != 8) return;
        float[] c = normalizedCorners.clone();
        for (int i = 0; i < c.length; i++) c[i] = clamp(c[i], 0.01f, 0.99f);
        corners.set(c);
    }

    public float[] getCorners() { return corners.get().clone(); }
    public void requestAutoDetect() { autoDetectRequested = true; }
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

            if (autoDetectRequested) {
                autoDetectRequested = false;
                DetectionResult result = detectScreen(buffer, rowStride, pixelStride, width, height);
                if (result != null) {
                    corners.set(result.corners);
                    DetectionListener dl = detectionListener;
                    if (dl != null) dl.onDetected(result.corners.clone(), result.confidence);
                }
            }

            float[] c = corners.get();
            int[] top = sampleEdge(buffer, rowStride, pixelStride, width, height,
                    c[0], c[1], c[2], c[3], AmbilightState.H_SEGMENTS);
            int[] right = sampleEdge(buffer, rowStride, pixelStride, width, height,
                    c[2], c[3], c[4], c[5], AmbilightState.V_SEGMENTS);
            int[] bottom = sampleEdge(buffer, rowStride, pixelStride, width, height,
                    c[6], c[7], c[4], c[5], AmbilightState.H_SEGMENTS);
            int[] left = sampleEdge(buffer, rowStride, pixelStride, width, height,
                    c[0], c[1], c[6], c[7], AmbilightState.V_SEGMENTS);

            updateFps();
            applySmoothing(top, smoothTop);
            applySmoothing(bottom, smoothBottom);
            applySmoothing(left, smoothLeft);
            applySmoothing(right, smoothRight);
            smoothingPrimed = true;

            listener.onAmbilightFrame(new AmbilightState(top, bottom, left, right,
                    fps, width, height, estimateWidth(c)));
        } finally {
            image.close();
        }
    }

    private int[] sampleEdge(ByteBuffer buffer, int rowStride, int pixelStride,
                             int frameW, int frameH, float x0, float y0, float x1, float y1,
                             int segments) {
        int[] out = new int[segments];
        float dx = x1 - x0;
        float dy = y1 - y0;
        float nx = -dy;
        float ny = dx;
        float nLen = (float) Math.sqrt(nx * nx + ny * ny);
        if (nLen > 0.0001f) { nx /= nLen; ny /= nLen; }
        float inset = 0.014f;
        nx *= inset; ny *= inset;

        for (int s = 0; s < segments; s++) {
            long r = 0, g = 0, b = 0;
            int count = 0;
            float a0 = s / (float) segments;
            float a1 = (s + 1) / (float) segments;
            for (int k = 1; k <= 7; k++) {
                float a = a0 + (a1 - a0) * (k / 8f);
                float fx = x0 + dx * a + nx;
                float fy = y0 + dy * a + ny;
                int px = clampInt(Math.round(fx * (frameW - 1)), 0, frameW - 1);
                int py = clampInt(Math.round(fy * (frameH - 1)), 0, frameH - 1);
                int p = py * rowStride + px * pixelStride;
                if (p < 0 || p + 3 >= buffer.limit()) continue;
                // CameraX OUTPUT_IMAGE_FORMAT_RGBA_8888 is R,G,B,A in plane 0.
                int rr = buffer.get(p) & 0xFF;
                int gg = buffer.get(p + 1) & 0xFF;
                int bb = buffer.get(p + 2) & 0xFF;
                r += rr; g += gg; b += bb; count++;
            }
            if (count == 0) out[s] = 0xFF000000;
            else out[s] = 0xFF000000
                    | (tone((int)(r / count)) << 16)
                    | (tone((int)(g / count)) << 8)
                    | tone((int)(b / count));
        }
        return out;
    }

    private DetectionResult detectScreen(ByteBuffer buffer, int rowStride, int pixelStride,
                                         int w, int h) {
        int step = Math.max(2, Math.min(w, h) / 180);
        int xStart = (int)(w * 0.06f), xEnd = (int)(w * 0.94f);
        int yStart = (int)(h * 0.06f), yEnd = (int)(h * 0.94f);
        float[] vScore = new float[w];
        float[] hScore = new float[h];

        for (int x = xStart + 2; x < xEnd - 2; x += step) {
            long sum = 0; int n = 0;
            for (int y = yStart; y < yEnd; y += step) {
                int a = luminance(buffer,rowStride,pixelStride,w,h,x-2,y);
                int bb = luminance(buffer,rowStride,pixelStride,w,h,x+2,y);
                sum += Math.abs(a-bb); n++;
            }
            vScore[x] = n == 0 ? 0 : (float)sum/n;
        }
        for (int y = yStart + 2; y < yEnd - 2; y += step) {
            long sum = 0; int n = 0;
            for (int x = xStart; x < xEnd; x += step) {
                int a = luminance(buffer,rowStride,pixelStride,w,h,x,y-2);
                int bb = luminance(buffer,rowStride,pixelStride,w,h,x,y+2);
                sum += Math.abs(a-bb); n++;
            }
            hScore[y] = n == 0 ? 0 : (float)sum/n;
        }

        int left = bestPeak(vScore,xStart,(int)(w*0.48f),step);
        int right = bestPeak(vScore,(int)(w*0.52f),xEnd,step);
        int top = bestPeak(hScore,yStart,(int)(h*0.48f),step);
        int bottom = bestPeak(hScore,(int)(h*0.52f),yEnd,step);
        if (left<0||right<0||top<0||bottom<0) return null;
        float rw=right-left, rh=bottom-top;
        if (rw<w*0.25f||rh<h*0.20f) return null;
        float ratio=rw/rh;
        float ratioScore=1f-Math.min(1f,Math.abs(ratio-16f/9f)/1.2f);
        float edgeScore=(vScore[left]+vScore[right]+hScore[top]+hScore[bottom])/(4f*45f);
        float confidence=clamp(0.35f*ratioScore+0.65f*Math.min(1f,edgeScore),0f,1f);
        float[] c = new float[]{left/(float)w,top/(float)h,right/(float)w,top/(float)h,
                right/(float)w,bottom/(float)h,left/(float)w,bottom/(float)h};
        return new DetectionResult(c,confidence);
    }

    private int bestPeak(float[] scores,int from,int to,int step) {
        float best=7f; int idx=-1;
        for(int i=Math.max(0,from);i<Math.min(scores.length,to);i+=step) {
            if(scores[i]>best){best=scores[i];idx=i;}
        }
        return idx;
    }

    private int luminance(ByteBuffer b,int rowStride,int pixelStride,int w,int h,int x,int y) {
        x=clampInt(x,0,w-1); y=clampInt(y,0,h-1);
        int p=y*rowStride+x*pixelStride;
        if(p<0||p+3>=b.limit()) return 0;
        int r=b.get(p)&0xFF, g=b.get(p+1)&0xFF, bl=b.get(p+2)&0xFF;
        return (r*54+g*183+bl*19)>>8;
    }

    private int tone(int value) { return clampInt(Math.round(value * brightness), 0, 255); }

    private void applySmoothing(int[] colors,float[][] memory) {
        float oldW=smoothing, newW=1f-oldW;
        for(int i=0;i<colors.length;i++) {
            float r=(colors[i]>>16)&0xFF, g=(colors[i]>>8)&0xFF, b=colors[i]&0xFF;
            if(!smoothingPrimed){memory[i][0]=r;memory[i][1]=g;memory[i][2]=b;}
            else {
                memory[i][0]=memory[i][0]*oldW+r*newW;
                memory[i][1]=memory[i][1]*oldW+g*newW;
                memory[i][2]=memory[i][2]*oldW+b*newW;
            }
            colors[i]=0xFF000000|(clampInt(Math.round(memory[i][0]),0,255)<<16)
                    |(clampInt(Math.round(memory[i][1]),0,255)<<8)
                    |clampInt(Math.round(memory[i][2]),0,255);
        }
    }

    private float estimateWidth(float[] c) {
        float top=dist(c[0],c[1],c[2],c[3]);
        float bottom=dist(c[6],c[7],c[4],c[5]);
        return clamp((top+bottom)*0.5f,0f,1f);
    }
    private float dist(float x0,float y0,float x1,float y1){float dx=x1-x0,dy=y1-y0;return(float)Math.sqrt(dx*dx+dy*dy);}
    private void updateFps(){fpsFrames++;long now=System.nanoTime();long elapsed=now-fpsWindowStart;if(elapsed>=700_000_000L){fps=(float)(fpsFrames*1_000_000_000.0/elapsed);fpsFrames=0;fpsWindowStart=now;}}
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
    private static int clampInt(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}
    private static final class DetectionResult {final float[] corners;final float confidence;DetectionResult(float[] corners,float confidence){this.corners=corners;this.confidence=confidence;}}
}
