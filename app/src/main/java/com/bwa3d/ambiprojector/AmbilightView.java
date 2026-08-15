package com.bwa3d.ambiprojector;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;

/** Projection output with editable TV mask, editable context frames and 4-point keystone warp. */
public final class AmbilightView extends View {
    public interface GestureListener { void onSingleTap(); void onLongPress(); void onDoubleTap(); }
    public enum Zone { TOP, BOTTOM, LEFT, RIGHT }
    private static final class Overlay { String text; long until; }

    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Paint textPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GestureDetector gestures;
    private final Matrix keystoneMatrix=new Matrix();
    private volatile AmbilightState state=AmbilightState.black();
    private boolean debug=true;
    private GestureListener gestureListener;
    private final Overlay topOverlay=new Overlay(),bottomOverlay=new Overlay(),leftOverlay=new Overlay(),rightOverlay=new Overlay();
    private float outerFadeRatio=0.16f;
    private volatile float[] keystoneCorners={0f,0f,1f,0f,1f,1f,0f,1f};
    private volatile float[] tvRect={0.20f,0.27f,0.80f,0.73f};
    private volatile float[][] textFrames={
            {0.24f,0.06f,0.76f,0.18f},
            {0.24f,0.82f,0.76f,0.94f},
            {0.03f,0.32f,0.18f,0.68f},
            {0.82f,0.32f,0.97f,0.68f}
    };
    private Bitmap projectionBuffer;
    private Canvas projectionCanvas;

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
    public void setState(AmbilightState newState){state=newState;postInvalidateOnAnimation();}
    public void setDebug(boolean enabled){debug=enabled;invalidate();}
    public boolean isDebug(){return debug;}
    public void setOuterFadeRatio(float value){outerFadeRatio=clamp(value,0.02f,0.42f);invalidate();}
    public float getOuterFadeRatio(){return outerFadeRatio;}
    public void setKeystoneCorners(float[] c){if(c==null||c.length!=8)return;float[] n=c.clone();for(int i=0;i<8;i++)n[i]=clamp(n[i],0f,1f);keystoneCorners=n;invalidate();}
    public float[] getKeystoneCorners(){return keystoneCorners.clone();}
    public void setTvRect(float[] r){if(r==null||r.length!=4)return;tvRect=sanitizeRect(r.clone(),0.08f);invalidate();}
    public float[] getTvRect(){return tvRect.clone();}
    public void setTextFrames(float[][] f){
        if(f==null||f.length!=4)return;float[][] n=new float[4][4];
        for(int i=0;i<4;i++){if(f[i]==null||f[i].length!=4)return;n[i]=sanitizeRect(f[i].clone(),0.05f);}textFrames=n;invalidate();
    }
    public float[][] getTextFrames(){float[][] n=new float[4][4];for(int i=0;i<4;i++)n[i]=textFrames[i].clone();return n;}

