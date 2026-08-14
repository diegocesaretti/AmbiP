package com.bwa3d.ambiprojector;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;

/** Projection output layer: continuous ambient halo, TV mask, black outer vignette and four text zones. */
public final class AmbilightView extends View {
    public interface GestureListener {
        void onSingleTap();
        void onLongPress();
        void onDoubleTap();
    }

    public enum Zone { TOP, BOTTOM, LEFT, RIGHT }
    private static final class Overlay { String text; long until; }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GestureDetector gestures;
    private volatile AmbilightState state = AmbilightState.black();
    private boolean debug = true;
    private GestureListener gestureListener;
    private final Overlay topOverlay = new Overlay();
    private final Overlay bottomOverlay = new Overlay();
    private final Overlay leftOverlay = new Overlay();
    private final Overlay rightOverlay = new Overlay();
    private float maskWidthRatio = 0.60f;
    private float outerFadeRatio = 0.16f;

    public AmbilightView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        textPaint.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL));
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(@NonNull MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(@NonNull MotionEvent e) { if (gestureListener != null) gestureListener.onSingleTap(); return true; }
            @Override public void onLongPress(@NonNull MotionEvent e) { if (gestureListener != null) gestureListener.onLongPress(); }
            @Override public boolean onDoubleTap(@NonNull MotionEvent e) { if (gestureListener != null) gestureListener.onDoubleTap(); return true; }
        });
    }

    public void setGestureListener(GestureListener listener) { gestureListener = listener; }
    public void setState(AmbilightState newState) { state = newState; postInvalidateOnAnimation(); }
    public void setDebug(boolean enabled) { debug = enabled; invalidate(); }
    public boolean isDebug() { return debug; }
    public void setOuterFadeRatio(float value) { outerFadeRatio = clamp(value, 0.02f, 0.42f); invalidate(); }
    public float getOuterFadeRatio() { return outerFadeRatio; }

    public void showContextOverlay(String text, long durationMs) { showContextOverlay(Zone.TOP, text, durationMs); }
    public void showContextOverlay(Zone zone, String text, long durationMs) {
        Overlay o = overlay(zone); o.text = text; o.until = SystemClock.uptimeMillis() + durationMs; invalidate();
    }
    public void showContextDemo(long durationMs) {
        showContextOverlay(Zone.TOP, "TOP · title / score / status", durationMs);
        showContextOverlay(Zone.BOTTOM, "BOTTOM · subtitles / extra info", durationMs);
        showContextOverlay(Zone.LEFT, "LEFT\ncontext\nzone", durationMs);
        showContextOverlay(Zone.RIGHT, "RIGHT\nalerts\nzone", durationMs);
    }
    private Overlay overlay(Zone zone) {
        switch(zone){case BOTTOM:return bottomOverlay;case LEFT:return leftOverlay;case RIGHT:return rightOverlay;default:return topOverlay;}
    }

    @Override protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        int w=getWidth(),h=getHeight(); if(w<=0||h<=0)return;
        canvas.drawColor(Color.BLACK);
        RectF tv=tvMask(w,h);
        drawContinuousSides(canvas,tv);
        drawCornerGlow(canvas,tv,tv.left,tv.top,mix(state.top[0],state.left[0]));
        drawCornerGlow(canvas,tv,tv.right,tv.top,mix(state.top[state.top.length-1],state.right[0]));
        drawCornerGlow(canvas,tv,tv.left,tv.bottom,mix(state.bottom[0],state.left[state.left.length-1]));
        drawCornerGlow(canvas,tv,tv.right,tv.bottom,mix(state.bottom[state.bottom.length-1],state.right[state.right.length-1]));

        // Always force the physical edge of the projector image to black, regardless of corner glow.
        drawOuterBlackVignette(canvas);

        paint.setShader(null); paint.setColor(Color.BLACK); canvas.drawRect(tv,paint);
        drawContextZones(canvas,tv);
        if(debug) drawDebug(canvas,tv);
    }

    private RectF tvMask(int w,int h){float mw=w*maskWidthRatio,mh=mw*9f/16f;if(mh>h*0.66f){mh=h*0.66f;mw=mh*16f/9f;}float l=(w-mw)*0.5f,t=(h-mh)*0.5f;return new RectF(l,t,l+mw,t+mh);}

    private void drawContinuousSides(Canvas c,RectF tv){
        int[] top=withCornerMix(state.top,mix(state.top[0],state.left[0]),mix(state.top[state.top.length-1],state.right[0]));
        int[] bottom=withCornerMix(state.bottom,mix(state.bottom[0],state.left[state.left.length-1]),mix(state.bottom[state.bottom.length-1],state.right[state.right.length-1]));
        int[] left=withCornerMix(state.left,mix(state.left[0],state.top[0]),mix(state.left[state.left.length-1],state.bottom[0]));
        int[] right=withCornerMix(state.right,mix(state.right[0],state.top[state.top.length-1]),mix(state.right[state.right.length-1],state.bottom[state.bottom.length-1]));
        float[] hp=positions(top.length),vp=positions(left.length);

        paint.setShader(new LinearGradient(tv.left,0,tv.right,0,top,hp,Shader.TileMode.CLAMP));
        c.drawRect(tv.left,0,tv.right,tv.top+1,paint);
        paint.setShader(new LinearGradient(tv.left,0,tv.right,0,bottom,positions(bottom.length),Shader.TileMode.CLAMP));
        c.drawRect(tv.left,tv.bottom-1,tv.right,getHeight(),paint);
        paint.setShader(new LinearGradient(0,tv.top,0,tv.bottom,left,vp,Shader.TileMode.CLAMP));
        c.drawRect(0,tv.top,tv.left+1,tv.bottom,paint);
        paint.setShader(new LinearGradient(0,tv.top,0,tv.bottom,right,positions(right.length),Shader.TileMode.CLAMP));
        c.drawRect(tv.right-1,tv.top,getWidth(),tv.bottom,paint);

        // Base falloff away from the TV.
        paint.setShader(new LinearGradient(0,0,0,tv.top,Color.BLACK,0x00000000,Shader.TileMode.CLAMP)); c.drawRect(tv.left,0,tv.right,tv.top,paint);
        paint.setShader(new LinearGradient(0,tv.bottom,0,getHeight(),0x00000000,Color.BLACK,Shader.TileMode.CLAMP)); c.drawRect(tv.left,tv.bottom,tv.right,getHeight(),paint);
        paint.setShader(new LinearGradient(0,0,tv.left,0,Color.BLACK,0x00000000,Shader.TileMode.CLAMP)); c.drawRect(0,tv.top,tv.left,tv.bottom,paint);
        paint.setShader(new LinearGradient(tv.right,0,getWidth(),0,0x00000000,Color.BLACK,Shader.TileMode.CLAMP)); c.drawRect(tv.right,tv.top,getWidth(),tv.bottom,paint);
    }

    private void drawCornerGlow(Canvas c,RectF tv,float x,float y,int color){
        float rx=Math.max(tv.left,getWidth()-tv.right), ry=Math.max(tv.top,getHeight()-tv.bottom);
        float radius=(float)Math.hypot(rx,ry);
        if(radius<1f)return;
        paint.setShader(new RadialGradient(x,y,radius,new int[]{color,withAlpha(color,130),Color.BLACK},new float[]{0f,0.38f,1f},Shader.TileMode.CLAMP));
        float l=x==tv.left?0f:tv.right, r=x==tv.left?tv.left:getWidth();
        float t=y==tv.top?0f:tv.bottom, b=y==tv.top?tv.top:getHeight();
        c.drawRect(l,t,r,b,paint);
    }

    private void drawOuterBlackVignette(Canvas c) {
        float fw = Math.max(2f, getWidth() * outerFadeRatio);
        float fh = Math.max(2f, getHeight() * outerFadeRatio);

        paint.setShader(new LinearGradient(0,0,fw,0,Color.BLACK,0x00000000,Shader.TileMode.CLAMP));
        c.drawRect(0,0,fw,getHeight(),paint);
        paint.setShader(new LinearGradient(getWidth()-fw,0,getWidth(),0,0x00000000,Color.BLACK,Shader.TileMode.CLAMP));
        c.drawRect(getWidth()-fw,0,getWidth(),getHeight(),paint);
        paint.setShader(new LinearGradient(0,0,0,fh,Color.BLACK,0x00000000,Shader.TileMode.CLAMP));
        c.drawRect(0,0,getWidth(),fh,paint);
        paint.setShader(new LinearGradient(0,getHeight()-fh,0,getHeight(),0x00000000,Color.BLACK,Shader.TileMode.CLAMP));
        c.drawRect(0,getHeight()-fh,getWidth(),getHeight(),paint);
    }

    private int[] withCornerMix(int[] src,int first,int last){int[] out=src.clone();if(out.length>0){out[0]=mix(out[0],first);out[out.length-1]=mix(out[out.length-1],last);}return out;}
    private float[] positions(int n){float[] p=new float[n];if(n==1){p[0]=0f;return p;}for(int i=0;i<n;i++)p[i]=i/(float)(n-1);return p;}
    private int mix(int a,int b){int r=(((a>>16)&255)+((b>>16)&255))/2,g=(((a>>8)&255)+((b>>8)&255))/2,bl=((a&255)+(b&255))/2;return 0xFF000000|(r<<16)|(g<<8)|bl;}
    private int withAlpha(int c,int a){return (a<<24)|(c&0x00FFFFFF);}

    private void drawContextZones(Canvas canvas,RectF tv){long now=SystemClock.uptimeMillis();textPaint.setColor(Color.WHITE);textPaint.setShadowLayer(10f,0f,2f,Color.BLACK);textPaint.setTextSize(Math.max(20f,getHeight()*0.032f));drawHorizontalOverlay(canvas,topOverlay,now,tv.centerX(),Math.max(textPaint.getTextSize()+12f,tv.top*0.55f),Paint.Align.CENTER);drawHorizontalOverlay(canvas,bottomOverlay,now,tv.centerX(),tv.bottom+(getHeight()-tv.bottom)*0.55f,Paint.Align.CENTER);drawVerticalOverlay(canvas,leftOverlay,now,Math.max(14f,tv.left*0.18f),tv.centerY(),Paint.Align.LEFT);drawVerticalOverlay(canvas,rightOverlay,now,getWidth()-Math.max(14f,(getWidth()-tv.right)*0.18f),tv.centerY(),Paint.Align.RIGHT);textPaint.clearShadowLayer();if(isAnyOverlayAlive(now))postInvalidateDelayed(200);}
    private void drawHorizontalOverlay(Canvas c,Overlay o,long now,float x,float y,Paint.Align align){if(!alive(o,now))return;textPaint.setTextAlign(align);c.drawText(o.text==null?"":o.text.replace("\n"," "),x,y,textPaint);}
    private void drawVerticalOverlay(Canvas c,Overlay o,long now,float x,float centerY,Paint.Align align){if(!alive(o,now))return;textPaint.setTextAlign(align);String[] lines=(o.text==null?"":o.text).split("\\n");float line=textPaint.getTextSize()*1.25f,y=centerY-(lines.length-1)*line*0.5f;for(String s:lines){c.drawText(s,x,y,textPaint);y+=line;}}
    private boolean alive(Overlay o,long now){if(o.text==null||now>o.until){o.text=null;return false;}return true;}
    private boolean isAnyOverlayAlive(long now){return alive(topOverlay,now)||alive(bottomOverlay,now)||alive(leftOverlay,now)||alive(rightOverlay,now);}

    private void drawDebug(Canvas canvas,RectF tv){paint.setShader(null);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2f);paint.setColor(0x88FFFFFF);canvas.drawRect(tv,paint);paint.setStyle(Paint.Style.FILL);textPaint.setTextAlign(Paint.Align.LEFT);textPaint.setTextSize(Math.max(15f,getHeight()*0.022f));textPaint.setColor(Color.WHITE);float x=18f,y=28f,line=textPaint.getTextSize()*1.35f;canvas.drawText("Ambi Projector v0.4",x,y,textPaint);y+=line;canvas.drawText(String.format("Camera: %dx%d   %.1f fps",state.sourceWidth,state.sourceHeight,state.fps),x,y,textPaint);y+=line;canvas.drawText("Tap: settings  |  Long: calibrate  |  Double: 4 text zones",x,y,textPaint);}
    @Override public boolean onTouchEvent(MotionEvent event){return gestures.onTouchEvent(event)||super.onTouchEvent(event);}
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
}
