package com.bwa3d.ambiprojector;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Network-only projector: receives compact TV RGB samples and performs visual processing here. */
public final class NetworkProjectorActivity extends ComponentActivity {
    private static final String PREFS = "ambi_projector_settings";
    private static final String KEY_SOURCE = "networkTvSource";

    private FrameLayout root;
    private AmbilightView ambilightView;
    private ContentOverlayView contentOverlay;
    private LinearLayout panel;
    private TextView status, web;
    private EditText source;
    private LightStreamClient client;
    private StateInterpolator interpolator;
    private ProjectorWebServer webServer;
    private SharedPreferences prefs;
    private boolean panelVisible = true;
    private boolean calibrationVisible;

    // If rendering is briefly late, overwrite the pending network state instead of building latency.
    private final AtomicReference<AmbilightState> pendingState = new AtomicReference<>();
    private final AtomicBoolean stateDispatchPosted = new AtomicBoolean();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        migrateFastDefaults();
        migrateFreeTvQuad();
        migrateContentV22();
        buildUi();
        applyProjectorSettings();

        interpolator = new StateInterpolator(ambilightView::setState);
        applyMotionSettings();
        interpolator.start();

        client = new LightStreamClient(new LightStreamClient.Listener() {
            @Override public void onState(AmbilightState state) { queueLatestState(state); }
            @Override public void onStatus(String value, boolean connected) {
                runOnUiThread(() -> {status.setText(value);status.setTextColor(connected ? 0xff81c784 : 0xffffb74d);});
            }
        });
        client.setSmoothing(prefs.getFloat("networkSmoothing",0.12f));

        String saved = prefs.getString(KEY_SOURCE, "");source.setText(saved);if (!saved.trim().isEmpty()) connect();

