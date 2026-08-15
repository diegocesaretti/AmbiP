package com.bwa3d.ambiprojector;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExposureState;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends ComponentActivity {
    private static final String TAG="AmbiProjector";
    private static final int CAMERA_REQUEST=1001;
    private static final String PREFS="ambi_projector_settings";
    private static final long CAMERA_STALL_MS=3500L;

    private FrameLayout root,calibrationContainer;
    private AmbilightView ambilightView;
    private PreviewView previewView;
    private ScreenCalibrationView calibrationView;
    private ProjectorKeystoneView projectorKeystoneView;
    private ProjectionLayoutView projectionLayoutView;
    private LinearLayout calibrationToolbar,projectorToolbar,layoutToolbar,settingsPanel;
    private TextView exposureLabel,zoomLabel,cameraStatusLabel;
    private SeekBar exposureSeek,zoomSeek;
    private FrameAnalyzer analyzer;
    private ExecutorService cameraExecutor;
    private Camera boundCamera;
    private ProcessCameraProvider cameraProvider;
    private SharedPreferences prefs;
    private final Handler mainHandler=new Handler(Looper.getMainLooper());
    private boolean previewVisible=false,calibrating=false,projectorCalibrating=false,layoutCalibrating=false,settingsVisible=false;
    private boolean cameraBinding=false,destroyed=false;
    private float calibrationZoom=1.8f;
    private int requestedExposureIndex=0;
    private long lastFrameAt=0L,lastRestartAt=0L;

    private final Runnable cameraWatchdog=new Runnable(){
        @Override public void run(){
            if(destroyed)return;long now=SystemClock.elapsedRealtime();
            boolean hasPermission=ContextCompat.checkSelfPermission(MainActivity.this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED;
            if(hasPermission&&getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)){
                if(lastFrameAt>0L&&now-lastFrameAt>CAMERA_STALL_MS&&now-lastRestartAt>CAMERA_STALL_MS){setCameraStatus("STALLED · restarting",0xFFFFB74D);restartCamera("watchdog");}
                else if(lastFrameAt==0L&&now-lastRestartAt>CAMERA_STALL_MS*2){setCameraStatus("NO FRAMES · restarting",0xFFFFB74D);restartCamera("no first frame");}
            }
            mainHandler.postDelayed(this,1500L);
        }
    };

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        try{
            prefs=getSharedPreferences(PREFS,MODE_PRIVATE);cameraExecutor=Executors.newSingleThreadExecutor();buildUi();
            analyzer=new FrameAnalyzer(s->{lastFrameAt=SystemClock.elapsedRealtime();runOnUiThread(()->{ambilightView.setState(s);setCameraStatus(String.format(Locale.US,"LIVE · %.1f fps",s.fps),0xFF81C784);});});
            restoreSettings();
            analyzer.setDetectionListener((corners,confidence)->runOnUiThread(()->{calibrationView.setCorners(corners);Toast.makeText(this,String.format(Locale.US,"Auto detect %.0f%% confidence",confidence*100f),Toast.LENGTH_SHORT).show();}));
            calibrationView.setListener(c->analyzer.setCorners(c));
            projectorKeystoneView.setListener(c->ambilightView.setKeystoneCorners(c));
            projectionLayoutView.setListener((tv,frames)->{ambilightView.setTvRect(tv);ambilightView.setTextFrames(frames);});
            if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)startCameraSafely();else ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.CAMERA},CAMERA_REQUEST);
            mainHandler.postDelayed(cameraWatchdog,2500L);
        }catch(Throwable t){showFatalError("Startup",t);}
    }

    private void buildUi(){
        root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);ambilightView=new AmbilightView(this);root.addView(ambilightView,new FrameLayout.LayoutParams(-1,-1));
        calibrationContainer=new FrameLayout(this);calibrationContainer.setBackgroundColor(Color.BLACK);
        int sw=getResources().getDisplayMetrics().widthPixels,sh=getResources().getDisplayMetrics().heightPixels;int cw=Math.min(sw,Math.round(sh*4f/3f)),ch=Math.min(sh,Math.round(cw*3f/4f));
        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(cw,ch);cp.gravity=Gravity.CENTER;root.addView(calibrationContainer,cp);
        previewView=new PreviewView(this);previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);calibrationContainer.addView(previewView,new FrameLayout.LayoutParams(-1,-1));
        calibrationView=new ScreenCalibrationView(this);calibrationView.setVisibility(View.GONE);calibrationContainer.addView(calibrationView,new FrameLayout.LayoutParams(-1,-1));calibrationContainer.setVisibility(View.INVISIBLE);

        projectorKeystoneView=new ProjectorKeystoneView(this);projectorKeystoneView.setVisibility(View.GONE);root.addView(projectorKeystoneView,new FrameLayout.LayoutParams(-1,-1));
        projectionLayoutView=new ProjectionLayoutView(this);projectionLayoutView.setVisibility(View.GONE);root.addView(projectionLayoutView,new FrameLayout.LayoutParams(-1,-1));

        buildCalibrationToolbar();buildProjectorToolbar();buildLayoutToolbar();buildSettingsPanel();
        ambilightView.setGestureListener(new AmbilightView.GestureListener(){
            @Override public void onSingleTap(){if(!anyProjectionCalibration())toggleSettings();}
            @Override public void onLongPress(){if(!projectorCalibrating&&!layoutCalibrating)enterCalibration();}
            @Override public void onDoubleTap(){if(!anyProjectionCalibration())ambilightView.showContextDemo(6000);}
        });setContentView(root);
    }

    private boolean anyProjectionCalibration(){return calibrating||projectorCalibrating||layoutCalibrating;}

    private void buildCalibrationToolbar(){
        calibrationToolbar=new LinearLayout(this);calibrationToolbar.setOrientation(LinearLayout.HORIZONTAL);calibrationToolbar.setGravity(Gravity.CENTER);calibrationToolbar.setPadding(dp(6),dp(6),dp(6),dp(6));calibrationToolbar.setBackgroundColor(0xBB000000);
        calibrationToolbar.addView(makeButton("− ZOOM",v->changeZoom(-0.25f)));calibrationToolbar.addView(makeButton("AUTO",v->{analyzer.requestAutoDetect();Toast.makeText(this,"Looking for TV borders…",Toast.LENGTH_SHORT).show();}));calibrationToolbar.addView(makeButton("ZOOM +",v->changeZoom(0.25f)));
        calibrationToolbar.addView(makeButton("RESET",v->{float[] c={0.10f,0.14f,0.90f,0.14f,0.90f,0.86f,0.10f,0.86f};calibrationView.setCorners(c);analyzer.setCorners(c);}));calibrationToolbar.addView(makeButton("RESTART CAM",v->restartCamera("manual calibration")));calibrationToolbar.addView(makeButton("DONE",v->exitCalibration()));
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-2,-2);p.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;p.topMargin=dp(8);calibrationToolbar.setVisibility(View.GONE);root.addView(calibrationToolbar,p);
    }

    private void buildProjectorToolbar(){
        projectorToolbar=new LinearLayout(this);projectorToolbar.setOrientation(LinearLayout.HORIZONTAL);projectorToolbar.setGravity(Gravity.CENTER);projectorToolbar.setPadding(dp(8),dp(8),dp(8),dp(8));projectorToolbar.setBackgroundColor(0xCC000000);
        projectorToolbar.addView(makeButton("RESET OUTER",v->{float[] c=defaultKeystone();projectorKeystoneView.setCorners(c);ambilightView.setKeystoneCorners(c);}));projectorToolbar.addView(makeButton("DONE",v->exitProjectorCalibration()));
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-2,-2);p.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;p.topMargin=dp(8);projectorToolbar.setVisibility(View.GONE);root.addView(projectorToolbar,p);
    }

    private void buildLayoutToolbar(){
        layoutToolbar=new LinearLayout(this);layoutToolbar.setOrientation(LinearLayout.HORIZONTAL);layoutToolbar.setGravity(Gravity.CENTER);layoutToolbar.setPadding(dp(8),dp(8),dp(8),dp(8));layoutToolbar.setBackgroundColor(0xCC000000);
        layoutToolbar.addView(makeButton("RESET INNER",v->{float[] tv=defaultTvRect();projectionLayoutView.setTvRect(tv);ambilightView.setTvRect(tv);}));
        layoutToolbar.addView(makeButton("RESET TEXT",v->{float[][] f=defaultTextFrames();projectionLayoutView.setTextFrames(f);ambilightView.setTextFrames(f);}));
        layoutToolbar.addView(makeButton("TEXT DEMO",v->ambilightView.showContextDemo(15000)));layoutToolbar.addView(makeButton("DONE",v->exitLayoutCalibration()));
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-2,-2);p.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;p.topMargin=dp(8);layoutToolbar.setVisibility(View.GONE);root.addView(layoutToolbar,p);
    }

    private void buildSettingsPanel(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(0xE6000000);scroll.setTag("settingsScroll");settingsPanel=new LinearLayout(this);settingsPanel.setOrientation(LinearLayout.VERTICAL);settingsPanel.setPadding(dp(18),dp(14),dp(18),dp(18));scroll.addView(settingsPanel,new ScrollView.LayoutParams(-1,-2));
        TextView title=text("Ambi Projector · Settings",20,Color.WHITE);title.setPadding(0,0,0,dp(8));settingsPanel.addView(title);TextView hint=text("Camera calibration reads the TV. Layout aligns the projected TV hole/text. Keystone aligns the outer projection.",12,0xFFBBBBBB);hint.setPadding(0,0,0,dp(12));settingsPanel.addView(hint);
        cameraStatusLabel=text("Camera: STARTING",13,0xFFFFB74D);cameraStatusLabel.setPadding(0,0,0,dp(8));settingsPanel.addView(cameraStatusLabel);
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);actions.addView(makeButton("PREVIEW",v->togglePreview()));actions.addView(makeButton("CAMERA TV",v->enterCalibration()));actions.addView(makeButton("LAYOUT",v->enterLayoutCalibration()));actions.addView(makeButton("OUTER",v->enterProjectorCalibration()));actions.addView(makeButton("RESTART CAM",v->restartCamera("manual settings")));actions.addView(makeButton("CLOSE",v->toggleSettings()));settingsPanel.addView(actions);

        addSection("CAMERA / CAPTURE");addExposureControl();addZoomControl();
        addFloatSlider("Capture contrast",0.45f,2.20f,1f,"captureContrast",v->analyzer.setCaptureContrast(v));addFloatSlider("Capture saturation",0f,2.20f,1f,"captureSaturation",v->analyzer.setCaptureSaturation(v));addBiPolarSlider("Camera color temperature","Warm","Cool","captureColorTemperature",v->analyzer.setCaptureColorTemperature(v));addBiPolarSlider("Camera tint","Green","Magenta","captureTint",v->analyzer.setCaptureTint(v));addFloatSlider("Camera red gain",0.45f,1.75f,1f,"captureRedGain",v->analyzer.setCaptureRedGain(v));addFloatSlider("Camera green gain",0.45f,1.75f,1f,"captureGreenGain",v->analyzer.setCaptureGreenGain(v));addFloatSlider("Camera blue gain",0.45f,1.75f,1f,"captureBlueGain",v->analyzer.setCaptureBlueGain(v));

        addSection("AMBIENT PROJECTION");addFloatSlider("Ambient brightness",0.15f,2f,1f,"ambientBrightness",v->analyzer.setAmbientBrightness(v));addFloatSlider("Ambient contrast",0.45f,2.20f,1f,"ambientContrast",v->analyzer.setAmbientContrast(v));addBiPolarSlider("Projector color temperature","Warm","Cool","ambientColorTemperature",v->analyzer.setAmbientColorTemperature(v));addFloatSlider("Motion smoothing",0f,0.96f,0.68f,"smoothing",v->analyzer.setSmoothing(v));addFloatSlider("Outer black fade",0.02f,0.42f,0.16f,"outerFade",v->ambilightView.setOuterFadeRatio(v));

        addSection("PROJECTION STYLE");
        LinearLayout styleRow=new LinearLayout(this);styleRow.setOrientation(LinearLayout.HORIZONTAL);
        styleRow.addView(makeButton("EDGE GRADIENT",v->{ambilightView.setProjectionStyle(AmbilightView.ProjectionStyle.EDGE_GRADIENT);prefs.edit().putString("projectionStyle","EDGE_GRADIENT").apply();Toast.makeText(this,"Projection: Edge Gradient",Toast.LENGTH_SHORT).show();}));
        styleRow.addView(makeButton("COLOR CLOUD",v->{ambilightView.setProjectionStyle(AmbilightView.ProjectionStyle.COLOR_CLOUD);prefs.edit().putString("projectionStyle","COLOR_CLOUD").apply();Toast.makeText(this,"Projection: Color Cloud",Toast.LENGTH_SHORT).show();}));
        settingsPanel.addView(styleRow);

        addSection("COLOR CLOUD / GRADIENT");
        addFloatSlider("Cloud spread",0.05f,0.90f,0.42f,"cloudSpread",v->ambilightView.setCloudSpread(v));
        addFloatSlider("Cloud radius",0.08f,0.50f,0.26f,"cloudRadius",v->ambilightView.setCloudRadius(v));
        addFloatSlider("Cloud opacity",0.05f,1.00f,0.60f,"cloudOpacity",v->ambilightView.setCloudOpacity(v));
        addFloatSlider("Gradient softness",0.00f,1.00f,0.72f,"cloudSoftness",v->ambilightView.setCloudSoftness(v));
        addFloatSlider("Corner blend",0.00f,1.00f,0.82f,"cornerBlend",v->ambilightView.setCornerBlend(v));
        addFloatSlider("Corner radius",0.70f,2.40f,1.48f,"cornerRadius",v->ambilightView.setCornerRadius(v));
        addFloatSlider("Edge pull",0.00f,1.00f,0.62f,"cloudEdgePull",v->ambilightView.setCloudEdgePull(v));
        addFloatSlider("Cloud saturation",0.50f,2.50f,1.32f,"cloudSaturation",v->ambilightView.setCloudSaturation(v));
        addFloatSlider("Cloud brightness",0.40f,1.80f,1.08f,"cloudBrightness",v->ambilightView.setCloudBrightness(v));

        TextView info=text("COLOR CLOUD: radial gradient fields + diagonal corner bridges. LAYOUT: projected TV/text. OUTER: keystone and projection edges.",12,0xFFAAAAAA);info.setPadding(0,dp(8),0,0);settingsPanel.addView(info);
        TextView reset=makeButton("RESET ALL SETTINGS",v->resetSettings());LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.topMargin=dp(12);reset.setLayoutParams(rp);settingsPanel.addView(reset);
        FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(Math.min(dp(560),Math.round(getResources().getDisplayMetrics().widthPixels*0.62f)),-1);sp.gravity=Gravity.END;scroll.setVisibility(View.GONE);root.addView(scroll,sp);
    }

    private interface FloatConsumer{void accept(float v);}
    private void addBiPolarSlider(String title,String neg,String pos,String key,FloatConsumer consumer){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);TextView label=text("",14,Color.WHITE);float initial=prefs.getFloat(key,0f);label.setText(bipolarLabel(title,neg,pos,initial));box.addView(label);SeekBar seek=new SeekBar(this);seek.setMax(1000);seek.setProgress(Math.round((initial+1f)*500f));box.addView(seek,new LinearLayout.LayoutParams(-1,dp(40)));seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){@Override public void onProgressChanged(SeekBar s,int p,boolean fromUser){float v=p/500f-1f;label.setText(bipolarLabel(title,neg,pos,v));if(analyzer!=null)consumer.accept(v);if(fromUser)prefs.edit().putFloat(key,v).apply();}@Override public void onStartTrackingTouch(SeekBar s){}@Override public void onStopTrackingTouch(SeekBar s){}});settingsPanel.addView(box);}
    private String bipolarLabel(String title,String neg,String pos,float v){if(Math.abs(v)<0.035f)return title+": Neutral";return String.format(Locale.US,"%s: %s %.0f%%",title,v<0?neg:pos,Math.abs(v)*100f);}
    private void addFloatSlider(String title,float min,float max,float def,String key,FloatConsumer consumer){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);TextView label=text("",14,Color.WHITE);float initial=prefs.getFloat(key,def);label.setText(String.format(Locale.US,"%s: %.2f",title,initial));box.addView(label);SeekBar seek=new SeekBar(this);seek.setMax(1000);seek.setProgress(Math.round((initial-min)/(max-min)*1000f));box.addView(seek,new LinearLayout.LayoutParams(-1,dp(40)));seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){@Override public void onProgressChanged(SeekBar s,int p,boolean fromUser){float v=min+(max-min)*(p/1000f);label.setText(String.format(Locale.US,"%s: %.2f",title,v));if(analyzer!=null)consumer.accept(v);if(fromUser)prefs.edit().putFloat(key,v).apply();}@Override public void onStartTrackingTouch(SeekBar s){}@Override public void onStopTrackingTouch(SeekBar s){}});settingsPanel.addView(box);}
    private void addSection(String s){TextView v=text(s,13,Color.WHITE);v.setPadding(0,dp(16),0,dp(5));settingsPanel.addView(v);}private TextView text(String s,float size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);return v;}
    private void addExposureControl(){exposureLabel=text("Exposure compensation: 0",14,Color.WHITE);settingsPanel.addView(exposureLabel);exposureSeek=new SeekBar(this);exposureSeek.setMax(1000);exposureSeek.setProgress(500);settingsPanel.addView(exposureSeek,new LinearLayout.LayoutParams(-1,dp(40)));exposureSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){@Override public void onProgressChanged(SeekBar s,int p,boolean f){if(f)applyExposureFromSlider(p,true);}@Override public void onStartTrackingTouch(SeekBar s){}@Override public void onStopTrackingTouch(SeekBar s){}});}
    private void addZoomControl(){zoomLabel=text("Camera zoom: 1.80x",14,Color.WHITE);settingsPanel.addView(zoomLabel);zoomSeek=new SeekBar(this);zoomSeek.setMax(1000);zoomSeek.setProgress(115);settingsPanel.addView(zoomSeek,new LinearLayout.LayoutParams(-1,dp(40)));zoomSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){@Override public void onProgressChanged(SeekBar s,int p,boolean f){if(f)applyZoomFromSlider(p,true);}@Override public void onStartTrackingTouch(SeekBar s){}@Override public void onStopTrackingTouch(SeekBar s){}});}

    private void restoreSettings(){
        if(analyzer==null)return;analyzer.setCaptureContrast(prefs.getFloat("captureContrast",1f));analyzer.setCaptureSaturation(prefs.getFloat("captureSaturation",1f));analyzer.setCaptureColorTemperature(prefs.getFloat("captureColorTemperature",0f));analyzer.setCaptureTint(prefs.getFloat("captureTint",0f));analyzer.setCaptureRedGain(prefs.getFloat("captureRedGain",1f));analyzer.setCaptureGreenGain(prefs.getFloat("captureGreenGain",1f));analyzer.setCaptureBlueGain(prefs.getFloat("captureBlueGain",1f));analyzer.setAmbientBrightness(prefs.getFloat("ambientBrightness",1f));analyzer.setAmbientContrast(prefs.getFloat("ambientContrast",1f));analyzer.setAmbientColorTemperature(prefs.getFloat("ambientColorTemperature",0f));analyzer.setSmoothing(prefs.getFloat("smoothing",0.68f));
        String style=prefs.getString("projectionStyle","COLOR_CLOUD");ambilightView.setProjectionStyle("EDGE_GRADIENT".equals(style)?AmbilightView.ProjectionStyle.EDGE_GRADIENT:AmbilightView.ProjectionStyle.COLOR_CLOUD);
        ambilightView.setCloudSpread(prefs.getFloat("cloudSpread",0.42f));ambilightView.setCloudRadius(prefs.getFloat("cloudRadius",0.26f));ambilightView.setCloudOpacity(prefs.getFloat("cloudOpacity",0.60f));ambilightView.setCloudSoftness(prefs.getFloat("cloudSoftness",0.72f));ambilightView.setCornerBlend(prefs.getFloat("cornerBlend",0.82f));ambilightView.setCornerRadius(prefs.getFloat("cornerRadius",1.48f));ambilightView.setCloudEdgePull(prefs.getFloat("cloudEdgePull",0.62f));ambilightView.setCloudSaturation(prefs.getFloat("cloudSaturation",1.32f));ambilightView.setCloudBrightness(prefs.getFloat("cloudBrightness",1.08f));
        ambilightView.setOuterFadeRatio(prefs.getFloat("outerFade",0.16f));ambilightView.setKeystoneCorners(loadKeystone());ambilightView.setTvRect(loadTvRect());ambilightView.setTextFrames(loadTextFrames());calibrationZoom=prefs.getFloat("cameraZoom",1.8f);requestedExposureIndex=prefs.getInt("exposureIndex",0);
    }

    private void resetSettings(){
        prefs.edit().clear().apply();analyzer.setCaptureContrast(1f);analyzer.setCaptureSaturation(1f);analyzer.setCaptureColorTemperature(0f);analyzer.setCaptureTint(0f);analyzer.setCaptureRedGain(1f);analyzer.setCaptureGreenGain(1f);analyzer.setCaptureBlueGain(1f);analyzer.setAmbientBrightness(1f);analyzer.setAmbientContrast(1f);analyzer.setAmbientColorTemperature(0f);analyzer.setSmoothing(0.68f);ambilightView.setOuterFadeRatio(0.16f);
        ambilightView.setProjectionStyle(AmbilightView.ProjectionStyle.COLOR_CLOUD);ambilightView.setCloudSpread(0.42f);ambilightView.setCloudRadius(0.26f);ambilightView.setCloudOpacity(0.60f);ambilightView.setCloudSoftness(0.72f);ambilightView.setCornerBlend(0.82f);ambilightView.setCornerRadius(1.48f);ambilightView.setCloudEdgePull(0.62f);ambilightView.setCloudSaturation(1.32f);ambilightView.setCloudBrightness(1.08f);
        float[] k=defaultKeystone(),tv=defaultTvRect();float[][] frames=defaultTextFrames();ambilightView.setKeystoneCorners(k);ambilightView.setTvRect(tv);ambilightView.setTextFrames(frames);projectorKeystoneView.setCorners(k);projectionLayoutView.setTvRect(tv);projectionLayoutView.setTextFrames(frames);calibrationZoom=1.8f;requestedExposureIndex=0;applyZoom(calibrationZoom);applyExposureIndex(0,false);Toast.makeText(this,"Settings reset · reopen panel to refresh sliders",Toast.LENGTH_SHORT).show();
    }

    private float[] defaultKeystone(){return new float[]{0f,0f,1f,0f,1f,1f,0f,1f};}
    private float[] defaultTvRect(){return new float[]{0.20f,0.27f,0.80f,0.73f};}
    private float[][] defaultTextFrames(){return new float[][]{{0.24f,0.06f,0.76f,0.18f},{0.24f,0.82f,0.76f,0.94f},{0.03f,0.32f,0.18f,0.68f},{0.82f,0.32f,0.97f,0.68f}};}
    private float[] loadKeystone(){float[] d=defaultKeystone(),c=new float[8];for(int i=0;i<8;i++)c[i]=prefs.getFloat("keystone"+i,d[i]);return c;}
    private void saveKeystone(float[] c){SharedPreferences.Editor e=prefs.edit();for(int i=0;i<8;i++)e.putFloat("keystone"+i,c[i]);e.apply();}
    private float[] loadTvRect(){float[] d=defaultTvRect(),r=new float[4];for(int i=0;i<4;i++)r[i]=prefs.getFloat("projectedTv"+i,d[i]);return r;}
    private float[][] loadTextFrames(){float[][] d=defaultTextFrames(),f=new float[4][4];for(int z=0;z<4;z++)for(int i=0;i<4;i++)f[z][i]=prefs.getFloat("textFrame"+z+"_"+i,d[z][i]);return f;}
    private void saveProjectionLayout(float[] tv,float[][] frames){SharedPreferences.Editor e=prefs.edit();for(int i=0;i<4;i++)e.putFloat("projectedTv"+i,tv[i]);for(int z=0;z<4;z++)for(int i=0;i<4;i++)e.putFloat("textFrame"+z+"_"+i,frames[z][i]);e.apply();}

    private void toggleSettings(){settingsVisible=!settingsVisible;View v=root.findViewWithTag("settingsScroll");if(v!=null)v.setVisibility(settingsVisible?View.VISIBLE:View.GONE);if(!settingsVisible&&previewVisible){calibrationContainer.setVisibility(View.INVISIBLE);previewVisible=false;}}
    private void togglePreview(){previewVisible=!previewVisible;calibrationContainer.setVisibility(previewVisible?View.VISIBLE:View.INVISIBLE);calibrationView.setVisibility(View.GONE);if(previewVisible&&lastFrameAt==0L)restartCamera("preview requested");}
    private TextView makeButton(String s,View.OnClickListener l){TextView b=text(s,13,Color.WHITE);b.setGravity(Gravity.CENTER);b.setPadding(dp(11),dp(9),dp(11),dp(9));b.setBackgroundColor(0xAA333333);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.setMargins(dp(3),dp(2),dp(3),dp(2));b.setLayoutParams(p);b.setOnClickListener(l);return b;}

    private void enterCalibration(){if(analyzer==null||projectorCalibrating||layoutCalibrating)return;calibrating=true;previewVisible=true;settingsVisible=false;hideSettings();applyZoom(calibrationZoom);calibrationContainer.setVisibility(View.VISIBLE);calibrationView.setCorners(analyzer.getCorners());calibrationView.setVisibility(View.VISIBLE);calibrationToolbar.setVisibility(View.VISIBLE);ambilightView.setVisibility(View.GONE);restartCamera("enter TV calibration");Toast.makeText(this,"CAMERA TV · drag the TV corners as seen by the camera",Toast.LENGTH_LONG).show();}
    private void exitCalibration(){calibrating=false;previewVisible=false;calibrationView.setVisibility(View.GONE);calibrationToolbar.setVisibility(View.GONE);calibrationContainer.setVisibility(View.INVISIBLE);ambilightView.setVisibility(View.VISIBLE);Toast.makeText(this,String.format(Locale.US,"Camera TV borders saved · zoom %.2fx",calibrationZoom),Toast.LENGTH_SHORT).show();}

    private void enterProjectorCalibration(){if(calibrating||layoutCalibrating)return;settingsVisible=false;hideSettings();previewVisible=false;calibrationContainer.setVisibility(View.INVISIBLE);projectorCalibrating=true;projectorKeystoneView.setCorners(ambilightView.getKeystoneCorners());projectorKeystoneView.setVisibility(View.VISIBLE);projectorToolbar.setVisibility(View.VISIBLE);Toast.makeText(this,"OUTER PROJECTION · drag corners or blue edge handles",Toast.LENGTH_LONG).show();}
    private void exitProjectorCalibration(){float[] c=projectorKeystoneView.getCorners();ambilightView.setKeystoneCorners(c);saveKeystone(c);projectorCalibrating=false;projectorKeystoneView.setVisibility(View.GONE);projectorToolbar.setVisibility(View.GONE);Toast.makeText(this,"Outer projection saved",Toast.LENGTH_SHORT).show();}

    private void enterLayoutCalibration(){if(calibrating||projectorCalibrating)return;settingsVisible=false;hideSettings();previewVisible=false;calibrationContainer.setVisibility(View.INVISIBLE);layoutCalibrating=true;projectionLayoutView.setTvRect(ambilightView.getTvRect());projectionLayoutView.setTextFrames(ambilightView.getTextFrames());ambilightView.showContextDemo(600000L);projectionLayoutView.setVisibility(View.VISIBLE);layoutToolbar.setVisibility(View.VISIBLE);Toast.makeText(this,"LAYOUT · TV inner border and text frames are independent",Toast.LENGTH_LONG).show();}
    private void exitLayoutCalibration(){float[] tv=projectionLayoutView.getTvRect();float[][] frames=projectionLayoutView.getTextFrames();ambilightView.setTvRect(tv);ambilightView.setTextFrames(frames);saveProjectionLayout(tv,frames);layoutCalibrating=false;projectionLayoutView.setVisibility(View.GONE);layoutToolbar.setVisibility(View.GONE);Toast.makeText(this,"Projection layout saved",Toast.LENGTH_SHORT).show();}
    private void hideSettings(){View v=root.findViewWithTag("settingsScroll");if(v!=null)v.setVisibility(View.GONE);}

    private void setCameraStatus(String status,int color){if(cameraStatusLabel!=null){cameraStatusLabel.setText("Camera: "+status);cameraStatusLabel.setTextColor(color);}}
    private void restartCamera(String reason){if(cameraBinding||destroyed)return;long now=SystemClock.elapsedRealtime();if(now-lastRestartAt<1000L)return;lastRestartAt=now;lastFrameAt=0L;Log.w(TAG,"Restarting camera: "+reason);setCameraStatus("RESTARTING",0xFFFFB74D);startCameraSafely();}
    private void changeZoom(float d){calibrationZoom=Math.max(1f,calibrationZoom+d);applyZoom(calibrationZoom);syncZoomSlider();}
    private void applyZoom(float requested){if(boundCamera==null)return;try{androidx.camera.core.ZoomState z=boundCamera.getCameraInfo().getZoomState().getValue();float max=z==null?requested:z.getMaxZoomRatio(),min=z==null?1f:z.getMinZoomRatio();calibrationZoom=Math.max(min,Math.min(max,requested));boundCamera.getCameraControl().setZoomRatio(calibrationZoom);prefs.edit().putFloat("cameraZoom",calibrationZoom).apply();if(zoomLabel!=null)zoomLabel.setText(String.format(Locale.US,"Camera zoom: %.2fx",calibrationZoom));}catch(Throwable t){Log.w(TAG,"Zoom unavailable",t);}}
    private void applyZoomFromSlider(int p,boolean persist){if(boundCamera==null)return;try{androidx.camera.core.ZoomState z=boundCamera.getCameraInfo().getZoomState().getValue();if(z==null)return;float ratio=z.getMinZoomRatio()+(z.getMaxZoomRatio()-z.getMinZoomRatio())*(p/1000f);calibrationZoom=ratio;boundCamera.getCameraControl().setZoomRatio(ratio);zoomLabel.setText(String.format(Locale.US,"Camera zoom: %.2fx",ratio));if(persist)prefs.edit().putFloat("cameraZoom",ratio).apply();}catch(Throwable t){Log.w(TAG,"Zoom slider",t);}}
    private void syncZoomSlider(){if(boundCamera==null||zoomSeek==null)return;try{androidx.camera.core.ZoomState z=boundCamera.getCameraInfo().getZoomState().getValue();if(z==null)return;float p=(calibrationZoom-z.getMinZoomRatio())/(z.getMaxZoomRatio()-z.getMinZoomRatio());zoomSeek.setProgress(Math.round(Math.max(0f,Math.min(1f,p))*1000f));}catch(Throwable ignored){}}
    private void applyExposureFromSlider(int p,boolean persist){if(boundCamera==null)return;try{ExposureState e=boundCamera.getCameraInfo().getExposureState();Range<Integer> r=e.getExposureCompensationRange();applyExposureIndex(Math.round(r.getLower()+(r.getUpper()-r.getLower())*(p/1000f)),persist);}catch(Throwable t){Log.w(TAG,"Exposure slider",t);}}
    private void applyExposureIndex(int requested,boolean persist){if(boundCamera==null)return;try{ExposureState e=boundCamera.getCameraInfo().getExposureState();Range<Integer> r=e.getExposureCompensationRange();int idx=Math.max(r.getLower(),Math.min(r.getUpper(),requested));requestedExposureIndex=idx;if(e.isExposureCompensationSupported())boundCamera.getCameraControl().setExposureCompensationIndex(idx);if(exposureLabel!=null)exposureLabel.setText("Exposure compensation: "+idx);if(persist)prefs.edit().putInt("exposureIndex",idx).apply();syncExposureSlider();}catch(Throwable t){Log.w(TAG,"Exposure unavailable",t);}}
    private void syncExposureSlider(){if(boundCamera==null||exposureSeek==null)return;try{ExposureState e=boundCamera.getCameraInfo().getExposureState();Range<Integer> r=e.getExposureCompensationRange();int min=r.getLower(),max=r.getUpper();exposureSeek.setEnabled(e.isExposureCompensationSupported()&&max>min);float p=max==min?0.5f:(requestedExposureIndex-min)/(float)(max-min);exposureSeek.setProgress(Math.round(Math.max(0f,Math.min(1f,p))*1000f));}catch(Throwable ignored){}}

    private void startCameraSafely(){if(cameraBinding||destroyed)return;cameraBinding=true;setCameraStatus("STARTING",0xFFFFB74D);try{startCamera();}catch(Throwable t){cameraBinding=false;setCameraStatus("ERROR",0xFFEF5350);Log.e(TAG,"Camera setup",t);}}
    private void startCamera(){ListenableFuture<ProcessCameraProvider> f=ProcessCameraProvider.getInstance(this);f.addListener(()->{try{cameraProvider=f.get();cameraProvider.unbindAll();Preview preview=new Preview.Builder().setTargetResolution(new Size(640,480)).build();preview.setSurfaceProvider(previewView.getSurfaceProvider());ImageAnalysis analysis=new ImageAnalysis.Builder().setTargetResolution(new Size(640,480)).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build();analysis.setAnalyzer(cameraExecutor,analyzer);boundCamera=cameraProvider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,preview,analysis);applyZoom(calibrationZoom);applyExposureIndex(requestedExposureIndex,false);syncZoomSlider();syncExposureSlider();setCameraStatus("BOUND · waiting frames",0xFFFFB74D);}catch(Throwable t){setCameraStatus("ERROR · "+t.getClass().getSimpleName(),0xFFEF5350);Log.e(TAG,"Camera bind",t);}finally{cameraBinding=false;}},ContextCompat.getMainExecutor(this));}

    @Override protected void onResume(){super.onResume();if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)mainHandler.postDelayed(()->{if(!destroyed&&(lastFrameAt==0L||SystemClock.elapsedRealtime()-lastFrameAt>CAMERA_STALL_MS))restartCamera("activity resume");},700L);}
    @Override protected void onPause(){super.onPause();setCameraStatus("PAUSED",0xFFAAAAAA);}
    @Override public void onRequestPermissionsResult(int requestCode,@NonNull String[] permissions,@NonNull int[] results){super.onRequestPermissionsResult(requestCode,permissions,results);if(requestCode==CAMERA_REQUEST&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)restartCamera("permission granted");}
    private void showFatalError(String stage,Throwable t){Log.e(TAG,stage,t);runOnUiThread(()->{TextView e=text("Ambi Projector diagnostic\n\n"+stage+" error:\n"+t.getClass().getName()+"\n"+(t.getMessage()==null?"(no message)":t.getMessage()),15,Color.WHITE);e.setBackgroundColor(Color.BLACK);e.setPadding(dp(18),dp(18),dp(18),dp(18));setContentView(e);});}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    @Override protected void onDestroy(){destroyed=true;mainHandler.removeCallbacksAndMessages(null);if(cameraProvider!=null)try{cameraProvider.unbindAll();}catch(Throwable ignored){}if(cameraExecutor!=null)cameraExecutor.shutdownNow();super.onDestroy();}
}
