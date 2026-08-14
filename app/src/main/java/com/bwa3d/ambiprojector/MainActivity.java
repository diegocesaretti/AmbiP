package com.bwa3d.ambiprojector;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
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
    private FrameAnalyzer analyzer;
    private ExecutorService cameraExecutor;
    private boolean previewVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            cameraExecutor = Executors.newSingleThreadExecutor();
            buildUi();
            analyzer = new FrameAnalyzer(state -> runOnUiThread(() -> {
                if (ambilightView != null) ambilightView.setState(state);
            }));

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                startCameraSafely();
            } else {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
            }
        } catch (Throwable t) {
            showFatalError("Startup", t);
        }
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(android.graphics.Color.BLACK);

        ambilightView = new AmbilightView(this);
        root.addView(ambilightView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setVisibility(View.GONE);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(dp(300), dp(190));
        pp.gravity = Gravity.END | Gravity.BOTTOM;
        pp.setMargins(dp(12), dp(12), dp(12), dp(12));
        root.addView(previewView, pp);

        ambilightView.setGestureListener(new AmbilightView.GestureListener() {
            @Override public void onSingleTap() {
                previewVisible = !previewVisible;
                previewView.setVisibility(previewVisible ? View.VISIBLE : View.GONE);
            }

            @Override public void onLongPress() {
                if (analyzer == null) return;
                float next = analyzer.getCropScale() + 0.10f;
                if (next > 0.98f) next = 0.50f;
                analyzer.setCropScale(next);
                Toast.makeText(MainActivity.this,
                        String.format("TV crop %.0f%%", next * 100f), Toast.LENGTH_SHORT).show();
            }

            @Override public void onDoubleTap() {
                ambilightView.showContextOverlay("Context layer ready · demo text", 4000);
            }
        });

        setContentView(root);
    }

    private void startCameraSafely() {
        try {
            startCamera();
        } catch (Throwable t) {
            showFatalError("Camera setup", t);
        }
    }

    private void startCamera() {
        final ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                provider.unbindAll();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();
                analysis.setAnalyzer(cameraExecutor, analyzer);

                provider.bindToLifecycle(this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis);
            } catch (Throwable t) {
                Log.e(TAG, "Camera failed", t);
                showFatalError("Camera", t);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCameraSafely();
            } else {
                Toast.makeText(this,
                        "Camera permission is required", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showFatalError(String stage, Throwable t) {
        Log.e(TAG, stage + " failure", t);
        runOnUiThread(() -> {
            try {
                TextView error = new TextView(this);
                error.setTextColor(android.graphics.Color.WHITE);
                error.setBackgroundColor(android.graphics.Color.BLACK);
                error.setTextSize(15f);
                error.setPadding(dp(18), dp(18), dp(18), dp(18));
                String msg = t.getClass().getName() + "\n" +
                        (t.getMessage() == null ? "(no message)" : t.getMessage());
                error.setText("Ambi Projector diagnostic\n\n" + stage + " error:\n" + msg);
                setContentView(error);
            } catch (Throwable ignored) {
                Toast.makeText(this, stage + " error", Toast.LENGTH_LONG).show();
            }
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdownNow();
    }
}
