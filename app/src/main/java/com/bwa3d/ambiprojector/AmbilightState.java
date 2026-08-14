package com.bwa3d.ambiprojector;

import java.util.Arrays;

/** Immutable-ish frame state handed from the analyzer to the renderer. */
public final class AmbilightState {
    // v0.6: doubled spatial sampling for smoother, more detailed gradients.
    public static final int H_SEGMENTS = 32;
    public static final int V_SEGMENTS = 18;

    public final int[] top = new int[H_SEGMENTS];
    public final int[] bottom = new int[H_SEGMENTS];
    public final int[] left = new int[V_SEGMENTS];
    public final int[] right = new int[V_SEGMENTS];
    public final float fps;
    public final int sourceWidth;
    public final int sourceHeight;
    public final float cropScale;

    public AmbilightState(int[] top, int[] bottom, int[] left, int[] right,
                          float fps, int sourceWidth, int sourceHeight, float cropScale) {
        System.arraycopy(top, 0, this.top, 0, H_SEGMENTS);
        System.arraycopy(bottom, 0, this.bottom, 0, H_SEGMENTS);
        System.arraycopy(left, 0, this.left, 0, V_SEGMENTS);
        System.arraycopy(right, 0, this.right, 0, V_SEGMENTS);
        this.fps = fps;
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.cropScale = cropScale;
    }

    public static AmbilightState black() {
        int[] h = new int[H_SEGMENTS];
        int[] v = new int[V_SEGMENTS];
        Arrays.fill(h, 0xFF000000);
        Arrays.fill(v, 0xFF000000);
        return new AmbilightState(h, h, v, v, 0f, 0, 0, 0.80f);
    }
}