        webServer = new ProjectorWebServer(prefs, new ProjectorWebServer.Listener() {
            @Override public void onSettingsChanged() {
                runOnUiThread(() -> {applyProjectorSettings();applyMotionSettings();if(client!=null)client.setSmoothing(prefs.getFloat("networkSmoothing",0.12f));});
            }
            @Override public void onSourceChanged(String value) {
                runOnUiThread(() -> {source.setText(value);if (!value.trim().isEmpty()) connect();});
            }
            @Override public void onTextZone(int zone,String text,long durationMs) {
                runOnUiThread(() -> {if(text==null)contentOverlay.clearText(zone);else contentOverlay.showText(zone,text,durationMs);});
            }
            @Override public void onImageZone(int zone,String imageSource,long durationMs,String fit) {
                runOnUiThread(() -> {if(imageSource==null)contentOverlay.clearImage(zone);else contentOverlay.showImage(zone,imageSource,durationMs,fit);});
            }
        });
        try { web.setText("AmbiP Control Center: " + webServer.start()); }
        catch (Exception e) { web.setText("Control Center unavailable: " + e.getClass().getSimpleName()); }
    }

    private void migrateFastDefaults() {
        if (prefs.getBoolean("fastPipelineV20",false)) return;
        SharedPreferences.Editor e=prefs.edit();float oldSmooth=prefs.getFloat("networkSmoothing",0.25f);int oldMs=prefs.getInt("interpolationMs",46);float oldAdaptive=prefs.getFloat("interpolationAdaptive",0.88f);
        if(!prefs.contains("networkSmoothing")||Math.abs(oldSmooth-0.25f)<0.002f)e.putFloat("networkSmoothing",0.12f);if(!prefs.contains("interpolationMs")||oldMs==46)e.putInt("interpolationMs",30);if(!prefs.contains("interpolationAdaptive")||Math.abs(oldAdaptive-0.88f)<0.002f)e.putFloat("interpolationAdaptive",0.94f);e.putBoolean("fastPipelineV20",true).apply();
    }

    /** Converts the old rectangular TV mask to the independently draggable four-corner quad. */
    private void migrateFreeTvQuad(){
        if(prefs.getBoolean("freeTvQuadV21",false))return;SharedPreferences.Editor e=prefs.edit();
        if(!prefs.contains("tvQuad0")){
            float l=prefs.getFloat("projectedTv0",.20f),t=prefs.getFloat("projectedTv1",.27f),r=prefs.getFloat("projectedTv2",.80f),b=prefs.getFloat("projectedTv3",.73f);
            float[] q={l,t,r,t,r,b,l,b};for(int i=0;i<8;i++)e.putFloat("tvQuad"+i,q[i]);
        }
        if(!prefs.contains("rgbGainR"))e.putFloat("rgbGainR",1f).putFloat("rgbGainG",1f).putFloat("rgbGainB",1f).putInt("rgbOffsetR",0).putInt("rgbOffsetG",0).putInt("rgbOffsetB",0);
        e.putBoolean("freeTvQuadV21",true).apply();
    }

    private void migrateContentV22(){
        if(prefs.getBoolean("contentOverlayV22",false))return;SharedPreferences.Editor e=prefs.edit();
        for(int z=0;z<4;z++){if(!prefs.contains("textSize"+z))e.putFloat("textSize"+z,4.5f);if(!prefs.contains("textAlign"+z))e.putString("textAlign"+z,"center");}
        float[][] d={{.03f,.07f,.22f,.28f},{.78f,.07f,.97f,.28f}};for(int z=0;z<2;z++){for(int i=0;i<4;i++)if(!prefs.contains("imageFrame"+z+"_"+i))e.putFloat("imageFrame"+z+"_"+i,d[z][i]);if(!prefs.contains("imageFit"+z))e.putString("imageFit"+z,"contain");}
        e.putBoolean("contentOverlayV22",true).apply();
    }

    private void queueLatestState(AmbilightState next) {if(next==null)return;pendingState.set(next);if(stateDispatchPosted.compareAndSet(false,true))runOnUiThread(this::drainLatestState);}
    private void drainLatestState(){AmbilightState latest=pendingState.getAndSet(null);if(latest!=null){if(interpolator!=null)interpolator.push(latest);else ambilightView.setState(latest);}stateDispatchPosted.set(false);if(pendingState.get()!=null&&stateDispatchPosted.compareAndSet(false,true))runOnUiThread(this::drainLatestState);}

    private void buildUi() {
        root = new FrameLayout(this);root.setBackgroundColor(Color.BLACK);
        ambilightView = new AmbilightView(this);ambilightView.setDebug(false);root.addView(ambilightView, new FrameLayout.LayoutParams(-1,-1));
        contentOverlay = new ContentOverlayView(this);contentOverlay.setDebug(false);root.addView(contentOverlay,new FrameLayout.LayoutParams(-1,-1));
        panel = new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(18),dp(16),dp(18),dp(18));panel.setBackgroundColor(0xdd080a0d);
        TextView title = text("AmbiP · Projector v0.22",20,Color.WHITE); panel.addView(title);TextView help = text("TV Source address. Example: 192.168.1.50",12,0xffaeb7c5);help.setPadding(0,dp(6),0,dp(8)); panel.addView(help);
        source = new EditText(this);source.setTextColor(Color.WHITE); source.setHintTextColor(0xff777f8a); source.setHint("TV IP / address");source.setSingleLine(true); source.setImeOptions(EditorInfo.IME_ACTION_GO);source.setBackgroundColor(0xff20252c); source.setPadding(dp(10),dp(9),dp(10),dp(9));panel.addView(source,new LinearLayout.LayoutParams(-1,-2));
        status = text("Not connected",13,0xffffb74d); status.setPadding(0,dp(9),0,dp(4)); panel.addView(status);web = text("AmbiP Control Center: starting…",12,0xff81d4fa); web.setPadding(0,0,0,dp(9)); web.setTextIsSelectable(true); panel.addView(web);
        LinearLayout buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL);buttons.addView(button("CONNECT",v->connect()));buttons.addView(button("CALIBRATE",v->toggleCalibration()));buttons.addView(button("BLACK",v->ambilightView.setState(AmbilightState.black())));buttons.addView(button("HIDE",v->togglePanel()));panel.addView(buttons);
        TextView note = text("v0.22: explicit Save Settings, per-zone text size/alignment and two warped image boxes with API. Text/images are isolated from the low-latency light renderer.",12,0xff9aa3af);note.setPadding(0,dp(8),0,0); panel.addView(note);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(Math.min(dp(650),Math.round(getResources().getDisplayMetrics().widthPixels*0.66f)),-2);pp.gravity = Gravity.TOP|Gravity.END; pp.setMargins(dp(12),dp(12),dp(12),dp(12)); root.addView(panel,pp);
        ambilightView.setGestureListener(new AmbilightView.GestureListener() {@Override public void onSingleTap(){togglePanel();}@Override public void onLongPress(){toggleCalibration();}@Override public void onDoubleTap(){boolean d=!ambilightView.isDebug();ambilightView.setDebug(d);contentOverlay.setDebug(d);}});setContentView(root);
    }

    private void connect(){String value=source.getText().toString().trim();if(value.isEmpty()){status.setText("Enter the TV Source IP/address");return;}prefs.edit().putString(KEY_SOURCE,value).apply();status.setText("Connecting…");status.setTextColor(0xffffb74d);pendingState.set(null);if(client!=null){client.setSmoothing(prefs.getFloat("networkSmoothing",0.12f));client.connect(value);}}
    private void togglePanel(){panelVisible=!panelVisible;panel.setVisibility(panelVisible?View.VISIBLE:View.GONE);}
    private void toggleCalibration(){calibrationVisible=!calibrationVisible;prefs.edit().putBoolean("calibrationOverlay",calibrationVisible).apply();ambilightView.setDebug(calibrationVisible);contentOverlay.setDebug(calibrationVisible);if(calibrationVisible){panelVisible=true;panel.setVisibility(View.VISIBLE);status.setText("CALIBRATION · drag outer / TV / text / image boxes in Control Center");status.setTextColor(0xff81d4fa);}}
    private void applyMotionSettings(){if(interpolator==null)return;interpolator.setEnabled(prefs.getBoolean("interpolationEnabled",true));interpolator.setDurationMs(prefs.getInt("interpolationMs",30));interpolator.setAdaptive(prefs.getFloat("interpolationAdaptive",0.94f));interpolator.setRenderHz(prefs.getInt("interpolationHz",60));}

    private void applyProjectorSettings(){
        String style=prefs.getString("projectionStyle","COLOR_CLOUD");ambilightView.setProjectionStyle("EDGE_GRADIENT".equals(style)?AmbilightView.ProjectionStyle.EDGE_GRADIENT:AmbilightView.ProjectionStyle.COLOR_CLOUD);
        ambilightView.setCloudSpread(prefs.getFloat("cloudSpread",0.42f));ambilightView.setCloudRadius(prefs.getFloat("cloudRadius",0.26f));ambilightView.setCloudOpacity(prefs.getFloat("cloudOpacity",0.60f));ambilightView.setCloudSoftness(prefs.getFloat("cloudSoftness",0.72f));ambilightView.setCornerBlend(prefs.getFloat("cornerBlend",0.82f));ambilightView.setCornerRadius(prefs.getFloat("cornerRadius",1.48f));ambilightView.setCloudEdgePull(prefs.getFloat("cloudEdgePull",0.62f));ambilightView.setCloudSaturation(prefs.getFloat("cloudSaturation",1.32f));ambilightView.setCloudBrightness(prefs.getFloat("cloudBrightness",1.08f));ambilightView.setCloudDynamicAmount(prefs.getFloat("cloudDynamicAmount",0.85f));ambilightView.setCloudDynamicRadius(prefs.getFloat("cloudDynamicRadius",0.65f));ambilightView.setCloudDynamicStretch(prefs.getFloat("cloudDynamicStretch",0.85f));ambilightView.setCloudDynamicOpacity(prefs.getFloat("cloudDynamicOpacity",0.18f));ambilightView.setCloudEnergyGamma(prefs.getFloat("cloudEnergyGamma",1.15f));ambilightView.setCloudSaturationWeight(prefs.getFloat("cloudSaturationWeight",0.60f));ambilightView.setCloudLumaWeight(prefs.getFloat("cloudLumaWeight",0.40f));ambilightView.setCloudRenderScale(prefs.getFloat("cloudRenderScale",0.42f));ambilightView.setOuterFadeRatio(prefs.getFloat("outerFade",0.16f));
        ambilightView.setRgbCalibration(prefs.getFloat("rgbGainR",1f),prefs.getFloat("rgbGainG",1f),prefs.getFloat("rgbGainB",1f),prefs.getInt("rgbOffsetR",0),prefs.getInt("rgbOffsetG",0),prefs.getInt("rgbOffsetB",0));
        float[] k=loadKeystone();float[][] tf=loadTextFrames();ambilightView.setKeystoneCorners(k);ambilightView.setTvQuad(loadTvQuad());ambilightView.setTextFrames(tf);
        contentOverlay.setKeystone(k);contentOverlay.setTextFrames(tf);contentOverlay.setTextStyles(loadTextSizes(),loadTextAligns());contentOverlay.setImageFrames(loadImageFrames());
        calibrationVisible=prefs.getBoolean("calibrationOverlay",calibrationVisible);ambilightView.setDebug(calibrationVisible);contentOverlay.setDebug(calibrationVisible);
    }

    private float[] loadKeystone(){float[] d={0f,0f,1f,0f,1f,1f,0f,1f},c=new float[8];for(int i=0;i<8;i++)c[i]=prefs.getFloat("keystone"+i,d[i]);return c;}
    private float[] loadTvQuad(){float[] d={.20f,.27f,.80f,.27f,.80f,.73f,.20f,.73f},q=new float[8];for(int i=0;i<8;i++)q[i]=prefs.getFloat("tvQuad"+i,d[i]);return q;}
    private float[][] loadTextFrames(){float[][] d={{.24f,.06f,.76f,.18f},{.24f,.82f,.76f,.94f},{.03f,.32f,.18f,.68f},{.82f,.32f,.97f,.68f}},f=new float[4][4];for(int z=0;z<4;z++)for(int i=0;i<4;i++)f[z][i]=prefs.getFloat("textFrame"+z+"_"+i,d[z][i]);return f;}
    private float[] loadTextSizes(){float[] s=new float[4];for(int z=0;z<4;z++)s[z]=prefs.getFloat("textSize"+z,4.5f);return s;}
    private String[] loadTextAligns(){String[] a=new String[4];for(int z=0;z<4;z++)a[z]=prefs.getString("textAlign"+z,"center");return a;}
    private float[][] loadImageFrames(){float[][] d={{.03f,.07f,.22f,.28f},{.78f,.07f,.97f,.28f}},f=new float[2][4];for(int z=0;z<2;z++)for(int i=0;i<4;i++)f[z][i]=prefs.getFloat("imageFrame"+z+"_"+i,d[z][i]);return f;}

    private TextView button(String s, View.OnClickListener l){TextView b=text(s,12,Color.WHITE);b.setGravity(Gravity.CENTER);b.setPadding(dp(10),dp(9),dp(10),dp(9));b.setBackgroundColor(0xff303842);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.setMargins(dp(3),dp(2),dp(3),dp(2));b.setLayoutParams(p);b.setOnClickListener(l);return b;}
    private TextView text(String s,float size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);return v;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}

    @Override protected void onResume(){super.onResume();applyProjectorSettings();applyMotionSettings();if(client!=null)client.setSmoothing(prefs.getFloat("networkSmoothing",0.12f));}
    @Override protected void onDestroy(){pendingState.set(null);if(interpolator!=null)interpolator.stop();if(client!=null)client.stop();if(webServer!=null)webServer.stop();if(contentOverlay!=null)contentOverlay.shutdown();super.onDestroy();}
}
