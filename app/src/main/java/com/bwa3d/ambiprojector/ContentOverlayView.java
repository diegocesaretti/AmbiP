package com.bwa3d.ambiprojector;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Base64;
import android.view.View;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Lightweight warped content layer drawn above AmbilightView.
 * Text and images use the same outer keystone as the projector light field but do not touch the
 * low-latency Ambilight network/rendering path.
 */
public final class ContentOverlayView extends View {
    private static final class TextState { String text; long until; }
    private static final class ImageState { Bitmap bitmap; String source; String fit="contain"; long until; }

    private final Paint textPaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint imagePaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Paint debugPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix warp=new Matrix();
    private final ExecutorService imagePool=Executors.newFixedThreadPool(2);
    private final AtomicIntegerArray imageTokens=new AtomicIntegerArray(2);
    private final TextState[] texts={new TextState(),new TextState(),new TextState(),new TextState()};
    private final ImageState[] images={new ImageState(),new ImageState()};

    private volatile float[] keystone={0f,0f,1f,0f,1f,1f,0f,1f};
    private volatile float[][] textFrames={{.24f,.06f,.76f,.18f},{.24f,.82f,.76f,.94f},{.03f,.32f,.18f,.68f},{.82f,.32f,.97f,.68f}};
    private volatile float[][] imageFrames={{.03f,.07f,.22f,.28f},{.78f,.07f,.97f,.28f}};
    private volatile float[] textSizePct={4.5f,4.5f,4.5f,4.5f};
    private volatile String[] textAlign={"center","center","center","center"};
    private boolean debug;
    private Bitmap buffer;
    private Canvas bufferCanvas;

    public ContentOverlayView(Context context){
        super(context);
        setBackgroundColor(Color.TRANSPARENT);
        setClickable(false);
        setFocusable(false);
        textPaint.setTypeface(android.graphics.Typeface.create("sans",android.graphics.Typeface.NORMAL));
        imagePaint.setFilterBitmap(true);
    }

    public void setKeystone(float[] corners){if(corners==null||corners.length!=8)return;float[] n=corners.clone();for(int i=0;i<8;i++)n[i]=clamp(n[i],0f,1f);keystone=n;invalidate();}
    public void setTextFrames(float[][] frames){if(frames==null||frames.length!=4)return;float[][] n=new float[4][4];for(int z=0;z<4;z++){if(frames[z]==null||frames[z].length!=4)return;n[z]=sanitize(frames[z],.04f);}textFrames=n;invalidate();}
    public void setImageFrames(float[][] frames){if(frames==null||frames.length!=2)return;float[][] n=new float[2][4];for(int z=0;z<2;z++){if(frames[z]==null||frames[z].length!=4)return;n[z]=sanitize(frames[z],.04f);}imageFrames=n;invalidate();}
    public void setTextStyles(float[] sizes,String[] aligns){if(sizes!=null&&sizes.length==4){float[] n=sizes.clone();for(int i=0;i<4;i++)n[i]=clamp(n[i],1f,12f);textSizePct=n;}if(aligns!=null&&aligns.length==4){String[] n=aligns.clone();for(int i=0;i<4;i++)n[i]=cleanAlign(n[i]);textAlign=n;}invalidate();}
    public void setDebug(boolean enabled){debug=enabled;invalidate();}

    public void showText(int zone,String text,long durationMs){if(zone<0||zone>=4)return;TextState s=texts[zone];s.text=text;s.until=durationMs<=0?Long.MAX_VALUE:SystemClock.uptimeMillis()+durationMs;postInvalidateOnAnimation();}
    public void clearText(int zone){if(zone<0||zone>=4)return;texts[zone].text=null;texts[zone].until=0;invalidate();}
    public void clearTexts(){for(int i=0;i<4;i++)clearText(i);}

