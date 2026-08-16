package com.bwa3d.ambiprojector;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * GPU Ambilight renderer.
 *
 * v0.26 moves the light field off Canvas(Bitmap) and into OpenGL ES 2.0. The first GPU pass
 * renders Color Cloud / Edge Gradient into an adaptive-resolution texture. The second pass
 * applies the outer projective keystone using a homography and upscales to the display.
 */
public final class AmbilightView extends GLSurfaceView {
    public interface GestureListener { void onSingleTap(); void onLongPress(); void onDoubleTap(); }
    public enum Zone { TOP, BOTTOM, LEFT, RIGHT }
    public enum ProjectionStyle { EDGE_GRADIENT, COLOR_CLOUD }

    private final GestureDetector gestures;
    private final GpuRenderer renderer;
    private GestureListener gestureListener;

    private volatile AmbilightState state=AmbilightState.black();
    private volatile boolean debug;
    private volatile ProjectionStyle projectionStyle=ProjectionStyle.COLOR_CLOUD;
    private volatile float outerFadeRatio=.16f;
    private volatile float[] keystoneCorners={0f,0f,1f,0f,1f,1f,0f,1f};
    private volatile float[] tvQuad={.20f,.27f,.80f,.27f,.80f,.73f,.20f,.73f};
    private volatile float[][] textFrames={{.24f,.06f,.76f,.18f},{.24f,.82f,.76f,.94f},{.03f,.32f,.18f,.68f},{.82f,.32f,.97f,.68f}};

    private volatile float cloudSpread=.42f,cloudRadius=.26f,cloudOpacity=.60f,cloudSaturation=1.32f,cloudBrightness=1.08f,cloudEdgePull=.62f,cloudSoftness=.72f,cornerBlend=.82f,cornerRadius=1.48f;
    private volatile float cloudDynamicAmount=.85f,cloudDynamicRadius=.65f,cloudDynamicStretch=.85f,cloudDynamicOpacity=.18f,cloudEnergyGamma=1.15f,cloudSaturationWeight=.60f,cloudLumaWeight=.40f;
    private volatile float rgbGainR=1f,rgbGainG=1f,rgbGainB=1f;
    private volatile int rgbOffsetR,rgbOffsetG,rgbOffsetB;
    private volatile float cloudRenderScale=.42f;

