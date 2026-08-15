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
    private final Paint edgeHandlePaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private float[] corners={0.01f,0.01f, 0.99f,0.01f, 0.99f,0.99f, 0.01f,0.99f};
    // 0..3 = corners, 4=top edge, 5=right edge, 6=bottom edge, 7=left edge.
    private int active=-1;
    private float lastNx,lastNy;
    private Listener listener;

    public ProjectorKeystoneView(Context context){
        super(context);
        setBackgroundColor(Color.TRANSPARENT);
        linePaint.setColor(Color.WHITE);linePaint.setStyle(Paint.Style.STROKE);linePaint.setStrokeWidth(dp(3));
        gridPaint.setColor(0x88FFFFFF);gridPaint.setStyle(Paint.Style.STROKE);gridPaint.setStrokeWidth(dp(1));
        handlePaint.setColor(0xFFFFFFFF);handlePaint.setStyle(Paint.Style.FILL);
        edgeHandlePaint.setColor(0xDD66CCFF);edgeHandlePaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(Color.WHITE);textPaint.setTextSize(dp(15));textPaint.setShadowLayer(dp(5),0,dp(1),Color.BLACK);
    }

    public void setListener(Listener l){listener=l;}
    public void setCorners(float[] c){if(c==null||c.length!=8)return;corners=c.clone();invalidate();}
    public float[] getCorners(){return corners.clone();}

    @Override protected void onDraw(@NonNull Canvas canvas){
        super.onDraw(canvas);int w=getWidth(),h=getHeight();if(w<=0||h<=0)return;
        path.reset();path.moveTo(corners[0]*w,corners[1]*h);path.lineTo(corners[2]*w,corners[3]*h);path.lineTo(corners[4]*w,corners[5]*h);path.lineTo(corners[6]*w,corners[7]*h);path.close();
        canvas.drawPath(path,linePaint);

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

        // Mid-edge handles move the complete outer border while preserving its angle/shape.
        drawEdgeHandle(canvas,4,midX(0,1)*w,midY(0,1)*h,"TOP");
        drawEdgeHandle(canvas,5,midX(1,2)*w,midY(1,2)*h,"RIGHT");
        drawEdgeHandle(canvas,6,midX(3,2)*w,midY(3,2)*h,"BOTTOM");
        drawEdgeHandle(canvas,7,midX(0,3)*w,midY(0,3)*h,"LEFT");

        canvas.drawText("PROJECTOR OUTPUT · drag corners or blue edge handles",dp(18),h-dp(24),textPaint);
    }

    private void drawEdgeHandle(Canvas canvas,int id,float x,float y,String label){
        float r=dp(active==id?16:13);
        canvas.drawCircle(x,y,r,edgeHandlePaint);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(Color.BLACK);canvas.drawCircle(x,y,dp(4),p);
        textPaint.setTextAlign(Paint.Align.CENTER);
        float oy=(id==4)?dp(34):(id==6?-dp(22):dp(5));
        float ox=(id==5)?-dp(38):(id==7?dp(38):0);
        canvas.drawText(label,x+ox,y+oy,textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    @Override public boolean onTouchEvent(MotionEvent e){
        int w=getWidth(),h=getHeight();if(w<=0||h<=0)return true;
        float nx=e.getX()/w,ny=e.getY()/h;
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                active=nearestControl(e.getX(),e.getY(),w,h);lastNx=nx;lastNy=ny;return true;
            case MotionEvent.ACTION_MOVE:
                if(active>=0&&active<4){
                    corners[active*2]=clamp(nx,0.001f,0.999f);
                    corners[active*2+1]=clamp(ny,0.001f,0.999f);
                    enforceOrder();
                    notifyChanged();
                } else if(active>=4){
                    moveEdge(active,nx-lastNx,ny-lastNy);
                    notifyChanged();
                }
                lastNx=nx;lastNy=ny;return true;
            case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:active=-1;invalidate();return true;
            default:return true;
        }
    }

    private void moveEdge(int edge,float dx,float dy){
        int a,b;
        switch(edge){
            case 4:a=0;b=1;break; // top
            case 5:a=1;b=2;break; // right
            case 6:a=3;b=2;break; // bottom
            default:a=0;b=3;break; // left
        }
        float minDx=Math.max(-corners[a*2],-corners[b*2]);
        float maxDx=Math.min(1f-corners[a*2],1f-corners[b*2]);
        float minDy=Math.max(-corners[a*2+1],-corners[b*2+1]);
        float maxDy=Math.min(1f-corners[a*2+1],1f-corners[b*2+1]);
        dx=clamp(dx,minDx+0.001f,maxDx-0.001f);
        dy=clamp(dy,minDy+0.001f,maxDy-0.001f);
        corners[a*2]+=dx;corners[a*2+1]+=dy;
        corners[b*2]+=dx;corners[b*2+1]+=dy;
        enforceOrder();
    }

    private void notifyChanged(){invalidate();if(listener!=null)listener.onCornersChanged(corners.clone());}

    private int nearestControl(float x,float y,int w,int h){
        int best=0;float bd=Float.MAX_VALUE;
        for(int i=0;i<4;i++){
            float dx=x-corners[i*2]*w,dy=y-corners[i*2+1]*h,d=dx*dx+dy*dy;
            if(d<bd){bd=d;best=i;}
        }
        float[][] mids={
                {midX(0,1)*w,midY(0,1)*h},
                {midX(1,2)*w,midY(1,2)*h},
                {midX(3,2)*w,midY(3,2)*h},
                {midX(0,3)*w,midY(0,3)*h}
        };
        for(int i=0;i<4;i++){
            float dx=x-mids[i][0],dy=y-mids[i][1],d=dx*dx+dy*dy;
            // Slight preference for midpoint handles when the touch is near one.
            if(d<bd*1.15f){bd=d;best=4+i;}
        }
        return best;
    }

    private float midX(int a,int b){return(corners[a*2]+corners[b*2])*0.5f;}
    private float midY(int a,int b){return(corners[a*2+1]+corners[b*2+1])*0.5f;}

    private void enforceOrder(){
        float gap=0.015f;
        corners[0]=Math.min(corners[0],corners[2]-gap);corners[6]=Math.min(corners[6],corners[4]-gap);
        corners[1]=Math.min(corners[1],corners[7]-gap);corners[3]=Math.min(corners[3],corners[5]-gap);
        for(int i=0;i<8;i++)corners[i]=clamp(corners[i],0.001f,0.999f);
    }
    private float lerp(float a,float b,float t){return a+(b-a)*t;}
    private float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
    private float dp(float v){return v*getResources().getDisplayMetrics().density;}
}
