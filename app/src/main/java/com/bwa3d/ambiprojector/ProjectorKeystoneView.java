package com.bwa3d.ambiprojector;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;

/** Full-screen interactive overlay for projector output perspective/keystone correction. */
public final class ProjectorKeystoneView extends View {
    public interface Listener { void onCornersChanged(float[] normalizedCorners); }

    private final Paint linePaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private float[] corners={0.01f,0.01f, 0.99f,0.01f, 0.99f,0.99f, 0.01f,0.99f};
    private int active=-1;
    private Listener listener;

    public ProjectorKeystoneView(Context context){
        super(context);
        setBackgroundColor(Color.TRANSPARENT);
        linePaint.setColor(Color.WHITE);linePaint.setStyle(Paint.Style.STROKE);linePaint.setStrokeWidth(dp(3));
        gridPaint.setColor(0x88FFFFFF);gridPaint.setStyle(Paint.Style.STROKE);gridPaint.setStrokeWidth(dp(1));
        handlePaint.setColor(0xFFFFFFFF);handlePaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(Color.WHITE);textPaint.setTextSize(dp(15));textPaint.setShadowLayer(dp(5),0,dp(1),Color.BLACK);
    }

    public void setListener(Listener l){listener=l;}
    public void setCorners(float[] c){if(c==null||c.length!=8)return;corners=c.clone();invalidate();}
    public float[] getCorners(){return corners.clone();}

    @Override protected void onDraw(@NonNull Canvas canvas){
        super.onDraw(canvas);int w=getWidth(),h=getHeight();if(w<=0||h<=0)return;
        path.reset();path.moveTo(corners[0]*w,corners[1]*h);path.lineTo(corners[2]*w,corners[3]*h);path.lineTo(corners[4]*w,corners[5]*h);path.lineTo(corners[6]*w,corners[7]*h);path.close();
        canvas.drawPath(path,linePaint);

        // Projective-looking guide grid formed by interpolating corresponding edge points.
        for(int i=1;i<4;i++){
            float t=i/4f;
            float lx=lerp(corners[0],corners[6],t)*w, ly=lerp(corners[1],corners[7],t)*h;
            float rx=lerp(corners[2],corners[4],t)*w, ry=lerp(corners[3],corners[5],t)*h;
            canvas.drawLine(lx,ly,rx,ry,gridPaint);
            float tx=lerp(corners[0],corners[2],t)*w, ty=lerp(corners[1],corners[3],t)*h;
            float bx=lerp(corners[6],corners[4],t)*w, by=lerp(corners[7],corners[5],t)*h;
            canvas.drawLine(tx,ty,bx,by,gridPaint);
        }

        String[] names={"TL","TR","BR","BL"};
        for(int i=0;i<4;i++){
            float x=corners[i*2]*w,y=corners[i*2+1]*h;
            canvas.drawCircle(x,y,dp(16),handlePaint);
            Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(Color.BLACK);canvas.drawCircle(x,y,dp(6),p);
            float ox=(i==0||i==3)?dp(20):-dp(42);float oy=(i<2)?dp(30):-dp(20);
            canvas.drawText(names[i],x+ox,y+oy,textPaint);
        }
        canvas.drawText("PROJECTOR KEYSTONE · drag all 4 corners",dp(18),h-dp(24),textPaint);
    }

    @Override public boolean onTouchEvent(MotionEvent e){
        int w=getWidth(),h=getHeight();if(w<=0||h<=0)return true;
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:active=nearest(e.getX(),e.getY(),w,h);return true;
            case MotionEvent.ACTION_MOVE:
                if(active>=0){corners[active*2]=clamp(e.getX()/w,0.001f,0.999f);corners[active*2+1]=clamp(e.getY()/h,0.001f,0.999f);enforceOrder();invalidate();if(listener!=null)listener.onCornersChanged(corners.clone());}
                return true;
            case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:active=-1;return true;
            default:return true;
        }
    }

    private int nearest(float x,float y,int w,int h){int best=0;float bd=Float.MAX_VALUE;for(int i=0;i<4;i++){float dx=x-corners[i*2]*w,dy=y-corners[i*2+1]*h,d=dx*dx+dy*dy;if(d<bd){bd=d;best=i;}}return best;}
    private void enforceOrder(){
        float gap=0.015f;
        corners[0]=Math.min(corners[0],corners[2]-gap);corners[6]=Math.min(corners[6],corners[4]-gap);
        corners[1]=Math.min(corners[1],corners[7]-gap);corners[3]=Math.min(corners[3],corners[5]-gap);
    }
    private float lerp(float a,float b,float t){return a+(b-a)*t;}
    private float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
    private float dp(float v){return v*getResources().getDisplayMetrics().density;}
}