    public void showContextOverlay(String text,long durationMs){showContextOverlay(Zone.TOP,text,durationMs);}
    public void showContextOverlay(Zone zone,String text,long durationMs){Overlay o=overlay(zone);o.text=text;o.until=SystemClock.uptimeMillis()+durationMs;invalidate();}
    public void showContextDemo(long durationMs){
        showContextOverlay(Zone.TOP,"TOP · title / score / status",durationMs);
        showContextOverlay(Zone.BOTTOM,"BOTTOM · subtitles / extra info",durationMs);
        showContextOverlay(Zone.LEFT,"LEFT\ncontext\nzone",durationMs);
        showContextOverlay(Zone.RIGHT,"RIGHT\nalerts\nzone",durationMs);
    }
    private Overlay overlay(Zone zone){switch(zone){case BOTTOM:return bottomOverlay;case LEFT:return leftOverlay;case RIGHT:return rightOverlay;default:return topOverlay;}}

    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);recreateBuffer(w,h);}
    private void recreateBuffer(int w,int h){if(projectionBuffer!=null){projectionBuffer.recycle();projectionBuffer=null;projectionCanvas=null;}if(w>0&&h>0){projectionBuffer=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);projectionCanvas=new Canvas(projectionBuffer);}}

    @Override protected void onDraw(@NonNull Canvas canvas){
        super.onDraw(canvas);int w=getWidth(),h=getHeight();if(w<=0||h<=0)return;
        if(projectionBuffer==null||projectionBuffer.getWidth()!=w||projectionBuffer.getHeight()!=h)recreateBuffer(w,h);
        canvas.drawColor(Color.BLACK);if(projectionCanvas==null)return;
        projectionCanvas.drawColor(Color.BLACK);drawProjection(projectionCanvas,w,h);
        float[] k=keystoneCorners;
        float[] src={0f,0f,w,0f,w,h,0f,h};
        float[] dst={k[0]*w,k[1]*h,k[2]*w,k[3]*h,k[4]*w,k[5]*h,k[6]*w,k[7]*h};
        keystoneMatrix.reset();
        if(keystoneMatrix.setPolyToPoly(src,0,dst,0,4))canvas.drawBitmap(projectionBuffer,keystoneMatrix,bitmapPaint);else canvas.drawBitmap(projectionBuffer,0,0,bitmapPaint);
    }

    private void drawProjection(Canvas canvas,int w,int h){
        RectF tv=tvMask(w,h);
        drawContinuousSides(canvas,tv);
        drawSmoothCorner(canvas,tv,true,true,edgeAverage(state.top,true),edgeAverage(state.left,true));
        drawSmoothCorner(canvas,tv,false,true,edgeAverage(state.top,false),edgeAverage(state.right,true));
        drawSmoothCorner(canvas,tv,true,false,edgeAverage(state.bottom,true),edgeAverage(state.left,false));
        drawSmoothCorner(canvas,tv,false,false,edgeAverage(state.bottom,false),edgeAverage(state.right,false));
        drawOuterBlackVignette(canvas);
        paint.setShader(null);paint.setColor(Color.BLACK);canvas.drawRect(tv,paint);
        drawContextFrames(canvas);if(debug)drawDebug(canvas,tv);
    }

    private RectF tvMask(int w,int h){float[] r=tvRect;return new RectF(r[0]*w,r[1]*h,r[2]*w,r[3]*h);}

    private void drawContinuousSides(Canvas c,RectF tv){
        int[] top=softCornerEnds(state.top,mix(edgeAverage(state.top,true),edgeAverage(state.left,true)),mix(edgeAverage(state.top,false),edgeAverage(state.right,true)));
        int[] bottom=softCornerEnds(state.bottom,mix(edgeAverage(state.bottom,true),edgeAverage(state.left,false)),mix(edgeAverage(state.bottom,false),edgeAverage(state.right,false)));
        int[] left=softCornerEnds(state.left,mix(edgeAverage(state.left,true),edgeAverage(state.top,true)),mix(edgeAverage(state.left,false),edgeAverage(state.bottom,true)));
        int[] right=softCornerEnds(state.right,mix(edgeAverage(state.right,true),edgeAverage(state.top,false)),mix(edgeAverage(state.right,false),edgeAverage(state.bottom,false)));
        paint.setShader(new LinearGradient(tv.left,0,tv.right,0,top,positions(top.length),Shader.TileMode.CLAMP));c.drawRect(tv.left,0,tv.right,tv.top+1,paint);
        paint.setShader(new LinearGradient(tv.left,0,tv.right,0,bottom,positions(bottom.length),Shader.TileMode.CLAMP));c.drawRect(tv.left,tv.bottom-1,tv.right,getHeight(),paint);
        paint.setShader(new LinearGradient(0,tv.top,0,tv.bottom,left,positions(left.length),Shader.TileMode.CLAMP));c.drawRect(0,tv.top,tv.left+1,tv.bottom,paint);
        paint.setShader(new LinearGradient(0,tv.top,0,tv.bottom,right,positions(right.length),Shader.TileMode.CLAMP));c.drawRect(tv.right-1,tv.top,getWidth(),tv.bottom,paint);
        paint.setShader(new LinearGradient(0,0,0,tv.top,Color.BLACK,0x00000000,Shader.TileMode.CLAMP));c.drawRect(tv.left,0,tv.right,tv.top,paint);
        paint.setShader(new LinearGradient(0,tv.bottom,0,getHeight(),0x00000000,Color.BLACK,Shader.TileMode.CLAMP));c.drawRect(tv.left,tv.bottom,tv.right,getHeight(),paint);
        paint.setShader(new LinearGradient(0,0,tv.left,0,Color.BLACK,0x00000000,Shader.TileMode.CLAMP));c.drawRect(0,tv.top,tv.left,tv.bottom,paint);
        paint.setShader(new LinearGradient(tv.right,0,getWidth(),0,0x00000000,Color.BLACK,Shader.TileMode.CLAMP));c.drawRect(tv.right,tv.top,getWidth(),tv.bottom,paint);
    }

    private void drawSmoothCorner(Canvas c,RectF tv,boolean left,boolean top,int horizontalColor,int verticalColor){
        float x=left?tv.left:tv.right,y=top?tv.top:tv.bottom;
        float ex=left?tv.left:getWidth()-tv.right,ey=top?tv.top:getHeight()-tv.bottom;
        float radius=Math.max(2f,(float)Math.hypot(ex,ey)*1.18f);float sx=left?1f:-1f,sy=top?1f:-1f;int blended=mix(horizontalColor,verticalColor);
        drawSoftGlow(c,left,top,x,y,radius,blended,168);drawSoftGlow(c,left,top,x+sx*ex*0.20f,y,radius*0.92f,horizontalColor,92);drawSoftGlow(c,left,top,x,y+sy*ey*0.20f,radius*0.92f,verticalColor,92);
    }
    private void drawSoftGlow(Canvas c,boolean left,boolean top,float cx,float cy,float radius,int color,int alpha){
        int center=withAlpha(color,alpha),mid=withAlpha(color,Math.round(alpha*0.56f)),faint=withAlpha(color,Math.round(alpha*0.18f));
        paint.setShader(new RadialGradient(cx,cy,radius,new int[]{center,mid,faint,0x00000000},new float[]{0f,0.30f,0.66f,1f},Shader.TileMode.CLAMP));
        float l=left?0f:Math.min(getWidth(),cx),r=left?Math.max(0f,cx):getWidth(),t=top?0f:Math.min(getHeight(),cy),b=top?Math.max(0f,cy):getHeight();c.drawRect(l,t,r,b,paint);
    }

    private int edgeAverage(int[] src,boolean first){if(src==null||src.length==0)return Color.BLACK;int n=Math.min(4,src.length),rs=0,gs=0,bs=0;for(int i=0;i<n;i++){int idx=first?i:src.length-1-i,c=src[idx];rs+=(c>>16)&255;gs+=(c>>8)&255;bs+=c&255;}return 0xFF000000|((rs/n)<<16)|((gs/n)<<8)|(bs/n);}
    private int[] softCornerEnds(int[] src,int first,int last){int[] out=src.clone();int n=Math.min(4,out.length);for(int i=0;i<n;i++){float strength=(n-i)/(float)(n+1);out[i]=mixWeighted(out[i],first,strength*0.55f);int j=out.length-1-i;out[j]=mixWeighted(out[j],last,strength*0.55f);}return out;}

    private void drawOuterBlackVignette(Canvas c){
        float fw=Math.max(2f,getWidth()*outerFadeRatio),fh=Math.max(2f,getHeight()*outerFadeRatio);
        paint.setShader(new LinearGradient(0,0,fw,0,Color.BLACK,0x00000000,Shader.TileMode.CLAMP));c.drawRect(0,0,fw,getHeight(),paint);
        paint.setShader(new LinearGradient(getWidth()-fw,0,getWidth(),0,0x00000000,Color.BLACK,Shader.TileMode.CLAMP));c.drawRect(getWidth()-fw,0,getWidth(),getHeight(),paint);
        paint.setShader(new LinearGradient(0,0,0,fh,Color.BLACK,0x00000000,Shader.TileMode.CLAMP));c.drawRect(0,0,getWidth(),fh,paint);
        paint.setShader(new LinearGradient(0,getHeight()-fh,0,getHeight(),0x00000000,Color.BLACK,Shader.TileMode.CLAMP));c.drawRect(0,getHeight()-fh,getWidth(),getHeight(),paint);
    }

    private void drawContextFrames(Canvas canvas){
        long now=SystemClock.uptimeMillis();Overlay[] overlays={topOverlay,bottomOverlay,leftOverlay,rightOverlay};float[][] f=textFrames;
        textPaint.setColor(Color.WHITE);textPaint.setShadowLayer(10f,0f,2f,Color.BLACK);
        for(int i=0;i<4;i++)if(alive(overlays[i],now))drawTextInFrame(canvas,overlays[i].text,f[i]);
        textPaint.clearShadowLayer();if(isAnyOverlayAlive(now))postInvalidateDelayed(200);
    }

    private void drawTextInFrame(Canvas c,String text,float[] nr){
        if(text==null)return;RectF r=new RectF(nr[0]*getWidth(),nr[1]*getHeight(),nr[2]*getWidth(),nr[3]*getHeight());
        float maxSize=Math.max(12f,Math.min(r.height()*0.34f,getHeight()*0.045f));textPaint.setTextSize(maxSize);textPaint.setTextAlign(Paint.Align.CENTER);
        String[] lines=text.split("\\n");float line=textPaint.getTextSize()*1.18f;float y=r.centerY()-(lines.length-1)*line*0.5f-(textPaint.ascent()+textPaint.descent())*0.5f;
        for(String s:lines){String draw=ellipsize(s,r.width()*0.92f);c.drawText(draw,r.centerX(),y,textPaint);y+=line;}
    }
    private String ellipsize(String s,float maxW){if(textPaint.measureText(s)<=maxW)return s;String out=s;while(out.length()>2&&textPaint.measureText(out+"…")>maxW)out=out.substring(0,out.length()-1);return out+"…";}
    private boolean alive(Overlay o,long now){if(o.text==null||now>o.until){o.text=null;return false;}return true;}
    private boolean isAnyOverlayAlive(long now){return alive(topOverlay,now)||alive(bottomOverlay,now)||alive(leftOverlay,now)||alive(rightOverlay,now);}

    private void drawDebug(Canvas canvas,RectF tv){paint.setShader(null);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2f);paint.setColor(0x88FFFFFF);canvas.drawRect(tv,paint);paint.setStyle(Paint.Style.FILL);textPaint.setTextAlign(Paint.Align.LEFT);textPaint.setTextSize(Math.max(15f,getHeight()*0.022f));textPaint.setColor(Color.WHITE);float x=18f,y=28f,line=textPaint.getTextSize()*1.35f;canvas.drawText("Ambi Projector v0.10",x,y,textPaint);y+=line;canvas.drawText(String.format("Camera: %dx%d   %.1f fps",state.sourceWidth,state.sourceHeight,state.fps),x,y,textPaint);y+=line;canvas.drawText("Tap: settings · Long: camera TV · Layout: projected TV/text",x,y,textPaint);}

    private float[] positions(int n){float[] p=new float[n];if(n==1){p[0]=0f;return p;}for(int i=0;i<n;i++)p[i]=i/(float)(n-1);return p;}
    private int mix(int a,int b){return mixWeighted(a,b,0.5f);}
    private int mixWeighted(int a,int b,float wb){wb=clamp(wb,0f,1f);float wa=1f-wb;int r=Math.round(((a>>16)&255)*wa+((b>>16)&255)*wb),g=Math.round(((a>>8)&255)*wa+((b>>8)&255)*wb),bl=Math.round((a&255)*wa+(b&255)*wb);return 0xFF000000|(r<<16)|(g<<8)|bl;}
    private int withAlpha(int c,int a){return(clampInt(a,0,255)<<24)|(c&0x00FFFFFF);}
    private float[] sanitizeRect(float[] r,float min){r[0]=clamp(r[0],0f,0.98f);r[1]=clamp(r[1],0f,0.98f);r[2]=clamp(r[2],0.02f,1f);r[3]=clamp(r[3],0.02f,1f);if(r[2]-r[0]<min)r[2]=Math.min(1f,r[0]+min);if(r[3]-r[1]<min)r[3]=Math.min(1f,r[1]+min);if(r[2]-r[0]<min)r[0]=Math.max(0f,r[2]-min);if(r[3]-r[1]<min)r[1]=Math.max(0f,r[3]-min);return r;}
    @Override public boolean onTouchEvent(MotionEvent event){return gestures.onTouchEvent(event)||super.onTouchEvent(event);}
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
    private static int clampInt(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}
}
