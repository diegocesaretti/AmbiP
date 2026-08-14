package com.bwa3d.ambiprojector;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends ComponentActivity {
    private static final String TAG = "AmbiProjector";
    private static final int CAMERA_REQUEST = 1001;

    private FrameLayout root;
    private AmbilightView ambilightView;
    private PreviewView previewView;
    private FrameLayout calibrationContainer;
    private ScreenCalibrationView calibrationView;
    private LinearLayout calibrationToolbar;
    private FrameAnalyzer analyzer;
    private ExecutorService cameraExecutor;
    private Camera boundCamera;
    private boolean previewVisible = false;
    private boolean calibrating = false;
    private float calibrationZoom = 1.8f;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            cameraExecutor = Executors.newSingleThreadExecutor();
            buildUi();
            analyzer = new FrameAnalyzer(state -> runOnUiThread(() -> { if (ambilightView != null) ambilightView.setState(state); }));
            analyzer.setDetectionListener((corners, confidence) -> runOnUiThread(() -> {
                if (calibrationView != null) calibrationView.setCorners(corners);
                Toast.makeText(this,String.format("Auto detect %.0f%% confidence",confidence*100f),Toast.LENGTH_SHORT).show();
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

        calibrationToolbar=new LinearLayout(this); calibrationToolbar.setOrientation(LinearLayout.HORIZONTAL); calibrationToolbar.setGravity(Gravity.CENTER); calibrationToolbar.setPadding(dp(6),dp(6),dp(6),dp(6)); calibrationToolbar.setBackgroundColor(0xBB000000);
        calibrationToolbar.addView(makeButton("− ZOOM",v->changeZoom(-0.25f)));
        calibrationToolbar.addView(makeButton("AUTO",v->{if(analyzer!=null){analyzer.requestAutoDetect();Toast.makeText(this,"Looking for TV borders…",Toast.LENGTH_SHORT).show();}}));
        calibrationToolbar.addView(makeButton("ZOOM +",v->changeZoom(0.25f)));
        calibrationToolbar.addView(makeButton("RESET",v->{float[] c=new float[]{0.10f,0.14f,0.90f,0.14f,0.90f,0.86f,0.10f,0.86f};calibrationView.setCorners(c);if(analyzer!=null)analyzer.setCorners(c);}));
        calibrationToolbar.addView(makeButton("DONE",v->exitCalibration()));
        FrameLayout.LayoutParams tb=new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,FrameLayout.LayoutParams.WRAP_CONTENT); tb.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL; tb.topMargin=dp(8); calibrationToolbar.setVisibility(View.GONE); root.addView(calibrationToolbar,tb);

        ambilightView.setGestureListener(new AmbilightView.GestureListener(){
            @Override public void onSingleTap(){if(calibrating)return;previewVisible=!previewVisible;if(previewVisible){calibrationContainer.setVisibility(View.VISIBLE);calibrationView.setVisibility(View.GONE);Toast.makeText(MainActivity.this,"Camera preview · tap again to hide",Toast.LENGTH_SHORT).show();}else calibrationContainer.setVisibility(View.GONE);}
            @Override public void onLongPress(){enterCalibration();}
            @Override public void onDoubleTap(){ambilightView.showContextDemo(6000);}
        });
        setContentView(root);
    }

    private TextView makeButton(String text,View.OnClickListener listener){TextView b=new TextView(this);b.setText(text);b.setTextColor(Color.WHITE);b.setTextSize(14f);b.setGravity(Gravity.CENTER);b.setPadding(dp(12),dp(9),dp(12),dp(9));b.setBackgroundColor(0xAA333333);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,LinearLayout.LayoutParams.WRAP_CONTENT);p.setMargins(dp(3),0,dp(3),0);b.setLayoutParams(p);b.setOnClickListener(listener);return b;}

    private void enterCalibration(){
        if(analyzer==null)return; calibrating=true;previewVisible=true;
        // Start magnified so the TV occupies many more camera pixels; this same zoom is kept
        // after calibration, so the selected corner coordinates remain valid during Ambilight.
        applyZoom(calibrationZoom);
        calibrationContainer.setVisibility(View.VISIBLE); calibrationView.setCorners(analyzer.getCorners()); calibrationView.setVisibility(View.VISIBLE); calibrationToolbar.setVisibility(View.VISIBLE); ambilightView.setVisibility(View.GONE);
        Toast.makeText(this,"Zoomed calibration · use −/+ if needed, then drag all 4 corners",Toast.LENGTH_LONG).show();
    }

    private void exitCalibration(){calibrating=false;previewVisible=false;calibrationView.setVisibility(View.GONE);calibrationToolbar.setVisibility(View.GONE);calibrationContainer.setVisibility(View.GONE);ambilightView.setVisibility(View.VISIBLE);Toast.makeText(this,String.format("Borders saved · camera zoom %.2fx",calibrationZoom),Toast.LENGTH_SHORT).show();}

    private void changeZoom(float delta){calibrationZoom=Math.max(1f,calibrationZoom+delta);applyZoom(calibrationZoom);Toast.makeText(this,String.format("Camera zoom %.2fx · reposition corners",calibrationZoom),Toast.LENGTH_SHORT).show();}
    private void applyZoom(float requested){
        if(boundCamera==null)return;
        try {
            androidx.camera.core.ZoomState zs=boundCamera.getCameraInfo().getZoomState().getValue();
            float max=zs==null?requested:zs.getMaxZoomRatio(); float min=zs==null?1f:zs.getMinZoomRatio();
            calibrationZoom=Math.max(min,Math.min(max,requested));
            boundCamera.getCameraControl().setZoomRatio(calibrationZoom);
        } catch(Throwable t){Log.w(TAG,"Zoom not available",t);}
    }

    private void startCameraSafely(){try{startCamera();}catch(Throwable t){showFatalError("Camera setup",t);}}
    private void startCamera(){final ListenableFuture<ProcessCameraProvider> future=ProcessCameraProvider.getInstance(this);future.addListener(()->{try{ProcessCameraProvider provider=future.get();provider.unbindAll();Preview preview=new Preview.Builder().setTargetResolution(new Size(640,480)).build();preview.setSurfaceProvider(previewView.getSurfaceProvider());ImageAnalysis analysis=new ImageAnalysis.Builder().setTargetResolution(new Size(640,480)).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build();analysis.setAnalyzer(cameraExecutor,analyzer);boundCamera=provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,preview,analysis);if(calibrating)applyZoom(calibrationZoom);}catch(Throwable t){Log.e(TAG,"Camera failed",t);showFatalError("Camera",t);}},ContextCompat.getMainExecutor(this));}

    @Override public void onRequestPermissionsResult(int requestCode,@NonNull String[] permissions,@NonNull int[] grantResults){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==CAMERA_REQUEST){if(grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED)startCameraSafely();else Toast.makeText(this,"Camera permission is required",Toast.LENGTH_LONG).show();}}
    private void showFatalError(String stage,Throwable t){Log.e(TAG,stage+" failure",t);runOnUiThread(()->{try{TextView error=new TextView(this);error.setTextColor(Color.WHITE);error.setBackgroundColor(Color.BLACK);error.setTextSize(15f);error.setPadding(dp(18),dp(18),dp(18),dp(18));String msg=t.getClass().getName()+"\n"+(t.getMessage()==null?"(no message)":t.getMessage());error.setText("Ambi Projector diagnostic\n\n"+stage+" error:\n"+msg);setContentView(error);}catch(Throwable ignored){Toast.makeText(this,stage+" error",Toast.LENGTH_LONG).show();}});}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    @Override protected void onDestroy(){super.onDestroy();if(cameraExecutor!=null)cameraExecutor.shutdownNow();}
}
