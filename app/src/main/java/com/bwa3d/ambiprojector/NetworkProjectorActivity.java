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

/** Projector-side network mode consuming ambip-light-v1 from AmbiP TV Source. */
public final class NetworkProjectorActivity extends ComponentActivity {
    private static final String PREFS = "ambi_projector_settings";
    private static final String KEY_SOURCE = "networkTvSource";

    private FrameLayout root;
    private AmbilightView ambilightView;
    private LinearLayout panel;
    private TextView status;
    private EditText source;
    private LightStreamClient client;
    private SharedPreferences prefs;
    private boolean panelVisible = true;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        buildUi();
        applyProjectorSettings();
        client = new LightStreamClient(new LightStreamClient.Listener() {
            @Override public void onState(AmbilightState state) { runOnUiThread(() -> ambilightView.setState(state)); }
            @Override public void onStatus(String value, boolean connected) {
                runOnUiThread(() -> {
                    status.setText(value);
                    status.setTextColor(connected ? 0xff81c784 : 0xffffb74d);
                });
            }
        });
        String saved = prefs.getString(KEY_SOURCE, "");
        source.setText(saved);
        if (!saved.trim().isEmpty()) connect();
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        ambilightView = new AmbilightView(this);
        ambilightView.setDebug(false);
        root.addView(ambilightView, new FrameLayout.LayoutParams(-1,-1));

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18),dp(16),dp(18),dp(18));
        panel.setBackgroundColor(0xdd080a0d);
        TextView title = text("AmbiP · Network Projector",20,Color.WHITE); panel.addView(title);
        TextView help = text("TV Source address. Example: 192.168.1.50 or http://192.168.1.50:8080",12,0xffaeb7c5);
        help.setPadding(0,dp(6),0,dp(8)); panel.addView(help);
        source = new EditText(this);
        source.setTextColor(Color.WHITE); source.setHintTextColor(0xff777f8a); source.setHint("TV IP / address");
        source.setSingleLine(true); source.setImeOptions(EditorInfo.IME_ACTION_GO);
        source.setBackgroundColor(0xff20252c); source.setPadding(dp(10),dp(9),dp(10),dp(9));
        panel.addView(source,new LinearLayout.LayoutParams(-1,-2));
        status = text("Not connected",13,0xffffb74d); status.setPadding(0,dp(9),0,dp(9)); panel.addView(status);
        LinearLayout buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(button("CONNECT",v->connect()));
        buttons.addView(button("BLACK",v->ambilightView.setState(AmbilightState.black())));
        buttons.addView(button("HIDE",v->togglePanel()));
        panel.addView(buttons);
        TextView note = text("Renderer settings are shared with the normal Ambi Projector app. Configure Color Cloud/Layout/Keystone there; this launcher only changes the input source to TV network data.",12,0xff9aa3af);
        note.setPadding(0,dp(8),0,0); panel.addView(note);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(Math.min(dp(520),Math.round(getResources().getDisplayMetrics().widthPixels*0.55f)),-2);
        pp.gravity = Gravity.TOP|Gravity.END; pp.setMargins(dp(12),dp(12),dp(12),dp(12)); root.addView(panel,pp);

        ambilightView.setGestureListener(new AmbilightView.GestureListener() {
            @Override public void onSingleTap(){togglePanel();}
            @Override public void onLongPress(){togglePanel();}
            @Override public void onDoubleTap(){ambilightView.setDebug(!ambilightView.isDebug());}
        });
        setContentView(root);
    }

    private void connect() {
        String value = source.getText().toString().trim();
        if (value.isEmpty()) { status.setText("Enter the TV Source IP/address"); return; }
        prefs.edit().putString(KEY_SOURCE,value).apply();
        status.setText("Connecting…"); status.setTextColor(0xffffb74d);
        client.connect(value);
    }

    private void togglePanel() {
        panelVisible = !panelVisible;
        panel.setVisibility(panelVisible ? View.VISIBLE : View.GONE);
    }

    private void applyProjectorSettings() {
        String style=prefs.getString("projectionStyle","COLOR_CLOUD");
        ambilightView.setProjectionStyle("EDGE_GRADIENT".equals(style)?AmbilightView.ProjectionStyle.EDGE_GRADIENT:AmbilightView.ProjectionStyle.COLOR_CLOUD);
        ambilightView.setCloudSpread(prefs.getFloat("cloudSpread",0.42f));
        ambilightView.setCloudRadius(prefs.getFloat("cloudRadius",0.26f));
        ambilightView.setCloudOpacity(prefs.getFloat("cloudOpacity",0.60f));
        ambilightView.setCloudSoftness(prefs.getFloat("cloudSoftness",0.72f));
        ambilightView.setCornerBlend(prefs.getFloat("cornerBlend",0.82f));
        ambilightView.setCornerRadius(prefs.getFloat("cornerRadius",1.48f));
        ambilightView.setCloudEdgePull(prefs.getFloat("cloudEdgePull",0.62f));
        ambilightView.setCloudSaturation(prefs.getFloat("cloudSaturation",1.32f));
        ambilightView.setCloudBrightness(prefs.getFloat("cloudBrightness",1.08f));
        ambilightView.setCloudDynamicAmount(prefs.getFloat("cloudDynamicAmount",0.85f));
        ambilightView.setCloudDynamicRadius(prefs.getFloat("cloudDynamicRadius",0.65f));
        ambilightView.setCloudDynamicStretch(prefs.getFloat("cloudDynamicStretch",0.85f));
        ambilightView.setCloudDynamicOpacity(prefs.getFloat("cloudDynamicOpacity",0.18f));
        ambilightView.setCloudEnergyGamma(prefs.getFloat("cloudEnergyGamma",1.15f));
        ambilightView.setCloudSaturationWeight(prefs.getFloat("cloudSaturationWeight",0.60f));
        ambilightView.setCloudLumaWeight(prefs.getFloat("cloudLumaWeight",0.40f));
        ambilightView.setOuterFadeRatio(prefs.getFloat("outerFade",0.16f));
        ambilightView.setKeystoneCorners(loadKeystone());
        ambilightView.setTvRect(loadTvRect());
        ambilightView.setTextFrames(loadTextFrames());
    }

    private float[] loadKeystone(){float[] d={0f,0f,1f,0f,1f,1f,0f,1f},c=new float[8];for(int i=0;i<8;i++)c[i]=prefs.getFloat("keystone"+i,d[i]);return c;}
    private float[] loadTvRect(){float[] d={0.20f,0.27f,0.80f,0.73f},r=new float[4];for(int i=0;i<4;i++)r[i]=prefs.getFloat("projectedTv"+i,d[i]);return r;}
    private float[][] loadTextFrames(){float[][] d={{0.24f,0.06f,0.76f,0.18f},{0.24f,0.82f,0.76f,0.94f},{0.03f,0.32f,0.18f,0.68f},{0.82f,0.32f,0.97f,0.68f}},f=new float[4][4];for(int z=0;z<4;z++)for(int i=0;i<4;i++)f[z][i]=prefs.getFloat("textFrame"+z+"_"+i,d[z][i]);return f;}

    private TextView button(String s, View.OnClickListener l){TextView b=text(s,13,Color.WHITE);b.setGravity(Gravity.CENTER);b.setPadding(dp(12),dp(9),dp(12),dp(9));b.setBackgroundColor(0xff303842);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.setMargins(dp(3),dp(2),dp(3),dp(2));b.setLayoutParams(p);b.setOnClickListener(l);return b;}
    private TextView text(String s,float size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);return v;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}

    @Override protected void onResume(){super.onResume();applyProjectorSettings();}
    @Override protected void onDestroy(){if(client!=null)client.stop();super.onDestroy();}
}
