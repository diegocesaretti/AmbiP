package com.bwa3d.ambiprojector;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
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
    private static final String TAG = "AmbiProjector";
    private static final int CAMERA_REQUEST = 1001;
    private static final String PREFS = "ambi_projector_settings";

    private FrameLayout root;
    private AmbilightView ambilightView;
    private PreviewView previewView;
    private FrameLayout calibrationContainer;
    private ScreenCalibrationView calibrationView;
    private LinearLayout calibrationToolbar;
    private LinearLayout settingsPanel;
    private TextView exposureLabel;
    private SeekBar exposureSeek;
    private TextView zoomLabel;
    private SeekBar zoomSeek;

    private FrameAnalyzer analyzer;
    private ExecutorService cameraExecutor;
    private Camera boundCamera;
    private SharedPreferences prefs;
    private boolean previewVisible = false;
    private boolean calibrating = false;
    private boolean settingsVisible = false;
    private float calibrationZoom = 1.8f;
    private int requestedExposureIndex = 0;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            cameraExecutor = Executors.newSingleThreadExecutor();
            buildUi();
            analyzer = new FrameAnalyzer(state -> runOnUiThread(() -> { if (ambilightView != null) ambilightView.setState(state); }));
            restoreSettings();
            analyzer.setDetectionListener((corners, confidence) -> runOnUiThread(() -> {
                if (calibrationView != null) calibrationView.setCorners(corners);
                Toast.makeText(this,String.format(Locale.US,"Auto detect %.0f%% confidence",confidence*100f),Toast.LENGTH_SHORT).show();
            }));
            calibrationView.setListener(c -> { if (analyzer != null) analyzer.setCorners(c); });

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) startCameraSafely();
            else ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.CAMERA},CAMERA_REQUEST);
        } catch(Throwable t){showFatalError("Startup",t);}
    }

    private void buildUi() {
        root=new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        ambilightView=new AmbilightView(this);
        root.addView(ambilightView,new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT));

        calibrationContainer=new FrameLayout(this); calibrationContainer.setBackgroundColor(Color.BLACK);
        int sw=getResources().getDisplayMetrics().widthPixels, sh=getResources().getDisplayMetrics().heightPixels;
        int cw=Math.min(sw,Math.round(sh*4f/3f)); int ch=Math.min(sh,Math.round(cw*3f/4f));
        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(cw,ch); cp.gravity=Gravity.CENTER; root.addView(calibrationContainer,cp);

        previewView=new PreviewView(this); previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER); previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        calibrationContainer.addView(previewView,new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT));
        calibrationView=new ScreenCalibrationView(this); calibrationView.setVisibility(View.GONE);
        calibrationContainer.addView(calibrationView,new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT));
        calibrationContainer.setVisibility(View.GONE);

        buildCalibrationToolbar();
        buildSettingsPanel();

        ambilightView.setGestureListener(new AmbilightView.GestureListener(){
            @Override public void onSingleTap(){ if(!calibrating) toggleSettings(); }
            @Override public void onLongPress(){enterCalibration();}
            @Override public void onDoubleTap(){ambilightView.showContextDemo(6000);}
        });
        setContentView(root);
    }

    private void buildCalibrationToolbar() {
        calibrationToolbar=new LinearLayout(this); calibrationToolbar.setOrientation(LinearLayout.HORIZONTAL); calibrationToolbar.setGravity(Gravity.CENTER); calibrationToolbar.setPadding(dp(6),dp(6),dp(6),dp(6)); calibrationToolbar.setBackgroundColor(0xBB000000);
        calibrationToolbar.addView(makeButton("− ZOOM",v->changeZoom(-0.25f)));
        calibrationToolbar.addView(makeButton("AUTO",v->{if(analyzer!=null){analyzer.requestAutoDetect();Toast.makeText(this,"Looking for TV borders…",Toast.LENGTH_SHORT).show();}}));
        calibrationToolbar.addView(makeButton("ZOOM +",v->changeZoom(0.25f)));
        calibrationToolbar.addView(makeButton("RESET",v->{float[] c=new float[]{0.10f,0.14f,0.90f,0.14f,0.90f,0.86f,0.10f,0.86f};calibrationView.setCorners(c);if(analyzer!=null)analyzer.setCorners(c);}));
        calibrationToolbar.addView(makeButton("DONE",v->exitCalibration()));
        FrameLayout.LayoutParams tb=new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,FrameLayout.LayoutParams.WRAP_CONTENT); tb.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL; tb.topMargin=dp(8); calibrationToolbar.setVisibility(View.GONE); root.addView(calibrationToolbar,tb);
    }

    private void buildSettingsPanel() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xE6000000);
        settingsPanel = new LinearLayout(this);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        settingsPanel.setPadding(dp(18),dp(14),dp(18),dp(18));
        scroll.addView(settingsPanel,new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT,ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this); title.setText("Ambi Projector · Settings"); title.setTextColor(Color.WHITE); title.setTextSize(20f); title.setPadding(0,0,0,dp(8)); settingsPanel.addView(title);
        TextView hint = new TextView(this); hint.setText("Camera controls affect what we capture. Ambient controls only affect the projected light."); hint.setTextColor(0xFFBBBBBB); hint.setTextSize(12f); hint.setPadding(0,0,0,dp(12)); settingsPanel.addView(hint);

        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(makeButton("PREVIEW",v->togglePreview()));
        actions.addView(makeButton("CALIBRATE",v->enterCalibration()));
        actions.addView(makeButton("CLOSE",v->toggleSettings()));
        settingsPanel.addView(actions);

        addSection("CAMERA");
        addExposureControl();
        addZoomControl();
        addFloatSlider("Capture contrast",0.45f,2.20f,1.0f,"captureContrast",v->{if(analyzer!=null)analyzer.setCaptureContrast(v);});
        addFloatSlider("Capture saturation",0.0f,2.20f,1.0f,"captureSaturation",v->{if(analyzer!=null)analyzer.setCaptureSaturation(v);});

        addSection("AMBIENT PROJECTION");
        addFloatSlider("Ambient brightness",0.15f,2.0f,1.0f,"ambientBrightness",v->{if(analyzer!=null)analyzer.setAmbientBrightness(v);});
        addFloatSlider("Ambient contrast",0.45f,2.20f,1.0f,"ambientContrast",v->{if(analyzer!=null)analyzer.setAmbientContrast(v);});
        addFloatSlider("Motion smoothing",0.0f,0.96f,0.68f,"smoothing",v->{if(analyzer!=null)analyzer.setSmoothing(v);});
        addFloatSlider("Outer black fade",0.02f,0.42f,0.16f,"outerFade",v->{if(ambilightView!=null)ambilightView.setOuterFadeRatio(v);});

        TextView reset = makeButton("RESET ALL SETTINGS",v->resetSettings());
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT); rp.topMargin=dp(12); reset.setLayoutParams(rp); settingsPanel.addView(reset);

        FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(Math.min(dp(430),Math.round(getResources().getDisplayMetrics().widthPixels*0.48f)),FrameLayout.LayoutParams.MATCH_PARENT);
        sp.gravity=Gravity.END; scroll.setVisibility(View.GONE); scroll.setTag("settingsScroll"); root.addView(scroll,sp);
    }

    private void addSection(String text) {
        TextView v=new TextView(this);v.setText(text);v.setTextColor(0xFFFFFFFF);v.setTextSize(13f);v.setPadding(0,dp(16),0,dp(5));settingsPanel.addView(v);
    }

    private void addExposureControl() {
        exposureLabel=new TextView(this); exposureLabel.setTextColor(Color.WHITE); exposureLabel.setTextSize(14f); exposureLabel.setText("Exposure compensation: 0"); settingsPanel.addView(exposureLabel);
        exposureSeek=new SeekBar(this); exposureSeek.setMax(1000); exposureSeek.setProgress(500); settingsPanel.addView(exposureSeek,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(40)));
        exposureSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar seekBar,int progress,boolean fromUser){ if(fromUser) applyExposureFromSlider(progress,true); }
            @Override public void onStartTrackingTouch(SeekBar seekBar){}
            @Override public void onStopTrackingTouch(SeekBar seekBar){}
        });
    }

    private void addZoomControl() {
        zoomLabel=new TextView(this); zoomLabel.setTextColor(Color.WHITE); zoomLabel.setTextSize(14f); zoomLabel.setText("Camera zoom: 1.80x"); settingsPanel.addView(zoomLabel);
        zoomSeek=new SeekBar(this); zoomSeek.setMax(1000); zoomSeek.setProgress(115); settingsPanel.addView(zoomSeek,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(40)));
        zoomSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar seekBar,int progress,boolean fromUser){ if(fromUser) applyZoomFromSlider(progress,true); }
            @Override public void onStartTrackingTouch(SeekBar seekBar){}
            @Override public void onStopTrackingTouch(SeekBar seekBar){}
        });
    }

    private interface FloatConsumer { void accept(float value); }
    private void addFloatSlider(String title,float min,float max,float def,String key,FloatConsumer consumer) {
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        TextView label=new TextView(this);label.setTextColor(Color.WHITE);label.setTextSize(14f);
        float initial=prefs==null?def:prefs.getFloat(key,def); label.setText(String.format(Locale.US,"%s: %.2f",title,initial)); box.addView(label);
        SeekBar seek=new SeekBar(this);seek.setMax(1000);seek.setProgress(Math.round((initial-min)/(max-min)*1000f));box.addView(seek,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(40)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar s,int progress,boolean fromUser){float value=min+(max-min)*(progress/1000f);label.setText(String.format(Locale.US,"%s: %.2f",title,value));consumer.accept(value);if(fromUser&&prefs!=null)prefs.edit().putFloat(key,value).apply();}
            @Override public void onStartTrackingTouch(SeekBar s){}
            @Override public void onStopTrackingTouch(SeekBar s){}
        });
        consumer.accept(initial); settingsPanel.addView(box);
    }

    private void restoreSettings() {
        if(prefs==null||analyzer==null)return;
        analyzer.setCaptureContrast(prefs.getFloat("captureContrast",1.0f));
        analyzer.setCaptureSaturation(prefs.getFloat("captureSaturation",1.0f));
        analyzer.setAmbientBrightness(prefs.getFloat("ambientBrightness",1.0f));
        analyzer.setAmbientContrast(prefs.getFloat("ambientContrast",1.0f));
        analyzer.setSmoothing(prefs.getFloat("smoothing",0.68f));
        ambilightView.setOuterFadeRatio(prefs.getFloat("outerFade",0.16f));
        calibrationZoom=prefs.getFloat("cameraZoom",1.8f);
        requestedExposureIndex=prefs.getInt("exposureIndex",0);
    }

    private void resetSettings(){
        if(prefs!=null)prefs.edit().clear().apply();
        if(analyzer!=null){analyzer.setCaptureContrast(1f);analyzer.setCaptureSaturation(1f);analyzer.setAmbientBrightness(1f);analyzer.setAmbientContrast(1f);analyzer.setSmoothing(0.68f);}
        ambilightView.setOuterFadeRatio(0.16f);calibrationZoom=1.8f;requestedExposureIndex=0;applyZoom(calibrationZoom);applyExposureIndex(0,false);
        Toast.makeText(this,"Settings reset · close/reopen panel to refresh sliders",Toast.LENGTH_SHORT).show();
    }

    private void toggleSettings(){
        settingsVisible=!settingsVisible;
        View scroll=root.findViewWithTag("settingsScroll");if(scroll!=null)scroll.setVisibility(settingsVisible?View.VISIBLE:View.GONE);
        if(!settingsVisible&&previewVisible){calibrationContainer.setVisibility(View.GONE);previewVisible=false;}
    }

    private void togglePreview(){previewVisible=!previewVisible;calibrationContainer.setVisibility(previewVisible?View.VISIBLE:View.GONE);calibrationView.setVisibility(View.GONE);}

    private TextView makeButton(String text,View.OnClickListener listener){TextView b=new TextView(this);b.setText(text);b.setTextColor(Color.WHITE);b.setTextSize(13f);b.setGravity(Gravity.CENTER);b.setPadding(dp(11),dp(9),dp(11),dp(9));b.setBackgroundColor(0xAA333333);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,LinearLayout.LayoutParams.WRAP_CONTENT);p.setMargins(dp(3),dp(2),dp(3),dp(2));b.setLayoutParams(p);b.setOnClickListener(listener);return b;}

    private void enterCalibration(){
        if(analyzer==null)return; calibrating=true;previewVisible=true;settingsVisible=false;View scroll=root.findViewWithTag("settingsScroll");if(scroll!=null)scroll.setVisibility(View.GONE);
        applyZoom(calibrationZoom);
        calibrationContainer.setVisibility(View.VISIBLE); calibrationView.setCorners(analyzer.getCorners()); calibrationView.setVisibility(View.VISIBLE); calibrationToolbar.setVisibility(View.VISIBLE); ambilightView.setVisibility(View.GONE);
        Toast.makeText(this,"Zoomed calibration · use −/+ if needed, then drag all 4 corners",Toast.LENGTH_LONG).show();
    }

    private void exitCalibration(){calibrating=false;previewVisible=false;calibrationView.setVisibility(View.GONE);calibrationToolbar.setVisibility(View.GONE);calibrationContainer.setVisibility(View.GONE);ambilightView.setVisibility(View.VISIBLE);Toast.makeText(this,String.format(Locale.US,"Borders saved · camera zoom %.2fx",calibrationZoom),Toast.LENGTH_SHORT).show();}

    private void changeZoom(float delta){calibrationZoom=Math.max(1f,calibrationZoom+delta);applyZoom(calibrationZoom);syncZoomSlider();Toast.makeText(this,String.format(Locale.US,"Camera zoom %.2fx · reposition corners",calibrationZoom),Toast.LENGTH_SHORT).show();}
    private void applyZoom(float requested){
        if(boundCamera==null)return;
        try {androidx.camera.core.ZoomState zs=boundCamera.getCameraInfo().getZoomState().getValue();float max=zs==null?requested:zs.getMaxZoomRatio();float min=zs==null?1f:zs.getMinZoomRatio();calibrationZoom=Math.max(min,Math.min(max,requested));boundCamera.getCameraControl().setZoomRatio(calibrationZoom);if(prefs!=null)prefs.edit().putFloat("cameraZoom",calibrationZoom).apply();if(zoomLabel!=null)zoomLabel.setText(String.format(Locale.US,"Camera zoom: %.2fx",calibrationZoom));} catch(Throwable t){Log.w(TAG,"Zoom not available",t);}
    }

    private void applyZoomFromSlider(int progress,boolean persist){
        if(boundCamera==null)return;
        try{androidx.camera.core.ZoomState zs=boundCamera.getCameraInfo().getZoomState().getValue();if(zs==null)return;float min=zs.getMinZoomRatio(),max=zs.getMaxZoomRatio();float ratio=min+(max-min)*(progress/1000f);calibrationZoom=ratio;boundCamera.getCameraControl().setZoomRatio(ratio);zoomLabel.setText(String.format(Locale.US,"Camera zoom: %.2fx",ratio));if(persist&&prefs!=null)prefs.edit().putFloat("cameraZoom",ratio).apply();}catch(Throwable t){Log.w(TAG,"Zoom slider failed",t);}
    }

    private void syncZoomSlider(){
        if(boundCamera==null||zoomSeek==null)return;try{androidx.camera.core.ZoomState zs=boundCamera.getCameraInfo().getZoomState().getValue();if(zs==null)return;float p=(calibrationZoom-zs.getMinZoomRatio())/(zs.getMaxZoomRatio()-zs.getMinZoomRatio());zoomSeek.setProgress(Math.round(Math.max(0f,Math.min(1f,p))*1000f));}catch(Throwable ignored){}
    }

    private void applyExposureFromSlider(int progress,boolean persist){
        if(boundCamera==null)return;try{ExposureState es=boundCamera.getCameraInfo().getExposureState();Range<Integer> range=es.getExposureCompensationRange();int min=range.getLower(),max=range.getUpper();int idx=Math.round(min+(max-min)*(progress/1000f));applyExposureIndex(idx,persist);}catch(Throwable t){Log.w(TAG,"Exposure slider failed",t);}
    }

    private void applyExposureIndex(int requested,boolean persist){
        if(boundCamera==null)return;try{ExposureState es=boundCamera.getCameraInfo().getExposureState();Range<Integer> range=es.getExposureCompensationRange();int idx=Math.max(range.getLower(),Math.min(range.getUpper(),requested));requestedExposureIndex=idx;if(es.isExposureCompensationSupported())boundCamera.getCameraControl().setExposureCompensationIndex(idx);if(exposureLabel!=null)exposureLabel.setText("Exposure compensation: "+idx);if(persist&&prefs!=null)prefs.edit().putInt("exposureIndex",idx).apply();syncExposureSlider();}catch(Throwable t){Log.w(TAG,"Exposure not available",t);}
    }

    private void syncExposureSlider(){
        if(boundCamera==null||exposureSeek==null)return;try{ExposureState es=boundCamera.getCameraInfo().getExposureState();Range<Integer> range=es.getExposureCompensationRange();int min=range.getLower(),max=range.getUpper();exposureSeek.setEnabled(es.isExposureCompensationSupported()&&max>min);float p=max==min?0.5f:(requestedExposureIndex-min)/(float)(max-min);exposureSeek.setProgress(Math.round(Math.max(0f,Math.min(1f,p))*1000f));exposureLabel.setText(es.isExposureCompensationSupported()?"Exposure compensation: "+requestedExposureIndex:"Exposure compensation: unsupported");}catch(Throwable ignored){}
    }

    private void startCameraSafely(){try{startCamera();}catch(Throwable t){showFatalError("Camera setup",t);}}
    private void startCamera(){final ListenableFuture<ProcessCameraProvider> future=ProcessCameraProvider.getInstance(this);future.addListener(()->{try{ProcessCameraProvider provider=future.get();provider.unbindAll();Preview preview=new Preview.Builder().setTargetResolution(new Size(640,480)).build();preview.setSurfaceProvider(previewView.getSurfaceProvider());ImageAnalysis analysis=new ImageAnalysis.Builder().setTargetResolution(new Size(640,480)).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build();analysis.setAnalyzer(cameraExecutor,analyzer);boundCamera=provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,preview,analysis);applyZoom(calibrationZoom);applyExposureIndex(requestedExposureIndex,false);syncZoomSlider();syncExposureSlider();}catch(Throwable t){Log.e(TAG,"Camera failed",t);showFatalError("Camera",t);}},ContextCompat.getMainExecutor(this));}

    @Override public void onRequestPermissionsResult(int requestCode,@NonNull String[] permissions,@NonNull int[] grantResults){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==CAMERA_REQUEST){if(grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED)startCameraSafely();else Toast.makeText(this,"Camera permission is required",Toast.LENGTH_LONG).show();}}
    private void showFatalError(String stage,Throwable t){Log.e(TAG,stage+" failure",t);runOnUiThread(()->{try{TextView error=new TextView(this);error.setTextColor(Color.WHITE);error.setBackgroundColor(Color.BLACK);error.setTextSize(15f);error.setPadding(dp(18),dp(18),dp(18),dp(18));String msg=t.getClass().getName()+"\n"+(t.getMessage()==null?"(no message)":t.getMessage());error.setText("Ambi Projector diagnostic\n\n"+stage+" error:\n"+msg);setContentView(error);}catch(Throwable ignored){Toast.makeText(this,stage+" error",Toast.LENGTH_LONG).show();}});}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    @Override protected void onDestroy(){super.onDestroy();if(cameraExecutor!=null)cameraExecutor.shutdownNow();}
}