    public AmbilightView(Context context){
        super(context);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8,8,8,8,0,0);
        setPreserveEGLContextOnPause(true);
        renderer=new GpuRenderer();
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        setFocusable(false);
        setFocusableInTouchMode(false);
        gestures=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(@NonNull MotionEvent e){return true;}
            @Override public boolean onSingleTapConfirmed(@NonNull MotionEvent e){if(gestureListener!=null)gestureListener.onSingleTap();return true;}
            @Override public void onLongPress(@NonNull MotionEvent e){if(gestureListener!=null)gestureListener.onLongPress();}
            @Override public boolean onDoubleTap(@NonNull MotionEvent e){if(gestureListener!=null)gestureListener.onDoubleTap();return true;}
        });
    }

    public void setGestureListener(GestureListener listener){gestureListener=listener;}
    public void setState(AmbilightState s){if(s==null)return;state=s;requestRender();}
    public void setDebug(boolean enabled){debug=enabled;requestRender();}
    public boolean isDebug(){return debug;}
    public float getRenderFps(){return renderer.renderFps;}
    public boolean isHardwareCanvasAccelerated(){return renderer.gpuReady;}
    public boolean isGpuReady(){return renderer.gpuReady;}
    public String getBackendLabel(){if(renderer.gpuReady)return "GPU GLES2";String e=renderer.gpuError;return e==null||e.isEmpty()?"GPU starting":"GPU error";}
    public String getGpuError(){return renderer.gpuError;}
    public int getInternalWidth(){return renderer.sceneW;}
    public int getInternalHeight(){return renderer.sceneH;}
    public float getCloudRenderScale(){return cloudRenderScale;}

    public void setOuterFadeRatio(float v){outerFadeRatio=clamp(v,.02f,.42f);requestRender();}
    public float getOuterFadeRatio(){return outerFadeRatio;}
    public void setProjectionStyle(ProjectionStyle v){if(v!=null){projectionStyle=v;renderer.sceneDirty=true;requestRender();}}
    public ProjectionStyle getProjectionStyle(){return projectionStyle;}
    public void setCloudSpread(float v){cloudSpread=clamp(v,.05f,.90f);requestRender();}
    public void setCloudRadius(float v){cloudRadius=clamp(v,.08f,.50f);requestRender();}
    public void setCloudOpacity(float v){cloudOpacity=clamp(v,.05f,1f);requestRender();}
    public void setCloudSaturation(float v){cloudSaturation=clamp(v,.50f,2.50f);requestRender();}
    public void setCloudBrightness(float v){cloudBrightness=clamp(v,.40f,1.80f);requestRender();}
    public void setCloudEdgePull(float v){cloudEdgePull=clamp(v,0f,1f);requestRender();}
    public void setCloudSoftness(float v){cloudSoftness=clamp(v,0f,1f);requestRender();}
    public void setCornerBlend(float v){cornerBlend=clamp(v,0f,1f);requestRender();}
    public void setCornerRadius(float v){cornerRadius=clamp(v,.70f,2.40f);requestRender();}
    public void setCloudDynamicAmount(float v){cloudDynamicAmount=clamp(v,0f,1.50f);requestRender();}
    public void setCloudDynamicRadius(float v){cloudDynamicRadius=clamp(v,0f,1.50f);requestRender();}
    public void setCloudDynamicStretch(float v){cloudDynamicStretch=clamp(v,0f,2f);requestRender();}
    public void setCloudDynamicOpacity(float v){cloudDynamicOpacity=clamp(v,0f,.80f);requestRender();}
    public void setCloudEnergyGamma(float v){cloudEnergyGamma=clamp(v,.40f,2.50f);requestRender();}
    public void setCloudSaturationWeight(float v){cloudSaturationWeight=clamp(v,0f,1f);requestRender();}
    public void setCloudLumaWeight(float v){cloudLumaWeight=clamp(v,0f,1f);requestRender();}
    public void setCloudRenderScale(float v){cloudRenderScale=clamp(v,.20f,1f);renderer.sceneDirty=true;requestRender();}
    public void setRgbCalibration(float r,float g,float b,int ro,int go,int bo){rgbGainR=clamp(r,.40f,2f);rgbGainG=clamp(g,.40f,2f);rgbGainB=clamp(b,.40f,2f);rgbOffsetR=clampInt(ro,-80,80);rgbOffsetG=clampInt(go,-80,80);rgbOffsetB=clampInt(bo,-80,80);requestRender();}
    public void setKeystoneCorners(float[] c){if(c==null||c.length!=8)return;float[] n=c.clone();for(int i=0;i<8;i++)n[i]=clamp(n[i],0f,1f);keystoneCorners=n;requestRender();}
    public float[] getKeystoneCorners(){return keystoneCorners.clone();}
    public void setTvQuad(float[] q){if(q==null||q.length!=8)return;float[] n=q.clone();for(int i=0;i<8;i++)n[i]=clamp(n[i],0f,1f);tvQuad=n;requestRender();}
    public float[] getTvQuad(){return tvQuad.clone();}
    public void setTvRect(float[] r){if(r==null||r.length!=4)return;float[] x=sanitizeRect(r.clone(),.08f);setTvQuad(new float[]{x[0],x[1],x[2],x[1],x[2],x[3],x[0],x[3]});}
    public void setTextFrames(float[][] f){if(f==null||f.length!=4)return;float[][] n=new float[4][4];for(int i=0;i<4;i++){if(f[i]==null||f[i].length!=4)return;n[i]=sanitizeRect(f[i].clone(),.04f);}textFrames=n;}
    public float[][] getTextFrames(){float[][] n=new float[4][4];for(int i=0;i<4;i++)n[i]=textFrames[i].clone();return n;}

    // Legacy context methods remain source-compatible; v0.22+ content lives in ContentOverlayView.
    public void showContextOverlay(String text,long durationMs){}
    public void showContextOverlay(Zone zone,String text,long durationMs){}
    public void clearContextOverlay(Zone zone){}
    public void clearAllContextOverlays(){}
    public void showContextDemo(long durationMs){}

    @Override public boolean onTouchEvent(MotionEvent e){return gestures.onTouchEvent(e)||super.onTouchEvent(e);}

    private final class GpuRenderer implements Renderer {
        private static final int EDGE_TEXELS=100;
        private static final int TOP_COUNT=32,RIGHT_COUNT=18,BOTTOM_COUNT=32,LEFT_COUNT=18;
        private static final double MIN_SCENE_PIXELS=170000.0;
        private static final double MAX_SCENE_PIXELS=1350000.0;

        private final FloatBuffer quadPos=buffer(new float[]{-1f,-1f, 1f,-1f, -1f,1f, 1f,1f});
        // Top-left coordinate convention: bottom GL vertices carry y=1, top GL vertices y=0.
        private final FloatBuffer quadUv=buffer(new float[]{0f,1f, 1f,1f, 0f,0f, 1f,0f});
        private final ByteBuffer edgePixels=ByteBuffer.allocateDirect(EDGE_TEXELS*4).order(ByteOrder.nativeOrder());

        private int fieldProgram,warpProgram,edgeTexture,sceneTexture,sceneFbo;
        private int viewW,viewH;
        private volatile int sceneW,sceneH;
        private volatile float renderFps;
        private volatile boolean gpuReady;
        private volatile String gpuError="";
        private volatile boolean sceneDirty=true;
        private long fpsStarted;
        private int fpsFrames;

        @Override public void onSurfaceCreated(GL10 gl,EGLConfig config){
            try{
                fieldProgram=program(VERTEX,FIELD_FRAGMENT);
                warpProgram=program(VERTEX,WARP_FRAGMENT);
                if(fieldProgram==0||warpProgram==0)throw new IllegalStateException(gpuError.isEmpty()?"shader link failed":gpuError);
                int[] ids=new int[1];
                GLES20.glGenTextures(1,ids,0);edgeTexture=ids[0];GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,edgeTexture);textureParams(GLES20.GL_NEAREST);GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,EDGE_TEXELS,1,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);
                GLES20.glGenTextures(1,ids,0);sceneTexture=ids[0];GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,sceneTexture);textureParams(GLES20.GL_LINEAR);
                GLES20.glGenFramebuffers(1,ids,0);sceneFbo=ids[0];
                GLES20.glDisable(GLES20.GL_DEPTH_TEST);GLES20.glDisable(GLES20.GL_CULL_FACE);GLES20.glDisable(GLES20.GL_BLEND);
                gpuReady=true;gpuError="";sceneDirty=true;
            }catch(Throwable t){gpuReady=false;gpuError=t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage());}
        }

        @Override public void onSurfaceChanged(GL10 gl,int width,int height){viewW=Math.max(1,width);viewH=Math.max(1,height);sceneDirty=true;}

        @Override public void onDrawFrame(GL10 gl){
            long now=System.nanoTime();trackFps(now);
            if(!gpuReady){GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);GLES20.glViewport(0,0,Math.max(1,viewW),Math.max(1,viewH));GLES20.glClearColor(0,0,0,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);return;}
            ensureSceneTarget();
            uploadEdges(state);
            renderField();
            renderWarp();
        }

        private void renderField(){
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,sceneFbo);GLES20.glViewport(0,0,sceneW,sceneH);GLES20.glClearColor(0,0,0,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);GLES20.glUseProgram(fieldProgram);
            bindGeometry(fieldProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,edgeTexture);ui(fieldProgram,"uEdges",0);
            float[] q=tvQuad;
            u2(fieldProgram,"uTv0",q[0],q[1]);u2(fieldProgram,"uTv1",q[2],q[3]);u2(fieldProgram,"uTv2",q[4],q[5]);u2(fieldProgram,"uTv3",q[6],q[7]);
            uf(fieldProgram,"uAspect",sceneW/(float)Math.max(1,sceneH));
            ui(fieldProgram,"uStyle",projectionStyle==ProjectionStyle.EDGE_GRADIENT?1:0);
            float detail=detail01();int hc=3+Math.round(detail*5f),vc=2+Math.round(detail*4f);ui(fieldProgram,"uHCount",hc);ui(fieldProgram,"uVCount",vc);
            uf(fieldProgram,"uBrightness",cloudBrightness);uf(fieldProgram,"uSaturation",cloudSaturation);uf(fieldProgram,"uSpread",cloudSpread);uf(fieldProgram,"uRadius",cloudRadius);uf(fieldProgram,"uOpacity",cloudOpacity);uf(fieldProgram,"uEdgePull",cloudEdgePull);uf(fieldProgram,"uSoftness",cloudSoftness);uf(fieldProgram,"uCornerBlend",cornerBlend);uf(fieldProgram,"uCornerRadius",cornerRadius);
            uf(fieldProgram,"uDynamic",cloudDynamicAmount);uf(fieldProgram,"uDynRadius",cloudDynamicRadius);uf(fieldProgram,"uDynStretch",cloudDynamicStretch);uf(fieldProgram,"uDynOpacity",cloudDynamicOpacity);uf(fieldProgram,"uEnergyGamma",cloudEnergyGamma);uf(fieldProgram,"uSatWeight",cloudSaturationWeight);uf(fieldProgram,"uLumaWeight",cloudLumaWeight);uf(fieldProgram,"uOuterFade",outerFadeRatio);
            u3(fieldProgram,"uGain",rgbGainR,rgbGainG,rgbGainB);u3(fieldProgram,"uOffset",rgbOffsetR/255f,rgbOffsetG/255f,rgbOffsetB/255f);uf(fieldProgram,"uDebug",debug?1f:0f);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
        }

        private void renderWarp(){
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);GLES20.glViewport(0,0,viewW,viewH);GLES20.glClearColor(0,0,0,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);GLES20.glUseProgram(warpProgram);bindGeometry(warpProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,sceneTexture);ui(warpProgram,"uScene",0);
            float[] k=keystoneCorners;float[] src={k[0],k[1],k[2],k[3],k[4],k[5],k[6],k[7]},dst={0,0,1,0,1,1,0,1};float[] h=homography(src,dst);GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(warpProgram,"uInvH"),1,false,h,0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
        }

        private void ensureSceneTarget(){
            float d=detail01();double budget=MIN_SCENE_PIXELS+(MAX_SCENE_PIXELS-MIN_SCENE_PIXELS)*d*d;if(projectionStyle==ProjectionStyle.EDGE_GRADIENT)budget=Math.max(budget,420000.0);float s=(float)Math.min(1.0,Math.sqrt(budget/Math.max(1.0,(double)viewW*viewH)));int w=Math.max(160,Math.min(viewW,Math.round(viewW*s))),h=Math.max(90,Math.min(viewH,Math.round(viewH*s)));if(!sceneDirty&&w==sceneW&&h==sceneH)return;sceneW=w;sceneH=h;sceneDirty=false;
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,sceneTexture);GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,sceneW,sceneH,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,sceneFbo);GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,sceneTexture,0);int status=GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);if(status!=GLES20.GL_FRAMEBUFFER_COMPLETE){gpuReady=false;gpuError="FBO 0x"+Integer.toHexString(status);}GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);
        }

        private void uploadEdges(AmbilightState s){
            edgePixels.clear();putResampled(edgePixels,s==null?null:s.top,TOP_COUNT);putResampled(edgePixels,s==null?null:s.right,RIGHT_COUNT);putResampled(edgePixels,s==null?null:s.bottom,BOTTOM_COUNT);putResampled(edgePixels,s==null?null:s.left,LEFT_COUNT);edgePixels.flip();GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,edgeTexture);GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D,0,0,0,EDGE_TEXELS,1,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,edgePixels);
        }

        private void putResampled(ByteBuffer out,int[] src,int count){for(int i=0;i<count;i++){int c=0xff000000;if(src!=null&&src.length>0){float t=count<=1?0f:i/(float)(count-1),p=t*(src.length-1);int a=(int)Math.floor(p),b=Math.min(src.length-1,a+1);float f=p-a;c=mixRgb(src[a],src[b],f);}out.put((byte)((c>>16)&255));out.put((byte)((c>>8)&255));out.put((byte)(c&255));out.put((byte)255);}}
        private int mixRgb(int a,int b,float t){int r=Math.round(((a>>16)&255)*(1f-t)+((b>>16)&255)*t),g=Math.round(((a>>8)&255)*(1f-t)+((b>>8)&255)*t),bl=Math.round((a&255)*(1f-t)+(b&255)*t);return 0xff000000|(r<<16)|(g<<8)|bl;}

        private void bindGeometry(int p){int ap=GLES20.glGetAttribLocation(p,"aPos"),au=GLES20.glGetAttribLocation(p,"aUv");quadPos.position(0);quadUv.position(0);GLES20.glEnableVertexAttribArray(ap);GLES20.glVertexAttribPointer(ap,2,GLES20.GL_FLOAT,false,0,quadPos);GLES20.glEnableVertexAttribArray(au);GLES20.glVertexAttribPointer(au,2,GLES20.GL_FLOAT,false,0,quadUv);}
        private void textureParams(int filter){GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,filter);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,filter);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);}
        private int program(String vs,String fs){int v=shader(GLES20.GL_VERTEX_SHADER,vs),f=shader(GLES20.GL_FRAGMENT_SHADER,fs);if(v==0||f==0)return 0;int p=GLES20.glCreateProgram();GLES20.glAttachShader(p,v);GLES20.glAttachShader(p,f);GLES20.glLinkProgram(p);int[] ok=new int[1];GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0);if(ok[0]==0){gpuError="link: "+GLES20.glGetProgramInfoLog(p);GLES20.glDeleteProgram(p);p=0;}GLES20.glDeleteShader(v);GLES20.glDeleteShader(f);return p;}
        private int shader(int type,String src){int s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,src);GLES20.glCompileShader(s);int[] ok=new int[1];GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);if(ok[0]==0){gpuError=(type==GLES20.GL_VERTEX_SHADER?"vertex: ":"fragment: ")+GLES20.glGetShaderInfoLog(s);GLES20.glDeleteShader(s);return 0;}return s;}
        private void trackFps(long now){if(fpsStarted==0L)fpsStarted=now;fpsFrames++;long e=now-fpsStarted;if(e>=500_000_000L){renderFps=(float)(fpsFrames*1_000_000_000.0/Math.max(1L,e));fpsFrames=0;fpsStarted=now;}}
        private float detail01(){return clamp((cloudRenderScale-.20f)/.80f,0f,1f);}
        private void uf(int p,String n,float v){GLES20.glUniform1f(GLES20.glGetUniformLocation(p,n),v);}private void ui(int p,String n,int v){GLES20.glUniform1i(GLES20.glGetUniformLocation(p,n),v);}private void u2(int p,String n,float a,float b){GLES20.glUniform2f(GLES20.glGetUniformLocation(p,n),a,b);}private void u3(int p,String n,float a,float b,float c){GLES20.glUniform3f(GLES20.glGetUniformLocation(p,n),a,b,c);}
    }

    private static FloatBuffer buffer(float[] a){FloatBuffer b=ByteBuffer.allocateDirect(a.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();b.put(a).position(0);return b;}

    /** Returns column-major 3x3 H mapping the four src points to dst points. */
    private static float[] homography(float[] src,float[] dst){
        double[][] a=new double[8][9];for(int i=0;i<4;i++){double x=src[i*2],y=src[i*2+1],u=dst[i*2],v=dst[i*2+1];int r=i*2;a[r][0]=x;a[r][1]=y;a[r][2]=1;a[r][6]=-u*x;a[r][7]=-u*y;a[r][8]=u;a[r+1][3]=x;a[r+1][4]=y;a[r+1][5]=1;a[r+1][6]=-v*x;a[r+1][7]=-v*y;a[r+1][8]=v;}
        for(int c=0;c<8;c++){int pivot=c;for(int r=c+1;r<8;r++)if(Math.abs(a[r][c])>Math.abs(a[pivot][c]))pivot=r;if(Math.abs(a[pivot][c])<1e-9)return identity3();double[] tmp=a[c];a[c]=a[pivot];a[pivot]=tmp;double d=a[c][c];for(int j=c;j<9;j++)a[c][j]/=d;for(int r=0;r<8;r++){if(r==c)continue;double f=a[r][c];for(int j=c;j<9;j++)a[r][j]-=f*a[c][j];}}
        float h0=(float)a[0][8],h1=(float)a[1][8],h2=(float)a[2][8],h3=(float)a[3][8],h4=(float)a[4][8],h5=(float)a[5][8],h6=(float)a[6][8],h7=(float)a[7][8];return new float[]{h0,h3,h6,h1,h4,h7,h2,h5,1f};
    }
    private static float[] identity3(){return new float[]{1,0,0,0,1,0,0,0,1};}
    private static float[] sanitizeRect(float[] r,float min){r[0]=clamp(r[0],0f,.98f);r[1]=clamp(r[1],0f,.98f);r[2]=clamp(r[2],.02f,1f);r[3]=clamp(r[3],.02f,1f);if(r[2]-r[0]<min)r[2]=Math.min(1f,r[0]+min);if(r[3]-r[1]<min)r[3]=Math.min(1f,r[1]+min);if(r[2]-r[0]<min)r[0]=Math.max(0f,r[2]-min);if(r[3]-r[1]<min)r[1]=Math.max(0f,r[3]-min);return r;}
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
    private static int clampInt(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}

    private static final String VERTEX=
            "attribute vec2 aPos;\n"+
            "attribute vec2 aUv;\n"+
            "varying vec2 vUv;\n"+
            "void main(){ gl_Position=vec4(aPos,0.0,1.0); vUv=aUv; }\n";

    private static final String WARP_FRAGMENT=
            "precision mediump float;\n"+
            "varying vec2 vUv; uniform sampler2D uScene; uniform mat3 uInvH;\n"+
            "void main(){ vec3 h=uInvH*vec3(vUv,1.0); if(abs(h.z)<0.00001){gl_FragColor=vec4(0,0,0,1);return;} vec2 p=h.xy/h.z; if(p.x<0.0||p.x>1.0||p.y<0.0||p.y>1.0){gl_FragColor=vec4(0,0,0,1);return;} gl_FragColor=texture2D(uScene,vec2(p.x,1.0-p.y)); }\n";

    private static final String FIELD_FRAGMENT=
            "precision mediump float;\n"+
            "varying vec2 vUv; uniform sampler2D uEdges; uniform vec2 uTv0,uTv1,uTv2,uTv3; uniform float uAspect; uniform int uStyle,uHCount,uVCount;\n"+
            "uniform float uBrightness,uSaturation,uSpread,uRadius,uOpacity,uEdgePull,uSoftness,uCornerBlend,uCornerRadius;\n"+
            "uniform float uDynamic,uDynRadius,uDynStretch,uDynOpacity,uEnergyGamma,uSatWeight,uLumaWeight,uOuterFade,uDebug; uniform vec3 uGain,uOffset;\n"+
            "float cross2(vec2 a,vec2 b){return a.x*b.y-a.y*b.x;}\n"+
            "bool insideQ(vec2 p){float c0=cross2(uTv1-uTv0,p-uTv0),c1=cross2(uTv2-uTv1,p-uTv1),c2=cross2(uTv3-uTv2,p-uTv2),c3=cross2(uTv0-uTv3,p-uTv3);return (c0>=0.0&&c1>=0.0&&c2>=0.0&&c3>=0.0)||(c0<=0.0&&c1<=0.0&&c2<=0.0&&c3<=0.0);}\n"+
            "float segT(vec2 p,vec2 a,vec2 b){vec2 d=b-a;return clamp(dot(p-a,d)/max(dot(d,d),0.000001),0.0,1.0);}\n"+
            "float segD(vec2 p,vec2 a,vec2 b){float t=segT(p,a,b);vec2 d=p-mix(a,b,t);return length(vec2(d.x*uAspect,d.y));}\n"+
            "float rayBox(vec2 p,vec2 d){float tx=999.0,ty=999.0;if(d.x>0.0001)tx=(1.0-p.x)/d.x;else if(d.x<-0.0001)tx=(0.0-p.x)/d.x;if(d.y>0.0001)ty=(1.0-p.y)/d.y;else if(d.y<-0.0001)ty=(0.0-p.y)/d.y;return max(0.001,min(tx,ty));}\n"+
            "vec3 rawEdge(float off,float cnt,float t){float idx=off+clamp(t,0.0,1.0)*(cnt-1.0)+0.5;return texture2D(uEdges,vec2(idx/100.0,0.5)).rgb;}\n"+
            "vec3 calibrated(vec3 c){return clamp(c*uGain+uOffset,0.0,1.0);}\n"+
            "vec3 boosted(vec3 c){c=calibrated(c);float l=dot(c,vec3(0.2126,0.7152,0.0722));c=mix(vec3(l),c,uSaturation);return clamp(c*uBrightness,0.0,1.0);}\n"+
            "float energy(vec3 c){c=calibrated(c);float mx=max(c.r,max(c.g,c.b)),mn=min(c.r,min(c.g,c.b));float sat=mx<=0.0001?0.0:(mx-mn)/mx;float lum=dot(c,vec3(0.2126,0.7152,0.0722));float den=max(0.0001,uSatWeight+uLumaWeight);return pow(clamp((sat*uSatWeight+lum*uLumaWeight)/den,0.0,1.0),uEnergyGamma);}\n"+
            "void blob(vec2 p,vec2 a,vec2 b,vec2 n,float off,float cnt,float t,float base,float horizontal,float endpoint,inout vec3 acc,inout float sum){vec2 ep=mix(a,b,t);vec3 raw=rawEdge(off,cnt,t);float e=energy(raw);float ar=uDynamic*uDynRadius,as=uDynamic*uDynStretch,ao=uDynamic*uDynOpacity;float rs=mix(max(0.35,1.0-0.55*ar),1.0+ar,e);float st=mix(max(0.35,1.0-0.45*as),1.0+1.35*as,e);float os=mix(max(0.55,1.0-0.45*ao),1.0+0.55*ao,e);float avail=rayBox(ep,n);float pull=0.82-0.67*uEdgePull;float reach=(e-0.35)*0.10*uDynamic*uDynStretch;vec2 center=ep+n*avail*clamp(pull+reach,0.04,0.95);float cornerScale=mix(1.0,mix(1.0,uCornerRadius,uCornerBlend*0.45),endpoint);float radius=uRadius*(0.76+uSpread*0.90)*rs*cornerScale;vec2 d=vec2((p.x-center.x)*uAspect,p.y-center.y);if(horizontal>0.5)d.y/=max(0.35,st);else d.x/=max(0.35,st);float dist=length(d);float inner=radius*mix(0.45,0.08,uSoftness);float w=(1.0-smoothstep(inner,max(inner+0.0001,radius),dist))*uOpacity*base*os;if(w>0.0001){acc+=boosted(raw)*w;sum+=w;}}\n"+
            "void edge(vec2 p,vec2 a,vec2 b,vec2 n,float off,float cnt,inout vec3 acc,inout float sum){float t=segT(p,a,b);vec2 ep=mix(a,b,t);float sd=dot(p-ep,n);if(sd<=0.0)return;float avail=rayBox(ep,n);float w=(1.0-smoothstep(0.0,max(0.001,avail),sd))*uOpacity;if(w>0.0001){acc+=boosted(rawEdge(off,cnt,t))*w;sum+=w;}}\n"+
            "void main(){vec2 p=vUv;vec2 et=uTv1-uTv0,er=uTv2-uTv1,eb=uTv2-uTv3,el=uTv3-uTv0;vec2 nt=normalize(vec2(et.y,-et.x)),nr=normalize(vec2(er.y,-er.x)),nb=normalize(vec2(-eb.y,eb.x)),nl=normalize(vec2(-el.y,el.x));bool inside=insideQ(p);vec3 acc=vec3(0.0);float sum=0.0;if(!inside){if(uStyle==0){for(int i=0;i<8;i++){if(i<uHCount){float t=float(i)/max(1.0,float(uHCount-1));float ep=(i==0||i==uHCount-1)?1.0:0.0;blob(p,uTv0,uTv1,nt,0.0,32.0,t,0.82,1.0,ep,acc,sum);blob(p,uTv3,uTv2,nb,50.0,32.0,t,0.82,1.0,ep,acc,sum);}}for(int i=0;i<6;i++){if(i<uVCount){float t=float(i)/max(1.0,float(uVCount-1));float ep=(i==0||i==uVCount-1)?1.0:0.0;blob(p,uTv0,uTv3,nl,82.0,18.0,t,0.75,0.0,ep,acc,sum);blob(p,uTv1,uTv2,nr,32.0,18.0,t,0.75,0.0,ep,acc,sum);}}}else{edge(p,uTv0,uTv1,nt,0.0,32.0,acc,sum);edge(p,uTv1,uTv2,nr,32.0,18.0,acc,sum);edge(p,uTv3,uTv2,nb,50.0,32.0,acc,sum);edge(p,uTv0,uTv3,nl,82.0,18.0,acc,sum);}}vec3 col=vec3(0.0);if(sum>0.0001){col=acc/sum;float light=1.0-exp(-sum*1.35);col*=light;}float border=min(min(p.x,1.0-p.x),min(p.y,1.0-p.y));float fade=smoothstep(0.0,max(0.001,uOuterFade),border);col*=fade;if(inside)col=vec3(0.0);if(uDebug>0.5){float d=min(min(segD(p,uTv0,uTv1),segD(p,uTv1,uTv2)),min(segD(p,uTv2,uTv3),segD(p,uTv3,uTv0)));if(d<0.0045)col=mix(col,vec3(0.30,1.0,0.55),0.92);}gl_FragColor=vec4(clamp(col,0.0,1.0),1.0);}\n";
}
