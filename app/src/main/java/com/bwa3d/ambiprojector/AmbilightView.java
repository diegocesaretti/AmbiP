package com.bwa3d.ambiprojector;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;

/** Projection output with free TV quad, context zones, outer keystone and fast light renderers. */
public final class AmbilightView extends View {
    public interface GestureListener { void onSingleTap(); void onLongPress(); void onDoubleTap(); }
    public enum Zone { TOP, BOTTOM, LEFT, RIGHT }
    public enum ProjectionStyle { EDGE_GRADIENT, COLOR_CLOUD }
    private static final class Overlay { String text; long until; }

    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.DITHER_FLAG);
    private final Paint bitmapPaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Paint textPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GestureDetector gestures;
    private final Matrix keystoneMatrix=new Matrix();
    private final Path tvPath=new Path();
    private final float[] hsvScratch=new float[3];
    private final int[] cloudGradientColors=new int[5];
    private final float[] cloudGradientStops=new float[5];

    private volatile AmbilightState state=AmbilightState.black();
    private boolean debug=true;
    private GestureListener gestureListener;
    private final Overlay topOverlay=new Overlay(),bottomOverlay=new Overlay(),leftOverlay=new Overlay(),rightOverlay=new Overlay();

    private float outerFadeRatio=0.16f;
    /** TL,TR,BR,BL normalized outer projection corners. */
    private volatile float[] keystoneCorners={0f,0f,1f,0f,1f,1f,0f,1f};
    /** TL,TR,BR,BL normalized TV corners before outer projection warp. */
    private volatile float[] tvQuad={0.20f,0.27f,0.80f,0.27f,0.80f,0.73f,0.20f,0.73f};
    private volatile float[][] textFrames={
            {0.24f,0.06f,0.76f,0.18f},
            {0.24f,0.82f,0.76f,0.94f},
            {0.03f,0.32f,0.18f,0.68f},
            {0.82f,0.32f,0.97f,0.68f}
    };

    private volatile ProjectionStyle projectionStyle=ProjectionStyle.COLOR_CLOUD;
    private float cloudSpread=0.42f;
    private float cloudRadius=0.26f;
    private float cloudOpacity=0.60f;
    private float cloudSaturation=1.32f;
    private float cloudBrightness=1.08f;
    private float cloudEdgePull=0.62f;
    private float cloudSoftness=0.72f;
    private float cornerBlend=0.82f;
    private float cornerRadius=1.48f;
    private float cloudDynamicAmount=0.85f;
    private float cloudDynamicRadius=0.65f;
    private float cloudDynamicStretch=0.85f;
    private float cloudDynamicOpacity=0.18f;
    private float cloudEnergyGamma=1.15f;
    private float cloudSaturationWeight=0.60f;
    private float cloudLumaWeight=0.40f;

    // Projector/surface color calibration, intentionally applied after TV capture.
    private float rgbGainR=1f,rgbGainG=1f,rgbGainB=1f;
    private int rgbOffsetR=0,rgbOffsetG=0,rgbOffsetB=0;

    // Soft Color Cloud is rendered into a small light field and filtered up.
    private float cloudRenderScale=0.42f;
    private static final double CLOUD_PIXEL_BUDGET=420000.0;
    private Bitmap projectionBuffer;
    private Canvas projectionCanvas;
    private Bitmap cloudBuffer;
    private Canvas cloudCanvas;

    public AmbilightView(Context context){
        super(context);setBackgroundColor(Color.BLACK);
        textPaint.setTypeface(android.graphics.Typeface.create("sans",android.graphics.Typeface.NORMAL));
        gestures=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(@NonNull MotionEvent e){return true;}
            @Override public boolean onSingleTapConfirmed(@NonNull MotionEvent e){if(gestureListener!=null)gestureListener.onSingleTap();return true;}
            @Override public void onLongPress(@NonNull MotionEvent e){if(gestureListener!=null)gestureListener.onLongPress();}
            @Override public boolean onDoubleTap(@NonNull MotionEvent e){if(gestureListener!=null)gestureListener.onDoubleTap();return true;}
        });
    }

    public void setGestureListener(GestureListener listener){gestureListener=listener;}
    public void setState(AmbilightState newState){if(newState==null)return;state=newState;postInvalidateOnAnimation();}
    public void setDebug(boolean enabled){debug=enabled;invalidate();}
    public boolean isDebug(){return debug;}
    public void setOuterFadeRatio(float value){outerFadeRatio=clamp(value,0.02f,0.42f);invalidate();}
    public float getOuterFadeRatio(){return outerFadeRatio;}
    public void setProjectionStyle(ProjectionStyle style){if(style==null)return;projectionStyle=style;invalidate();}
    public ProjectionStyle getProjectionStyle(){return projectionStyle;}
    public void setCloudSpread(float value){cloudSpread=clamp(value,0.05f,0.90f);invalidate();}
    public void setCloudRadius(float value){cloudRadius=clamp(value,0.08f,0.50f);invalidate();}
    public void setCloudOpacity(float value){cloudOpacity=clamp(value,0.05f,1f);invalidate();}
    public void setCloudSaturation(float value){cloudSaturation=clamp(value,0.50f,2.50f);invalidate();}
    public void setCloudBrightness(float value){cloudBrightness=clamp(value,0.40f,1.80f);invalidate();}
    public void setCloudEdgePull(float value){cloudEdgePull=clamp(value,0f,1f);invalidate();}
    public void setCloudSoftness(float value){cloudSoftness=clamp(value,0f,1f);invalidate();}
    public void setCornerBlend(float value){cornerBlend=clamp(value,0f,1f);invalidate();}
    public void setCornerRadius(float value){cornerRadius=clamp(value,0.70f,2.40f);invalidate();}
    public void setCloudDynamicAmount(float value){cloudDynamicAmount=clamp(value,0f,1.50f);invalidate();}
    public void setCloudDynamicRadius(float value){cloudDynamicRadius=clamp(value,0f,1.50f);invalidate();}
    public void setCloudDynamicStretch(float value){cloudDynamicStretch=clamp(value,0f,2.00f);invalidate();}
    public void setCloudDynamicOpacity(float value){cloudDynamicOpacity=clamp(value,0f,0.80f);invalidate();}
    public void setCloudEnergyGamma(float value){cloudEnergyGamma=clamp(value,0.40f,2.50f);invalidate();}
    public void setCloudSaturationWeight(float value){cloudSaturationWeight=clamp(value,0f,1f);invalidate();}
    public void setCloudLumaWeight(float value){cloudLumaWeight=clamp(value,0f,1f);invalidate();}
    public void setCloudRenderScale(float value){cloudRenderScale=clamp(value,0.20f,1f);recycleCloudBuffer();invalidate();}
    public void setRgbCalibration(float rGain,float gGain,float bGain,int rOffset,int gOffset,int bOffset){
        rgbGainR=clamp(rGain,0.40f,2.00f);rgbGainG=clamp(gGain,0.40f,2.00f);rgbGainB=clamp(bGain,0.40f,2.00f);
        rgbOffsetR=clampInt(rOffset,-80,80);rgbOffsetG=clampInt(gOffset,-80,80);rgbOffsetB=clampInt(bOffset,-80,80);invalidate();
    }
    public void setKeystoneCorners(float[] c){if(c==null||c.length!=8)return;float[] n=c.clone();for(int i=0;i<8;i++)n[i]=clamp(n[i],0f,1f);keystoneCorners=n;invalidate();}
    public float[] getKeystoneCorners(){return keystoneCorners.clone();}
    public void setTvQuad(float[] q){if(q==null||q.length!=8)return;float[] n=q.clone();for(int i=0;i<8;i++)n[i]=clamp(n[i],0f,1f);tvQuad=n;invalidate();}
    public float[] getTvQuad(){return tvQuad.clone();}
    /** Compatibility helper for old saved rectangle settings. */
    public void setTvRect(float[] r){if(r==null||r.length!=4)return;float[] x=sanitizeRect(r.clone(),0.08f);setTvQuad(new float[]{x[0],x[1],x[2],x[1],x[2],x[3],x[0],x[3]});}
    public void setTextFrames(float[][] f){if(f==null||f.length!=4)return;float[][] n=new float[4][4];for(int i=0;i<4;i++){if(f[i]==null||f[i].length!=4)return;n[i]=sanitizeRect(f[i].clone(),0.05f);}textFrames=n;invalidate();}
    public float[][] getTextFrames(){float[][] n=new float[4][4];for(int i=0;i<4;i++)n[i]=textFrames[i].clone();return n;}

    public void showContextOverlay(String text,long durationMs){showContextOverlay(Zone.TOP,text,durationMs);}
    public void showContextOverlay(Zone zone,String text,long durationMs){Overlay o=overlay(zone);o.text=text;o.until=SystemClock.uptimeMillis()+Math.max(0L,durationMs);invalidate();}
    public void clearContextOverlay(Zone zone){Overlay o=overlay(zone);o.text=null;o.until=0;invalidate();}
    public void clearAllContextOverlays(){for(Zone z:Zone.values())clearContextOverlay(z);}
    public void showContextDemo(long durationMs){
        showContextOverlay(Zone.TOP,"TOP · title / score / status",durationMs);
        showContextOverlay(Zone.BOTTOM,"BOTTOM · subtitles / extra info",durationMs);
        showContextOverlay(Zone.LEFT,"LEFT\ncontext\nzone",durationMs);
        showContextOverlay(Zone.RIGHT,"RIGHT\nalerts\nzone",durationMs);
    }
    private Overlay overlay(Zone zone){switch(zone){case BOTTOM:return bottomOverlay;case LEFT:return leftOverlay;case RIGHT:return rightOverlay;default:return topOverlay;}}

    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);recreateBuffer(w,h);}
    private void recreateBuffer(int w,int h){if(projectionBuffer!=null){projectionBuffer.recycle();projectionBuffer=null;projectionCanvas=null;}recycleCloudBuffer();if(w>0&&h>0){projectionBuffer=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);projectionCanvas=new Canvas(projectionBuffer);}}
    private void recycleCloudBuffer(){if(cloudBuffer!=null){cloudBuffer.recycle();cloudBuffer=null;cloudCanvas=null;}}
    private void ensureCloudBuffer(int w,int h){
        if(w<=0||h<=0)return;double cap=Math.sqrt(CLOUD_PIXEL_BUDGET/Math.max(1.0,(double)w*h));float scale=clamp((float)Math.min(cloudRenderScale,cap),0.18f,1f);
        int cw=Math.max(1,Math.min(w,Math.max(96,Math.round(w*scale))));int ch=Math.max(1,Math.min(h,Math.max(54,Math.round(h*scale))));
        if(cloudBuffer!=null&&cloudBuffer.getWidth()==cw&&cloudBuffer.getHeight()==ch)return;recycleCloudBuffer();cloudBuffer=Bitmap.createBitmap(cw,ch,Bitmap.Config.ARGB_8888);cloudCanvas=new Canvas(cloudBuffer);
    }

    @Override protected void onDraw(@NonNull Canvas canvas){
        super.onDraw(canvas);int w=getWidth(),h=getHeight();if(w<=0||h<=0)return;
        if(projectionBuffer==null||projectionBuffer.getWidth()!=w||projectionBuffer.getHeight()!=h)recreateBuffer(w,h);
        canvas.drawColor(Color.BLACK);if(projectionCanvas==null)return;
        projectionCanvas.drawColor(Color.BLACK);drawProjection(projectionCanvas,w,h);
        float[] k=keystoneCorners;float[] src={0f,0f,w,0f,w,h,0f,h};float[] dst={k[0]*w,k[1]*h,k[2]*w,k[3]*h,k[4]*w,k[5]*h,k[6]*w,k[7]*h};
        keystoneMatrix.reset();if(keystoneMatrix.setPolyToPoly(src,0,dst,0,4))canvas.drawBitmap(projectionBuffer,keystoneMatrix,bitmapPaint);else canvas.drawBitmap(projectionBuffer,0,0,bitmapPaint);
    }

    private void drawProjection(Canvas canvas,int w,int h){
        RectF tv=tvBounds(w,h);
        if(projectionStyle==ProjectionStyle.COLOR_CLOUD)drawColorCloudFast(canvas,w,h);else drawEdgeGradient(canvas,tv);
        drawOuterBlackVignette(canvas);drawTvBlackMask(canvas,w,h);drawContextFrames(canvas);if(debug)drawDebug(canvas,w,h);
    }

    private RectF tvBounds(int w,int h){float[] q=tvQuad;float minX=1f,minY=1f,maxX=0f,maxY=0f;for(int i=0;i<8;i+=2){minX=Math.min(minX,q[i]);maxX=Math.max(maxX,q[i]);minY=Math.min(minY,q[i+1]);maxY=Math.max(maxY,q[i+1]);}return new RectF(minX*w,minY*h,maxX*w,maxY*h);}
    private Path buildTvPath(int w,int h){float[] q=tvQuad;tvPath.reset();tvPath.moveTo(q[0]*w,q[1]*h);tvPath.lineTo(q[2]*w,q[3]*h);tvPath.lineTo(q[4]*w,q[5]*h);tvPath.lineTo(q[6]*w,q[7]*h);tvPath.close();return tvPath;}
    private void drawTvBlackMask(Canvas c,int w,int h){paint.setShader(null);paint.setStyle(Paint.Style.FILL);paint.setColor(Color.BLACK);c.drawPath(buildTvPath(w,h),paint);}

    private void drawEdgeGradient(Canvas canvas,RectF tv){
        drawContinuousSides(canvas,tv);
        drawSmoothCorner(canvas,tv,true,true,edgeAverage(state.top,true),edgeAverage(state.left,true));
        drawSmoothCorner(canvas,tv,false,true,edgeAverage(state.top,false),edgeAverage(state.right,true));
        drawSmoothCorner(canvas,tv,true,false,edgeAverage(state.bottom,true),edgeAverage(state.left,false));
        drawSmoothCorner(canvas,tv,false,false,edgeAverage(state.bottom,false),edgeAverage(state.right,false));
    }

    private void drawColorCloudFast(Canvas target,int fullW,int fullH){
        ensureCloudBuffer(fullW,fullH);if(cloudCanvas==null||cloudBuffer==null)return;int rw=cloudBuffer.getWidth(),rh=cloudBuffer.getHeight();cloudCanvas.drawColor(Color.BLACK);
        drawColorCloud(cloudCanvas,tvBounds(rw,rh),rw,rh);paint.setShader(null);target.drawBitmap(cloudBuffer,null,new RectF(0f,0f,fullW,fullH),bitmapPaint);
    }

    private void drawColorCloud(Canvas c,RectF tv,int rw,int rh){
        AmbilightState s=state;float minSide=Math.min(rw,rh);float baseRadius=Math.max(10f,minSide*cloudRadius);float sideRadius=baseRadius*(0.76f+cloudSpread*0.90f);
        drawHorizontalClouds(c,s.top,true,tv,sideRadius,7,rw,rh);drawHorizontalClouds(c,s.bottom,false,tv,sideRadius,7,rw,rh);drawVerticalClouds(c,s.left,true,tv,sideRadius,5,rw,rh);drawVerticalClouds(c,s.right,false,tv,sideRadius,5,rw,rh);
        int tlH=sampleWindow(s.top,0f,3),tlV=sampleWindow(s.left,0f,3),trH=sampleWindow(s.top,1f,3),trV=sampleWindow(s.right,0f,3),blH=sampleWindow(s.bottom,0f,3),blV=sampleWindow(s.left,1f,3),brH=sampleWindow(s.bottom,1f,3),brV=sampleWindow(s.right,1f,3);
        float cr=baseRadius*cornerRadius;drawCornerBridge(c,tv,true,true,tlH,tlV,cr,rw,rh);drawCornerBridge(c,tv,false,true,trH,trV,cr,rw,rh);drawCornerBridge(c,tv,true,false,blH,blV,cr,rw,rh);drawCornerBridge(c,tv,false,false,brH,brV,cr,rw,rh);
    }

    private void drawHorizontalClouds(Canvas c,int[] colors,boolean top,RectF tv,float radius,int count,int rw,int rh){
        if(colors==null||colors.length==0)return;float available=top?tv.top:rh-tv.bottom;float pull=0.82f-0.67f*cloudEdgePull;float baseCy=top?tv.top-available*pull:tv.bottom+available*pull;float direction=top?-1f:1f;
        for(int i=0;i<count;i++){float t=count==1?0.5f:i/(float)(count-1);float cx=tv.left+tv.width()*t;int raw=sampleWindow(colors,t,2);float energy=cloudEnergy(raw);if(energy<0.012f&&maxChannel(raw)<8)continue;int color=boostCloudColor(raw);float rr=radius*dynamicRadiusScale(energy),stretch=dynamicStretchScale(energy),cy=baseCy+direction*available*dynamicReachOffset(energy),strength=0.82f*dynamicOpacityScale(energy);drawCloudBlob(c,cx,cy,rr,color,strength,1f,stretch);}
    }

    private void drawVerticalClouds(Canvas c,int[] colors,boolean left,RectF tv,float radius,int count,int rw,int rh){
        if(colors==null||colors.length==0)return;float available=left?tv.left:rw-tv.right;float pull=0.82f-0.67f*cloudEdgePull;float baseCx=left?tv.left-available*pull:tv.right+available*pull;float direction=left?-1f:1f;
        for(int i=0;i<count;i++){float t=count==1?0.5f:i/(float)(count-1);float cy=tv.top+tv.height()*t;int raw=sampleWindow(colors,t,2);float energy=cloudEnergy(raw);if(energy<0.012f&&maxChannel(raw)<8)continue;int color=boostCloudColor(raw);float rr=radius*dynamicRadiusScale(energy),stretch=dynamicStretchScale(energy),cx=baseCx+direction*available*dynamicReachOffset(energy),strength=0.75f*dynamicOpacityScale(energy);drawCloudBlob(c,cx,cy,rr,color,strength,stretch,1f);}
    }

    private void drawCornerBridge(Canvas c,RectF tv,boolean left,boolean top,int horizontalColor,int verticalColor,float radius,int rw,int rh){
        float availableX=left?tv.left:rw-tv.right,availableY=top?tv.top:rh-tv.bottom,sx=left?-1f:1f,sy=top?-1f:1f,cornerX=left?tv.left:tv.right,cornerY=top?tv.top:tv.bottom,sidePull=0.82f-0.67f*cloudEdgePull,diagonalPull=0.60f-0.40f*cloudEdgePull;
        float sideX=cornerX+sx*availableX*sidePull,sideY=cornerY+sy*availableY*sidePull,centerX=cornerX+sx*availableX*diagonalPull,centerY=cornerY+sy*availableY*diagonalPull;
        int h=boostCloudColor(horizontalColor),v=boostCloudColor(verticalColor),mixedRaw=mixWeighted(horizontalColor,verticalColor,0.5f),mixed=boostCloudColor(mixedRaw);float hEnergy=cloudEnergy(horizontalColor),vEnergy=cloudEnergy(verticalColor),centerEnergy=clamp(Math.max(hEnergy,vEnergy)*0.65f+(hEnergy+vEnergy)*0.175f,0f,1f);if(centerEnergy<0.008f&&maxChannel(mixedRaw)<8)return;
        float centralStrength=(0.34f+cornerBlend*0.34f)*dynamicOpacityScale(centerEnergy),hStrength=(0.24f+cornerBlend*0.16f)*dynamicOpacityScale(hEnergy),vStrength=(0.24f+cornerBlend*0.16f)*dynamicOpacityScale(vEnergy),centerRadius=radius*dynamicRadiusScale(centerEnergy),centerStretch=1f+(dynamicStretchScale(centerEnergy)-1f)*0.72f,centerReach=0.5f*dynamicReachOffset(centerEnergy);
        centerX+=sx*availableX*centerReach;centerY+=sy*availableY*centerReach;drawCloudBlob(c,centerX,centerY,centerRadius,mixed,centralStrength,centerStretch,centerStretch);
        float hx=lerp(cornerX,centerX,0.48f),hy=lerp(sideY,centerY,0.54f),vx=lerp(sideX,centerX,0.54f),vy=lerp(cornerY,centerY,0.48f),hRadius=radius*0.88f*dynamicRadiusScale(hEnergy),vRadius=radius*0.88f*dynamicRadiusScale(vEnergy);
        if(hEnergy>0.008f||maxChannel(horizontalColor)>=8)drawCloudBlob(c,hx,hy,hRadius,h,hStrength,1f,dynamicStretchScale(hEnergy));if(vEnergy>0.008f||maxChannel(verticalColor)>=8)drawCloudBlob(c,vx,vy,vRadius,v,vStrength,dynamicStretchScale(vEnergy),1f);
    }

    private void drawCloudBlob(Canvas c,float cx,float cy,float radius,int color,float strength,float scaleX,float scaleY){
        int alpha=Math.round(255f*cloudOpacity*clamp(strength,0f,1.45f));if(alpha<3||maxChannel(color)<5)return;float s=cloudSoftness,innerPos=lerp(0.44f,0.18f,s),midPos=lerp(0.72f,0.44f,s),faintPos=lerp(0.91f,0.76f,s);
        cloudGradientColors[0]=withAlpha(color,alpha);cloudGradientColors[1]=withAlpha(color,Math.round(alpha*lerp(0.90f,0.72f,s)));cloudGradientColors[2]=withAlpha(color,Math.round(alpha*lerp(0.67f,0.38f,s)));cloudGradientColors[3]=withAlpha(color,Math.round(alpha*lerp(0.30f,0.10f,s)));cloudGradientColors[4]=0x00000000;
        cloudGradientStops[0]=0f;cloudGradientStops[1]=innerPos;cloudGradientStops[2]=midPos;cloudGradientStops[3]=faintPos;cloudGradientStops[4]=1f;float r=Math.max(2f,radius);paint.setShader(new RadialGradient(cx,cy,r,cloudGradientColors,cloudGradientStops,Shader.TileMode.CLAMP));
        c.save();c.translate(cx,cy);c.scale(clamp(scaleX,0.30f,4f),clamp(scaleY,0.30f,4f));c.translate(-cx,-cy);c.drawCircle(cx,cy,r,paint);c.restore();
    }

    private float cloudEnergy(int color){int r=(color>>16)&255,g=(color>>8)&255,b=color&255,max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b));float sat=max<=0?0f:(max-min)/(float)max,lum=clamp((0.2126f*r+0.7152f*g+0.0722f*b)/255f,0f,1f),sw=cloudSaturationWeight,lw=cloudLumaWeight,total=Math.max(0.0001f,sw+lw),raw=(sat*sw+lum*lw)/total;return(float)Math.pow(clamp(raw,0f,1f),cloudEnergyGamma);}
    private float dynamicRadiusScale(float energy){float amount=cloudDynamicAmount*cloudDynamicRadius;return lerp(Math.max(0.35f,1f-0.55f*amount),1f+amount,clamp(energy,0f,1f));}
    private float dynamicStretchScale(float energy){float amount=cloudDynamicAmount*cloudDynamicStretch;return lerp(Math.max(0.35f,1f-0.45f*amount),1f+1.35f*amount,clamp(energy,0f,1f));}
    private float dynamicOpacityScale(float energy){float amount=cloudDynamicAmount*cloudDynamicOpacity;return lerp(Math.max(0.55f,1f-0.45f*amount),1f+0.55f*amount,clamp(energy,0f,1f));}
    private float dynamicReachOffset(float energy){return(clamp(energy,0f,1f)-0.35f)*0.10f*cloudDynamicAmount*cloudDynamicStretch;}

    private int sampleWindow(int[] src,float t,int halfWindow){if(src==null||src.length==0)return Color.BLACK;int center=Math.round(clamp(t,0f,1f)*(src.length-1)),from=Math.max(0,center-halfWindow),to=Math.min(src.length-1,center+halfWindow),rs=0,gs=0,bs=0,n=0;for(int i=from;i<=to;i++){int color=src[i];rs+=(color>>16)&255;gs+=(color>>8)&255;bs+=color&255;n++;}return n==0?Color.BLACK:0xFF000000|((rs/n)<<16)|((gs/n)<<8)|(bs/n);}
    private int boostCloudColor(int color){color=calibrateColor(color);Color.colorToHSV(color,hsvScratch);hsvScratch[1]=clamp(hsvScratch[1]*cloudSaturation,0f,1f);hsvScratch[2]=clamp(hsvScratch[2]*cloudBrightness,0f,1f);return Color.HSVToColor(hsvScratch);}
    private int maxChannel(int color){return Math.max((color>>16)&255,Math.max((color>>8)&255,color&255));}
    private int calibrateColor(int c){int r=clampInt(Math.round(((c>>16)&255)*rgbGainR)+rgbOffsetR,0,255),g=clampInt(Math.round(((c>>8)&255)*rgbGainG)+rgbOffsetG,0,255),b=clampInt(Math.round((c&255)*rgbGainB)+rgbOffsetB,0,255);return 0xff000000|(r<<16)|(g<<8)|b;}
    private int[] calibratedCopy(int[] src){int[] out=src.clone();for(int i=0;i<out.length;i++)out[i]=calibrateColor(out[i]);return out;}

    private void drawContinuousSides(Canvas c,RectF tv){
        int[] top=calibratedCopy(state.top),bottom=calibratedCopy(state.bottom),left=calibratedCopy(state.left),right=calibratedCopy(state.right);
        top=softCornerEnds(top,mix(edgeAverage(top,true,false),edgeAverage(left,true,false)),mix(edgeAverage(top,false,false),edgeAverage(right,true,false)));
        bottom=softCornerEnds(bottom,mix(edgeAverage(bottom,true,false),edgeAverage(left,false,false)),mix(edgeAverage(bottom,false,false),edgeAverage(right,false,false)));
        left=softCornerEnds(left,mix(edgeAverage(left,true,false),edgeAverage(top,true,false)),mix(edgeAverage(left,false,false),edgeAverage(bottom,true,false)));
        right=softCornerEnds(right,mix(edgeAverage(right,true,false),edgeAverage(top,false,false)),mix(edgeAverage(right,false,false),edgeAverage(bottom,false,false)));
        paint.setShader(new LinearGradient(tv.left,0,tv.right,0,top,positions(top.length),Shader.TileMode.CLAMP));c.drawRect(tv.left,0,tv.right,tv.top+1,paint);
        paint.setShader(new LinearGradient(tv.left,0,tv.right,0,bottom,positions(bottom.length),Shader.TileMode.CLAMP));c.drawRect(tv.left,tv.bottom-1,tv.right,getHeight(),paint);
        paint.setShader(new LinearGradient(0,tv.top,0,tv.bottom,left,positions(left.length),Shader.TileMode.CLAMP));c.drawRect(0,tv.top,tv.left+1,tv.bottom,paint);
        paint.setShader(new LinearGradient(0,tv.top,0,tv.bottom,right,positions(right.length),Shader.TileMode.CLAMP));c.drawRect(tv.right-1,tv.top,getWidth(),tv.bottom,paint);
        paint.setShader(new LinearGradient(0,0,0,tv.top,Color.BLACK,0x00000000,Shader.TileMode.CLAMP));c.drawRect(tv.left,0,tv.right,tv.top,paint);paint.setShader(new LinearGradient(0,tv.bottom,0,getHeight(),0x00000000,Color.BLACK,Shader.TileMode.CLAMP));c.drawRect(tv.left,tv.bottom,tv.right,getHeight(),paint);paint.setShader(new LinearGradient(0,0,tv.left,0,Color.BLACK,0x00000000,Shader.TileMode.CLAMP));c.drawRect(0,tv.top,tv.left,tv.bottom,paint);paint.setShader(new LinearGradient(tv.right,0,getWidth(),0,0x00000000,Color.BLACK,Shader.TileMode.CLAMP));c.drawRect(tv.right,tv.top,getWidth(),tv.bottom,paint);
    }

    private void drawSmoothCorner(Canvas c,RectF tv,boolean left,boolean top,int horizontalColor,int verticalColor){float x=left?tv.left:tv.right,y=top?tv.top:tv.bottom,ex=left?tv.left:getWidth()-tv.right,ey=top?tv.top:getHeight()-tv.bottom,radius=Math.max(2f,(float)Math.hypot(ex,ey)*1.18f),sx=left?1f:-1f,sy=top?1f:-1f;int blended=mix(horizontalColor,verticalColor);drawSoftGlow(c,left,top,x,y,radius,blended,168);drawSoftGlow(c,left,top,x+sx*ex*0.20f,y,radius*0.92f,horizontalColor,92);drawSoftGlow(c,left,top,x,y+sy*ey*0.20f,radius*0.92f,verticalColor,92);}
    private void drawSoftGlow(Canvas c,boolean left,boolean top,float cx,float cy,float radius,int color,int alpha){int center=withAlpha(color,alpha),mid=withAlpha(color,Math.round(alpha*0.56f)),faint=withAlpha(color,Math.round(alpha*0.18f));paint.setShader(new RadialGradient(cx,cy,radius,new int[]{center,mid,faint,0x00000000},new float[]{0f,0.30f,0.66f,1f},Shader.TileMode.CLAMP));float l=left?0f:Math.min(getWidth(),cx),r=left?Math.max(0f,cx):getWidth(),t=top?0f:Math.min(getHeight(),cy),b=top?Math.max(0f,cy):getHeight();c.drawRect(l,t,r,b,paint);}
    private int edgeAverage(int[] src,boolean first){return edgeAverage(src,first,true);}
    private int edgeAverage(int[] src,boolean first,boolean calibrate){if(src==null||src.length==0)return Color.BLACK;int n=Math.min(4,src.length),rs=0,gs=0,bs=0;for(int i=0;i<n;i++){int idx=first?i:src.length-1-i,c=calibrate?calibrateColor(src[idx]):src[idx];rs+=(c>>16)&255;gs+=(c>>8)&255;bs+=c&255;}return 0xFF000000|((rs/n)<<16)|((gs/n)<<8)|(bs/n);}
    private int[] softCornerEnds(int[] src,int first,int last){int[] out=src.clone();int n=Math.min(4,out.length);for(int i=0;i<n;i++){float strength=(n-i)/(float)(n+1);out[i]=mixWeighted(out[i],first,strength*0.55f);int j=out.length-1-i;out[j]=mixWeighted(out[j],last,strength*0.55f);}return out;}

    private void drawOuterBlackVignette(Canvas c){float fw=Math.max(2f,getWidth()*outerFadeRatio),fh=Math.max(2f,getHeight()*outerFadeRatio);paint.setShader(new LinearGradient(0,0,fw,0,Color.BLACK,0x00000000,Shader.TileMode.CLAMP));c.drawRect(0,0,fw,getHeight(),paint);paint.setShader(new LinearGradient(getWidth()-fw,0,getWidth(),0,0x00000000,Color.BLACK,Shader.TileMode.CLAMP));c.drawRect(getWidth()-fw,0,getWidth(),getHeight(),paint);paint.setShader(new LinearGradient(0,0,0,fh,Color.BLACK,0x00000000,Shader.TileMode.CLAMP));c.drawRect(0,0,getWidth(),fh,paint);paint.setShader(new LinearGradient(0,getHeight()-fh,0,getHeight(),0x00000000,Color.BLACK,Shader.TileMode.CLAMP));c.drawRect(0,getHeight()-fh,getWidth(),getHeight(),paint);}

    private void drawContextFrames(Canvas canvas){long now=SystemClock.uptimeMillis();Overlay[] overlays={topOverlay,bottomOverlay,leftOverlay,rightOverlay};float[][] f=textFrames;textPaint.setColor(Color.WHITE);textPaint.setShadowLayer(10f,0f,2f,Color.BLACK);for(int i=0;i<4;i++)if(alive(overlays[i],now))drawTextInFrame(canvas,overlays[i].text,f[i]);textPaint.clearShadowLayer();if(isAnyOverlayAlive(now))postInvalidateDelayed(200);}
    private void drawTextInFrame(Canvas c,String text,float[] nr){if(text==null)return;RectF r=new RectF(nr[0]*getWidth(),nr[1]*getHeight(),nr[2]*getWidth(),nr[3]*getHeight());float maxSize=Math.max(12f,Math.min(r.height()*0.34f,getHeight()*0.045f));textPaint.setTextSize(maxSize);textPaint.setTextAlign(Paint.Align.CENTER);String[] lines=text.split("\\n");float line=textPaint.getTextSize()*1.18f,y=r.centerY()-(lines.length-1)*line*0.5f-(textPaint.ascent()+textPaint.descent())*0.5f;for(String s:lines){String draw=ellipsize(s,r.width()*0.92f);c.drawText(draw,r.centerX(),y,textPaint);y+=line;}}
    private String ellipsize(String s,float maxW){if(textPaint.measureText(s)<=maxW)return s;String out=s;while(out.length()>2&&textPaint.measureText(out+"…")>maxW)out=out.substring(0,out.length()-1);return out+"…";}
    private boolean alive(Overlay o,long now){if(o.text==null||now>o.until){o.text=null;return false;}return true;}
    private boolean isAnyOverlayAlive(long now){return alive(topOverlay,now)||alive(bottomOverlay,now)||alive(leftOverlay,now)||alive(rightOverlay,now);}

    private void drawDebug(Canvas canvas,int w,int h){paint.setShader(null);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3f);paint.setColor(0xaa60ff9c);canvas.drawPath(buildTvPath(w,h),paint);paint.setStyle(Paint.Style.FILL);textPaint.setTextAlign(Paint.Align.LEFT);textPaint.setTextSize(Math.max(15f,getHeight()*0.022f));textPaint.setColor(Color.WHITE);float x=18f,y=28f,line=textPaint.getTextSize()*1.35f;canvas.drawText("Ambi Projector v0.21 · "+(projectionStyle==ProjectionStyle.COLOR_CLOUD?"FAST CLOUD":"EDGE"),x,y,textPaint);y+=line;canvas.drawText(String.format(LocaleHolder.FORMAT,"Network source: %dx%d   %.1f fps",state.sourceWidth,state.sourceHeight,state.fps),x,y,textPaint);y+=line;canvas.drawText("TV mask: free quad · Text zones: API/drag",x,y,textPaint);}

    private float[] positions(int n){float[] p=new float[n];if(n==1){p[0]=0f;return p;}for(int i=0;i<n;i++)p[i]=i/(float)(n-1);return p;}
    private float lerp(float a,float b,float t){return a+(b-a)*t;}
    private int mix(int a,int b){return mixWeighted(a,b,0.5f);}
    private int mixWeighted(int a,int b,float wb){wb=clamp(wb,0f,1f);float wa=1f-wb;int r=Math.round(((a>>16)&255)*wa+((b>>16)&255)*wb),g=Math.round(((a>>8)&255)*wa+((b>>8)&255)*wb),bl=Math.round((a&255)*wa+(b&255)*wb);return 0xFF000000|(r<<16)|(g<<8)|bl;}
    private int withAlpha(int c,int a){return(clampInt(a,0,255)<<24)|(c&0x00FFFFFF);}
    private float[] sanitizeRect(float[] r,float min){r[0]=clamp(r[0],0f,0.98f);r[1]=clamp(r[1],0f,0.98f);r[2]=clamp(r[2],0.02f,1f);r[3]=clamp(r[3],0.02f,1f);if(r[2]-r[0]<min)r[2]=Math.min(1f,r[0]+min);if(r[3]-r[1]<min)r[3]=Math.min(1f,r[1]+min);if(r[2]-r[0]<min)r[0]=Math.max(0f,r[2]-min);if(r[3]-r[1]<min)r[1]=Math.max(0f,r[3]-min);return r;}
    @Override public boolean onTouchEvent(MotionEvent event){return gestures.onTouchEvent(event)||super.onTouchEvent(event);}
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
    private static int clampInt(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}
    /** Avoids allocating a Locale just for the debug overlay format. */
    private static final class LocaleHolder { static final java.util.Locale FORMAT=java.util.Locale.US; }
}
