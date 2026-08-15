package com.bwa3d.ambiprojector;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;

/** Edit the projected TV mask plus four freely movable/resizable context frames. */
public final class ProjectionLayoutView extends View {
    public interface Listener { void onLayoutChanged(float[] tvRect, float[][] textFrames); }

    private final Paint tvPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint framePaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint=new Paint(Paint.ANTI_ALIAS_FLAG);

    // normalized l,t,r,b in the pre-keystone projection canvas
    private float[] tv={0.20f,0.27f,0.80f,0.73f};
    private float[][] frames={
            {0.24f,0.06f,0.76f,0.18f},
            {0.24f,0.82f,0.76f,0.94f},
            {0.03f,0.32f,0.18f,0.68f},
            {0.82f,0.32f,0.97f,0.68f}
    };

    private Listener listener;
    private int activeType=-1; // 0 TV, 1..4 text frames
    private int activeHandle=-1; // TV: 0 move,1 L,2 T,3 R,4 B,5 TL,6 TR,7 BR,8 BL. Text: 0 move,1 resize
    private float downX,downY;
    private float[] startRect=new float[4];

    public ProjectionLayoutView(Context c){
        super(c);setBackgroundColor(Color.TRANSPARENT);
        tvPaint.setStyle(Paint.Style.STROKE);tvPaint.setStrokeWidth(dp(4));tvPaint.setColor(0xFFFFFFFF);
        framePaint.setStyle(Paint.Style.STROKE);framePaint.setStrokeWidth(dp(3));framePaint.setColor(0xFF40C4FF);
        handlePaint.setStyle(Paint.Style.FILL);handlePaint.setColor(Color.WHITE);
        textPaint.setColor(Color.WHITE);textPaint.setTextSize(dp(14));textPaint.setShadowLayer(dp(4),0,dp(1),Color.BLACK);
    }

    public void setListener(Listener l){listener=l;}
    public void setTvRect(float[] r){if(r!=null&&r.length==4){tv=sanitize(r.clone(),0.08f);invalidate();}}
    public float[] getTvRect(){return tv.clone();}
    public void setTextFrames(float[][] f){
        if(f==null||f.length!=4)return;
        float[][] n=new float[4][4];
        for(int i=0;i<4;i++){if(f[i]==null||f[i].length!=4)return;n[i]=sanitize(f[i].clone(),0.06f);}frames=n;invalidate();
    }
    public float[][] getTextFrames(){float[][] n=new float[4][4];for(int i=0;i<4;i++)n[i]=frames[i].clone();return n;}

    @Override protected void onDraw(@NonNull Canvas c){
        super.onDraw(c);int w=getWidth(),h=getHeight();if(w<=0||h<=0)return;
        RectF tr=px(tv,w,h);c.drawRect(tr,tvPaint);
        drawTvHandles(c,tr);
        c.drawText("TV / INNER BORDER",tr.left+dp(8),tr.top-dp(8),textPaint);

        String[] names={"TEXT 1","TEXT 2","TEXT 3","TEXT 4"};
        for(int i=0;i<4;i++){
            RectF r=px(frames[i],w,h);c.drawRect(r,framePaint);
            c.drawText(names[i],r.left+dp(6),r.top+dp(18),textPaint);
            c.drawCircle(r.right,r.bottom,dp(9),handlePaint);
        }
        c.drawText("LAYOUT · drag TV borders/corners · drag text boxes · corner handle resizes text",dp(16),h-dp(22),textPaint);
    }

    private void drawTvHandles(Canvas c,RectF r){
        float cx=r.centerX(),cy=r.centerY(),rad=dp(9);
        c.drawCircle(r.left,r.top,rad,handlePaint);c.drawCircle(r.right,r.top,rad,handlePaint);
        c.drawCircle(r.right,r.bottom,rad,handlePaint);c.drawCircle(r.left,r.bottom,rad,handlePaint);
        c.drawCircle(cx,r.top,rad*0.8f,handlePaint);c.drawCircle(cx,r.bottom,rad*0.8f,handlePaint);
        c.drawCircle(r.left,cy,rad*0.8f,handlePaint);c.drawCircle(r.right,cy,rad*0.8f,handlePaint);
    }