    public void showImage(int zone,String source,long durationMs,String fit){
        if(zone<0||zone>=2)return;
        if(source==null||source.trim().isEmpty()){clearImage(zone);return;}
        final String src=source.trim();final String cleanFit=cleanFit(fit);final int token=imageTokens.incrementAndGet(zone);final long until=durationMs<=0?Long.MAX_VALUE:SystemClock.uptimeMillis()+durationMs;
        imagePool.execute(()->{
            Bitmap decoded=null;try{decoded=decodeSource(src);}catch(Throwable ignored){}
            final Bitmap ready=decoded;
            post(()->{
                if(imageTokens.get(zone)!=token){if(ready!=null&&!ready.isRecycled())ready.recycle();return;}
                ImageState s=images[zone];Bitmap old=s.bitmap;s.bitmap=ready;s.source=ready==null?null:src;s.fit=cleanFit;s.until=ready==null?0:until;
                if(old!=null&&old!=ready&&!old.isRecycled())old.recycle();invalidate();
            });
        });
    }
    public void clearImage(int zone){if(zone<0||zone>=2)return;imageTokens.incrementAndGet(zone);ImageState s=images[zone];Bitmap old=s.bitmap;s.bitmap=null;s.source=null;s.until=0;if(old!=null&&!old.isRecycled())old.recycle();invalidate();}
    public void clearImages(){clearImage(0);clearImage(1);}

    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);recreateBuffer(w,h);}
    private void recreateBuffer(int w,int h){if(buffer!=null){buffer.recycle();buffer=null;bufferCanvas=null;}if(w>0&&h>0){buffer=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);bufferCanvas=new Canvas(buffer);}}

    @Override protected void onDraw(@NonNull Canvas canvas){
        super.onDraw(canvas);int w=getWidth(),h=getHeight();if(w<=0||h<=0)return;if(buffer==null||buffer.getWidth()!=w||buffer.getHeight()!=h)recreateBuffer(w,h);if(bufferCanvas==null)return;
        bufferCanvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);long now=SystemClock.uptimeMillis();boolean ticking=false;
        for(int z=0;z<2;z++)if(imageAlive(images[z],now)){drawImage(bufferCanvas,z,images[z]);if(images[z].until!=Long.MAX_VALUE)ticking=true;}
        for(int z=0;z<4;z++)if(textAlive(texts[z],now)){drawText(bufferCanvas,z,texts[z].text);if(texts[z].until!=Long.MAX_VALUE)ticking=true;}
        if(debug)drawDebug(bufferCanvas);
        float[] k=keystone,src={0f,0f,w,0f,w,h,0f,h},dst={k[0]*w,k[1]*h,k[2]*w,k[3]*h,k[4]*w,k[5]*h,k[6]*w,k[7]*h};warp.reset();
        if(warp.setPolyToPoly(src,0,dst,0,4))canvas.drawBitmap(buffer,warp,imagePaint);else canvas.drawBitmap(buffer,0,0,imagePaint);
        if(ticking)postInvalidateDelayed(160L);
    }

    private void drawText(Canvas c,int zone,String value){
        float[] f=textFrames[zone];RectF r=rect(f);float size=Math.max(10f,getHeight()*(textSizePct[zone]/100f));textPaint.setTextSize(size);textPaint.setColor(Color.WHITE);textPaint.setShadowLayer(Math.max(4f,size*.16f),0f,Math.max(1f,size*.05f),Color.BLACK);
        String align=textAlign[zone];float margin=Math.max(4f,r.width()*.035f),x;if("left".equals(align)){textPaint.setTextAlign(Paint.Align.LEFT);x=r.left+margin;}else if("right".equals(align)){textPaint.setTextAlign(Paint.Align.RIGHT);x=r.right-margin;}else{textPaint.setTextAlign(Paint.Align.CENTER);x=r.centerX();}
        String[] lines=value.split("\\n",-1);float line=size*1.18f,y=r.centerY()-(lines.length-1)*line*.5f-(textPaint.ascent()+textPaint.descent())*.5f,maxW=Math.max(1f,r.width()-margin*2f);
        for(String s:lines){c.drawText(ellipsize(s,maxW),x,y,textPaint);y+=line;if(y>r.bottom+line*.5f)break;}textPaint.clearShadowLayer();
    }

    private void drawImage(Canvas c,int zone,ImageState s){
        Bitmap b=s.bitmap;if(b==null||b.isRecycled())return;RectF box=rect(imageFrames[zone]);if(box.width()<1||box.height()<1)return;
        if("stretch".equals(s.fit)){c.drawBitmap(b,null,box,imagePaint);return;}
        float ia=b.getWidth()/(float)Math.max(1,b.getHeight()),ba=box.width()/Math.max(1f,box.height());
        if("cover".equals(s.fit)){
            Rect src;if(ia>ba){int sw=Math.max(1,Math.round(b.getHeight()*ba)),x=(b.getWidth()-sw)/2;src=new Rect(x,0,x+sw,b.getHeight());}else{int sh=Math.max(1,Math.round(b.getWidth()/ba)),y=(b.getHeight()-sh)/2;src=new Rect(0,y,b.getWidth(),y+sh);}c.drawBitmap(b,src,box,imagePaint);
        }else{
            float scale=Math.min(box.width()/b.getWidth(),box.height()/b.getHeight()),dw=b.getWidth()*scale,dh=b.getHeight()*scale;RectF dst=new RectF(box.centerX()-dw*.5f,box.centerY()-dh*.5f,box.centerX()+dw*.5f,box.centerY()+dh*.5f);c.drawBitmap(b,null,dst,imagePaint);
        }
    }

    private void drawDebug(Canvas c){
        debugPaint.setStyle(Paint.Style.STROKE);debugPaint.setStrokeWidth(Math.max(2f,getHeight()*.003f));debugPaint.setColor(0xffffb74d);for(int z=0;z<4;z++)c.drawRect(rect(textFrames[z]),debugPaint);debugPaint.setColor(0xffce93d8);for(int z=0;z<2;z++)c.drawRect(rect(imageFrames[z]),debugPaint);
        debugPaint.setStyle(Paint.Style.FILL);debugPaint.setTextSize(Math.max(14f,getHeight()*.023f));debugPaint.setColor(Color.WHITE);debugPaint.setTextAlign(Paint.Align.LEFT);String[] tn={"TEXT TOP","TEXT BOTTOM","TEXT LEFT","TEXT RIGHT"};for(int z=0;z<4;z++){RectF r=rect(textFrames[z]);c.drawText(tn[z],r.left+7,r.top+debugPaint.getTextSize()+4,debugPaint);}for(int z=0;z<2;z++){RectF r=rect(imageFrames[z]);c.drawText("IMAGE "+(z+1),r.left+7,r.top+debugPaint.getTextSize()+4,debugPaint);}
    }

    private RectF rect(float[] f){return new RectF(f[0]*getWidth(),f[1]*getHeight(),f[2]*getWidth(),f[3]*getHeight());}
    private boolean textAlive(TextState s,long now){if(s.text==null)return false;if(s.until!=Long.MAX_VALUE&&now>s.until){s.text=null;s.until=0;return false;}return true;}
    private boolean imageAlive(ImageState s,long now){if(s.bitmap==null)return false;if(s.until!=Long.MAX_VALUE&&now>s.until){Bitmap b=s.bitmap;s.bitmap=null;s.source=null;s.until=0;if(b!=null&&!b.isRecycled())b.recycle();return false;}return true;}
    private String ellipsize(String s,float maxW){if(textPaint.measureText(s)<=maxW)return s;String out=s;while(out.length()>1&&textPaint.measureText(out+"…")>maxW)out=out.substring(0,out.length()-1);return out+"…";}

    private static Bitmap decodeSource(String source)throws Exception{
        byte[] data;if(source.startsWith("data:")){int comma=source.indexOf(',');if(comma<0)throw new IllegalArgumentException("bad data URL");data=Base64.decode(source.substring(comma+1),Base64.DEFAULT);}else{URLConnection c=new URL(source).openConnection();c.setConnectTimeout(2500);c.setReadTimeout(5000);c.setUseCaches(true);try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream(65536)){byte[] buf=new byte[8192];int n,total=0;while((n=in.read(buf))>=0){if(n==0)continue;total+=n;if(total>5_000_000)throw new IllegalArgumentException("image too large");out.write(buf,0,n);}data=out.toByteArray();}}
        if(data.length>5_000_000)throw new IllegalArgumentException("image too large");Bitmap b=BitmapFactory.decodeByteArray(data,0,data.length);if(b==null)throw new IllegalArgumentException("unsupported image");int max=Math.max(b.getWidth(),b.getHeight());if(max>1920){float s=1920f/max;Bitmap scaled=Bitmap.createScaledBitmap(b,Math.max(1,Math.round(b.getWidth()*s)),Math.max(1,Math.round(b.getHeight()*s)),true);if(scaled!=b)b.recycle();b=scaled;}return b;
    }

    private static float[] sanitize(float[] f,float min){float l=clamp(f[0],0f,1f),t=clamp(f[1],0f,1f),r=clamp(f[2],0f,1f),b=clamp(f[3],0f,1f);if(r-l<min)r=Math.min(1f,l+min);if(b-t<min)b=Math.min(1f,t+min);if(r-l<min)l=Math.max(0f,r-min);if(b-t<min)t=Math.max(0f,b-min);return new float[]{l,t,r,b};}
    private static String cleanAlign(String s){String x=s==null?"center":s.trim().toLowerCase(Locale.US);return "left".equals(x)||"right".equals(x)?x:"center";}
    private static String cleanFit(String s){String x=s==null?"contain":s.trim().toLowerCase(Locale.US);return "cover".equals(x)||"stretch".equals(x)?x:"contain";}
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}

    public void shutdown(){imagePool.shutdownNow();for(int i=0;i<2;i++)clearImage(i);if(buffer!=null&&!buffer.isRecycled())buffer.recycle();buffer=null;bufferCanvas=null;}
}
