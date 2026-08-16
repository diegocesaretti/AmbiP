package com.bwa3d.ambiprojector;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
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
    private static final String KEY_AUTO_START = "projectorAutoStart";

    private FrameLayout root;
    private AmbilightView ambilightView;
    private ContentOverlayView contentOverlay;
    private LinearLayout panel;
    private TextView status, web;
    private EditText source;
    private TextView connectButton, calibrateButton, blackButton, hideButton, autoStartButton;
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
                runOnUiThread(() -> {applyProjectorSettings();applyMotionSettings();if(client!=null)client.setSmoothing(prefs.getFloat("networkSmoothing",0.12f));updateAutoStartButton();});
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

        updateAutoStartButton();
        requestInitialFocus();
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
        panel = new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(22),dp(18),dp(22),dp(20));panel.setBackgroundColor(0xe6080a0d);
        TextView title = text("AmbiP · Projector v0.23",22,Color.WHITE); panel.addView(title);TextView help = text("Remote ready · D-pad to move, OK to select. TV Source address:",13,0xffb7c2cf);help.setPadding(0,dp(7),0,dp(9)); panel.addView(help);

        source = new EditText(this);source.setId(View.generateViewId());source.setTextColor(Color.WHITE); source.setHintTextColor(0xff8b96a3); source.setHint("TV IP / address");source.setSingleLine(true); source.setImeOptions(EditorInfo.IME_ACTION_GO);source.setPadding(dp(13),dp(11),dp(13),dp(11));source.setFocusable(true);source.setFocusableInTouchMode(true);source.setOnFocusChangeListener((v,focused)->styleSource(focused));styleSource(false);panel.addView(source,new LinearLayout.LayoutParams(-1,-2));
        status = text("Not connected",14,0xffffb74d); status.setPadding(0,dp(10),0,dp(4)); panel.addView(status);web = text("AmbiP Control Center: starting…",12,0xff81d4fa); web.setPadding(0,0,0,dp(10)); web.setTextIsSelectable(true); panel.addView(web);

        LinearLayout buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL);
        connectButton=button("CONNECT",v->connect());calibrateButton=button("CALIBRATE",v->toggleCalibration());blackButton=button("BLACK",v->ambilightView.setState(AmbilightState.black()));hideButton=button("HIDE",v->togglePanel());
        buttons.addView(connectButton);buttons.addView(calibrateButton);buttons.addView(blackButton);buttons.addView(hideButton);panel.addView(buttons);

        LinearLayout secondRow=new LinearLayout(this);secondRow.setOrientation(LinearLayout.HORIZONTAL);autoStartButton=button("AUTO START: OFF",v->toggleAutoStart());secondRow.addView(autoStartButton);panel.addView(secondRow);
        wireRemoteNavigation();

        TextView note = text("v0.23: projector Auto Start + full TV-remote focus. If the panel is hidden, press any D-pad/OK key to bring it back.",12,0xffa6b0bc);note.setPadding(0,dp(10),0,0); panel.addView(note);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(Math.min(dp(700),Math.round(getResources().getDisplayMetrics().widthPixels*0.70f)),-2);pp.gravity = Gravity.TOP|Gravity.END; pp.setMargins(dp(16),dp(16),dp(16),dp(16)); root.addView(panel,pp);
        ambilightView.setGestureListener(new AmbilightView.GestureListener() {@Override public void onSingleTap(){togglePanel();}@Override public void onLongPress(){toggleCalibration();}@Override public void onDoubleTap(){boolean d=!ambilightView.isDebug();ambilightView.setDebug(d);contentOverlay.setDebug(d);}});setContentView(root);
    }

    private void wireRemoteNavigation(){
        connectButton.setId(View.generateViewId());calibrateButton.setId(View.generateViewId());blackButton.setId(View.generateViewId());hideButton.setId(View.generateViewId());autoStartButton.setId(View.generateViewId());
        source.setNextFocusDownId(connectButton.getId());
        connectButton.setNextFocusUpId(source.getId());connectButton.setNextFocusRightId(calibrateButton.getId());connectButton.setNextFocusDownId(autoStartButton.getId());
        calibrateButton.setNextFocusUpId(source.getId());calibrateButton.setNextFocusLeftId(connectButton.getId());calibrateButton.setNextFocusRightId(blackButton.getId());calibrateButton.setNextFocusDownId(autoStartButton.getId());
        blackButton.setNextFocusUpId(source.getId());blackButton.setNextFocusLeftId(calibrateButton.getId());blackButton.setNextFocusRightId(hideButton.getId());blackButton.setNextFocusDownId(autoStartButton.getId());
        hideButton.setNextFocusUpId(source.getId());hideButton.setNextFocusLeftId(blackButton.getId());hideButton.setNextFocusDownId(autoStartButton.getId());
        autoStartButton.setNextFocusUpId(connectButton.getId());
    }

    private void connect(){String value=source.getText().toString().trim();if(value.isEmpty()){status.setText("Enter the TV Source IP/address");return;}prefs.edit().putString(KEY_SOURCE,value).apply();status.setText("Connecting…");status.setTextColor(0xffffb74d);pendingState.set(null);if(client!=null){client.setSmoothing(prefs.getFloat("networkSmoothing",0.12f));client.connect(value);}}
    private void togglePanel(){panelVisible=!panelVisible;panel.setVisibility(panelVisible?View.VISIBLE:View.GONE);if(panelVisible)requestInitialFocus();}
    private void showPanelForRemote(){if(panelVisible)return;panelVisible=true;panel.setVisibility(View.VISIBLE);requestInitialFocus();}
    private void requestInitialFocus(){if(connectButton!=null)connectButton.post(connectButton::requestFocus);}
    private void toggleCalibration(){calibrationVisible=!calibrationVisible;prefs.edit().putBoolean("calibrationOverlay",calibrationVisible).apply();ambilightView.setDebug(calibrationVisible);contentOverlay.setDebug(calibrationVisible);if(calibrationVisible){panelVisible=true;panel.setVisibility(View.VISIBLE);status.setText("CALIBRATION · drag outer / TV / text / image boxes in Control Center");status.setTextColor(0xff81d4fa);requestInitialFocus();}}
    private void toggleAutoStart(){boolean enabled=!prefs.getBoolean(KEY_AUTO_START,false);prefs.edit().putBoolean(KEY_AUTO_START,enabled).commit();updateAutoStartButton();status.setText(enabled?"AUTO START enabled · projector will try to launch AmbiP after boot":"AUTO START disabled");status.setTextColor(enabled?0xff81c784:0xffffb74d);}
    private void updateAutoStartButton(){if(autoStartButton==null)return;boolean enabled=prefs.getBoolean(KEY_AUTO_START,false);autoStartButton.setText(enabled?"AUTO START: ON":"AUTO START: OFF");}
    private void applyMotionSettings(){if(interpolator==null)return;interpolator.setEnabled(prefs.getBoolean("interpolationEnabled",true));interpolator.setDurationMs(prefs.getInt("interpolationMs",30));interpolator.setAdaptive(prefs.getFloat("interpolationAdaptive",0.94f));interpolator.setRenderHz(prefs.getInt("interpolationHz",60));}

    @Override public boolean dispatchKeyEvent(KeyEvent event){
        int key=event.getKeyCode();boolean remoteKey=key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN||key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT||key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER;
        if(event.getAction()==KeyEvent.ACTION_DOWN&&key==KeyEvent.KEYCODE_MENU){togglePanel();return true;}
        if(event.getAction()==KeyEvent.ACTION_DOWN&&!panelVisible&&remoteKey){showPanelForRemote();return true;}
        return super.dispatchKeyEvent(event);
    }

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

    private TextView button(String s, View.OnClickListener l){
        TextView b=text(s,13,Color.WHITE);b.setGravity(Gravity.CENTER);b.setPadding(dp(13),dp(11),dp(13),dp(11));b.setFocusable(true);b.setFocusableInTouchMode(true);b.setClickable(true);b.setOnClickListener(l);b.setOnFocusChangeListener((v,focused)->styleButton(b,focused));b.setOnKeyListener((v,key,event)->{if(event.getAction()==KeyEvent.ACTION_UP&&(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER)){b.performClick();return true;}return false;});styleButton(b,false);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.setMargins(dp(5),dp(4),dp(5),dp(4));b.setLayoutParams(p);return b;
    }
    private void styleButton(TextView b,boolean focused){GradientDrawable d=new GradientDrawable();d.setCornerRadius(dp(10));d.setColor(focused?0xff00a8d6:0xff303842);d.setStroke(dp(focused?3:1),focused?Color.WHITE:0xff526172);b.setBackground(d);b.setTextColor(Color.WHITE);b.setScaleX(focused?1.07f:1f);b.setScaleY(focused?1.07f:1f);b.setElevation(focused?dp(8):dp(1));}
    private void styleSource(boolean focused){GradientDrawable d=new GradientDrawable();d.setCornerRadius(dp(9));d.setColor(focused?0xff163d4a:0xff20252c);d.setStroke(dp(focused?3:1),focused?0xff81d4fa:0xff465362);source.setBackground(d);source.setScaleX(focused?1.015f:1f);source.setScaleY(focused?1.015f:1f);}
    private TextView text(String s,float size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);return v;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}

    @Override protected void onResume(){super.onResume();applyProjectorSettings();applyMotionSettings();updateAutoStartButton();if(client!=null)client.setSmoothing(prefs.getFloat("networkSmoothing",0.12f));}
    @Override protected void onDestroy(){pendingState.set(null);if(interpolator!=null)interpolator.stop();if(client!=null)client.stop();if(webServer!=null)webServer.stop();if(contentOverlay!=null)contentOverlay.shutdown();super.onDestroy();}
}