    @Override public boolean onTouchEvent(MotionEvent e){
        int w=getWidth(),h=getHeight();if(w<=0||h<=0)return true;
        float x=e.getX(),y=e.getY();
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                downX=x;downY=y;
                Hit hit=hitTest(x,y,w,h);activeType=hit.type;activeHandle=hit.handle;
                if(activeType==0)System.arraycopy(tv,0,startRect,0,4);
                else if(activeType>0)System.arraycopy(frames[activeType-1],0,startRect,0,4);
                return true;
            case MotionEvent.ACTION_MOVE:
                if(activeType>=0){
                    float dx=(x-downX)/w,dy=(y-downY)/h;
                    if(activeType==0)editTv(dx,dy);
                    else editText(activeType-1,dx,dy);
                    invalidate();notifyChanged();
                }
                return true;
            case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:
                activeType=-1;activeHandle=-1;return true;
            default:return true;
        }
    }

    private void editTv(float dx,float dy){
        float[] r=startRect.clone();
        switch(activeHandle){
            case 1:r[0]+=dx;break; case 2:r[1]+=dy;break; case 3:r[2]+=dx;break; case 4:r[3]+=dy;break;
            case 5:r[0]+=dx;r[1]+=dy;break; case 6:r[2]+=dx;r[1]+=dy;break; case 7:r[2]+=dx;r[3]+=dy;break; case 8:r[0]+=dx;r[3]+=dy;break;
            default:{float ww=r[2]-r[0],hh=r[3]-r[1];r[0]+=dx;r[2]+=dx;r[1]+=dy;r[3]+=dy;keepInside(r,ww,hh);}
        }
        tv=sanitize(r,0.08f);
    }

    private void editText(int idx,float dx,float dy){
        float[] r=startRect.clone();
        if(activeHandle==1){r[2]+=dx;r[3]+=dy;}
        else{float ww=r[2]-r[0],hh=r[3]-r[1];r[0]+=dx;r[2]+=dx;r[1]+=dy;r[3]+=dy;keepInside(r,ww,hh);}
        frames[idx]=sanitize(r,0.06f);
    }

    private Hit hitTest(float x,float y,int w,int h){
        float tol=dp(26);
        RectF tr=px(tv,w,h);
        if(dist(x,y,tr.left,tr.top)<tol)return new Hit(0,5);
        if(dist(x,y,tr.right,tr.top)<tol)return new Hit(0,6);
        if(dist(x,y,tr.right,tr.bottom)<tol)return new Hit(0,7);
        if(dist(x,y,tr.left,tr.bottom)<tol)return new Hit(0,8);
        if(Math.abs(x-tr.left)<tol&&y>=tr.top-tol&&y<=tr.bottom+tol)return new Hit(0,1);
        if(Math.abs(y-tr.top)<tol&&x>=tr.left-tol&&x<=tr.right+tol)return new Hit(0,2);
        if(Math.abs(x-tr.right)<tol&&y>=tr.top-tol&&y<=tr.bottom+tol)return new Hit(0,3);
        if(Math.abs(y-tr.bottom)<tol&&x>=tr.left-tol&&x<=tr.right+tol)return new Hit(0,4);

        for(int i=0;i<4;i++){
            RectF r=px(frames[i],w,h);
            if(dist(x,y,r.right,r.bottom)<tol)return new Hit(i+1,1);
            if(r.contains(x,y))return new Hit(i+1,0);
        }
        if(tr.contains(x,y))return new Hit(0,0);
        return new Hit(-1,-1);
    }

    private void notifyChanged(){if(listener!=null)listener.onLayoutChanged(tv.clone(),getTextFrames());}
    private RectF px(float[] r,int w,int h){return new RectF(r[0]*w,r[1]*h,r[2]*w,r[3]*h);}
    private float dist(float x,float y,float a,float b){return(float)Math.hypot(x-a,y-b);}
    private void keepInside(float[] r,float ww,float hh){
        if(r[0]<0f){r[0]=0f;r[2]=ww;}if(r[2]>1f){r[2]=1f;r[0]=1f-ww;}
        if(r[1]<0f){r[1]=0f;r[3]=hh;}if(r[3]>1f){r[3]=1f;r[1]=1f-hh;}
    }
    private float[] sanitize(float[] r,float min){
        r[0]=clamp(r[0],0f,0.98f);r[1]=clamp(r[1],0f,0.98f);r[2]=clamp(r[2],0.02f,1f);r[3]=clamp(r[3],0.02f,1f);
        if(r[2]-r[0]<min)r[2]=Math.min(1f,r[0]+min);if(r[3]-r[1]<min)r[3]=Math.min(1f,r[1]+min);
        if(r[2]-r[0]<min)r[0]=Math.max(0f,r[2]-min);if(r[3]-r[1]<min)r[1]=Math.max(0f,r[3]-min);return r;
    }
    private float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
    private float dp(float v){return v*getResources().getDisplayMetrics().density;}
    private static final class Hit{final int type,handle;Hit(int t,int h){type=t;handle=h;}}
}
