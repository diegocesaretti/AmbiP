package com.bwa3d.ambiprojector;

import android.content.SharedPreferences;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tiny HTTP-only settings server for configuring the projector from a phone. */
public final class ProjectorWebServer {
    public interface Listener {
        void onSettingsChanged();
        void onSourceChanged(String source);
    }

    public static final int PORT = 8081;
    private final SharedPreferences prefs;
    private final Listener listener;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private volatile boolean running;
    private ServerSocket server;
    private String url = "http://127.0.0.1:" + PORT;

    public ProjectorWebServer(SharedPreferences prefs, Listener listener) {
        this.prefs = prefs;
        this.listener = listener;
    }

    public synchronized String start() throws IOException {
        if (running) return url;
        server = new ServerSocket(PORT, 16, InetAddress.getByName("0.0.0.0"));
        server.setReuseAddress(true);
        running = true;
        List<String> ips = localIpv4Addresses();
        if (!ips.isEmpty()) url = "http://" + ips.get(0) + ":" + PORT;
        pool.execute(this::acceptLoop);
        return url;
    }

    public synchronized void stop() {
        running = false;
        if (server != null) try { server.close(); } catch (IOException ignored) {}
        pool.shutdownNow();
    }

    public String getUrl() { return url; }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                pool.execute(() -> handle(s));
            } catch (IOException ignored) { if (!running) break; }
        }
    }

    private void handle(Socket socket) {
        try {
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            String line = readLine(in);
            if (line == null) return;
            String[] first = line.split(" ");
            String raw = first.length > 1 ? first[1] : "/";
            while ((line = readLine(in)) != null && line.length() > 0) {}
            String path = raw, query = "";
            int q = raw.indexOf('?');
            if (q >= 0) { path = raw.substring(0,q); query = raw.substring(q+1); }
            if ("/api/settings".equals(path)) {
                apply(parseQuery(query));
                send(socket,"application/json; charset=utf-8",settingsJson().getBytes(StandardCharsets.UTF_8));
            } else if ("/api/status".equals(path)) {
                send(socket,"application/json; charset=utf-8",settingsJson().getBytes(StandardCharsets.UTF_8));
            } else {
                send(socket,"text/html; charset=utf-8",PAGE.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void apply(Map<String,String> q) {
        SharedPreferences.Editor e = prefs.edit();
        String source = q.get("source");
        if (source != null) e.putString("networkTvSource", source.trim());

        putFloat(e,q,"smooth","networkSmoothing",0f,0.80f);
        putBoolean(e,q,"interp","interpolationEnabled");
        putInt(e,q,"interpms","interpolationMs",0,180);
        putFloat(e,q,"adaptive","interpolationAdaptive",0f,1f);
        putInt(e,q,"hz","interpolationHz",30,120);

        putFloat(e,q,"brightness","cloudBrightness",0.40f,1.80f);
        putFloat(e,q,"saturation","cloudSaturation",0.50f,2.50f);
        putFloat(e,q,"spread","cloudSpread",0.05f,0.90f);
        putFloat(e,q,"radius","cloudRadius",0.08f,0.50f);
        putFloat(e,q,"opacity","cloudOpacity",0.05f,1.00f);
        putFloat(e,q,"dynamic","cloudDynamicAmount",0f,1.50f);
        putFloat(e,q,"stretch","cloudDynamicStretch",0f,2.00f);
        putFloat(e,q,"fade","outerFade",0.02f,0.42f);
        putFloat(e,q,"edgepull","cloudEdgePull",0f,1f);
        putFloat(e,q,"softness","cloudSoftness",0f,1f);
        putFloat(e,q,"cornerblend","cornerBlend",0f,1f);
        putFloat(e,q,"cornerradius","cornerRadius",0.70f,2.40f);

        putFloat(e,q,"tv0","projectedTv0",0f,0.94f);
        putFloat(e,q,"tv1","projectedTv1",0f,0.94f);
        putFloat(e,q,"tv2","projectedTv2",0.06f,1f);
        putFloat(e,q,"tv3","projectedTv3",0.06f,1f);
        for(int i=0;i<8;i++) putFloat(e,q,"k"+i,"keystone"+i,0f,1f);
        putBoolean(e,q,"calibrate","calibrationOverlay");
        e.apply();

        sanitizeTvRect();
        if (listener != null) {
            listener.onSettingsChanged();
            if (source != null) listener.onSourceChanged(source.trim());
        }
    }

    private void sanitizeTvRect() {
        float l=prefs.getFloat("projectedTv0",0.20f),t=prefs.getFloat("projectedTv1",0.27f);
        float r=prefs.getFloat("projectedTv2",0.80f),b=prefs.getFloat("projectedTv3",0.73f);
        boolean changed=false;
        if(r-l<0.08f){r=Math.min(1f,l+0.08f);changed=true;}
        if(b-t<0.08f){b=Math.min(1f,t+0.08f);changed=true;}
        if(changed) prefs.edit().putFloat("projectedTv0",l).putFloat("projectedTv1",t).putFloat("projectedTv2",r).putFloat("projectedTv3",b).apply();
    }

    private static void putFloat(SharedPreferences.Editor e,Map<String,String> q,String key,String pref,float lo,float hi){String s=q.get(key);if(s==null)return;try{float v=Float.parseFloat(s);e.putFloat(pref,Math.max(lo,Math.min(hi,v)));}catch(Exception ignored){}}
    private static void putInt(SharedPreferences.Editor e,Map<String,String> q,String key,String pref,int lo,int hi){String s=q.get(key);if(s==null)return;try{int v=Integer.parseInt(s);e.putInt(pref,Math.max(lo,Math.min(hi,v)));}catch(Exception ignored){}}
    private static void putBoolean(SharedPreferences.Editor e,Map<String,String> q,String key,String pref){String s=q.get(key);if(s!=null)e.putBoolean(pref,"1".equals(s)||"true".equalsIgnoreCase(s)||"on".equalsIgnoreCase(s));}

    private String settingsJson() {
        StringBuilder s=new StringBuilder(1500);
        s.append('{');
        s.append("\"source\":\"").append(escape(prefs.getString("networkTvSource",""))).append("\",");
        s.append("\"smooth\":").append(f(prefs.getFloat("networkSmoothing",0.15f))).append(',');
        s.append("\"interp\":").append(prefs.getBoolean("interpolationEnabled",true)).append(',');
        s.append("\"interpms\":").append(prefs.getInt("interpolationMs",78)).append(',');
        s.append("\"adaptive\":").append(f(prefs.getFloat("interpolationAdaptive",0.72f))).append(',');
        s.append("\"hz\":").append(prefs.getInt("interpolationHz",60)).append(',');
        s.append("\"brightness\":").append(f(prefs.getFloat("cloudBrightness",1.08f))).append(',');
        s.append("\"saturation\":").append(f(prefs.getFloat("cloudSaturation",1.32f))).append(',');
        s.append("\"spread\":").append(f(prefs.getFloat("cloudSpread",0.42f))).append(',');
        s.append("\"radius\":").append(f(prefs.getFloat("cloudRadius",0.26f))).append(',');
        s.append("\"opacity\":").append(f(prefs.getFloat("cloudOpacity",0.60f))).append(',');
        s.append("\"dynamic\":").append(f(prefs.getFloat("cloudDynamicAmount",0.85f))).append(',');
        s.append("\"stretch\":").append(f(prefs.getFloat("cloudDynamicStretch",0.85f))).append(',');
        s.append("\"fade\":").append(f(prefs.getFloat("outerFade",0.16f))).append(',');
        s.append("\"edgepull\":").append(f(prefs.getFloat("cloudEdgePull",0.62f))).append(',');
        s.append("\"softness\":").append(f(prefs.getFloat("cloudSoftness",0.72f))).append(',');
        s.append("\"cornerblend\":").append(f(prefs.getFloat("cornerBlend",0.82f))).append(',');
        s.append("\"cornerradius\":").append(f(prefs.getFloat("cornerRadius",1.48f))).append(',');
        for(int i=0;i<4;i++)s.append("\"tv").append(i).append("\":").append(f(prefs.getFloat("projectedTv"+i,new float[]{0.20f,0.27f,0.80f,0.73f}[i]))).append(',');
        float[] kd={0f,0f,1f,0f,1f,1f,0f,1f};
        for(int i=0;i<8;i++)s.append("\"k").append(i).append("\":").append(f(prefs.getFloat("keystone"+i,kd[i]))).append(',');
        s.append("\"calibrate\":").append(prefs.getBoolean("calibrationOverlay",false));
        s.append('}');return s.toString();
    }

    private static String f(float v){return String.format(Locale.US,"%.3f",v);}
    private static String escape(String s){if(s==null)return"";return s.replace("\\","\\\\").replace("\"","\\\"");}

    private static Map<String,String> parseQuery(String query){Map<String,String> out=new HashMap<>();if(query==null)return out;for(String p:query.split("&")){int x=p.indexOf('=');String k=x<0?p:p.substring(0,x);String v=x<0?"":p.substring(x+1);try{out.put(URLDecoder.decode(k,"UTF-8"),URLDecoder.decode(v,"UTF-8"));}catch(Exception ignored){}}return out;}
    private static String readLine(InputStream in)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();int c;boolean cr=false;while((c=in.read())>=0){if(cr&&c=='\n')break;if(cr){b.write('\r');cr=false;}if(c=='\r')cr=true;else b.write(c);if(b.size()>16384)break;}if(c<0&&b.size()==0)return null;return new String(b.toByteArray(),StandardCharsets.UTF_8);}
    private static void send(Socket s,String type,byte[] body)throws IOException{BufferedOutputStream out=new BufferedOutputStream(s.getOutputStream());String h="HTTP/1.1 200 OK\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";out.write(h.getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();}

    private static List<String> localIpv4Addresses(){List<String[]> found=new ArrayList<>();try{for(NetworkInterface ni:Collections.list(NetworkInterface.getNetworkInterfaces())){if(!ni.isUp()||ni.isLoopback())continue;String name=ni.getName()==null?"":ni.getName().toLowerCase(Locale.US);if(name.startsWith("tun")||name.startsWith("rmnet"))continue;for(InetAddress a:Collections.list(ni.getInetAddresses())){if(!(a instanceof Inet4Address)||a.isLoopbackAddress()||a.isLinkLocalAddress())continue;int score=(name.startsWith("wlan")||name.startsWith("wifi"))?0:(name.startsWith("eth")?1:2);found.add(new String[]{String.valueOf(score),a.getHostAddress()});}}}catch(Throwable ignored){}Collections.sort(found,new Comparator<String[]>(){@Override public int compare(String[] a,String[] b){return Integer.compare(Integer.parseInt(a[0]),Integer.parseInt(b[0]));}});List<String> out=new ArrayList<>();for(String[] x:found)if(x[1]!=null&&!out.contains(x[1]))out.add(x[1]);return out;}

    private static final String PAGE=""+
"<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>AmbiP Projector</title><style>"+
"body{background:#090b0e;color:#eee;font-family:system-ui,sans-serif;max-width:820px;margin:auto;padding:18px}.card{background:#15191f;padding:16px;border-radius:12px;margin:12px 0}.grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}.g4{display:grid;grid-template-columns:repeat(4,1fr);gap:8px}input{width:100%}.text{padding:10px;width:100%;background:#222;color:#fff;border:0}.v{float:right;color:#9fd}.note{color:#aaa;font-size:13px}.small{font-size:12px;color:#9aa}.toggle{width:auto}h2,h3{margin:6px 0 12px}@media(max-width:650px){.grid,.g4{grid-template-columns:1fr 1fr}}"+
"</style></head><body><h2>AmbiP Projector v0.17</h2>"+
"<div class='card'><h3>Connection</h3><input id='source' class='text' placeholder='TV Source IP, e.g. 192.168.1.50'></div>"+
"<div class='card'><h3>Motion / latency</h3><p><label><input id='interp' class='toggle' type='checkbox'> 60 Hz interpolation</label></p>"+
"<p>Input smoothing <span id='smoothv' class='v'></span><input id='smooth' type='range' min='0' max='.8' step='.01'></p>"+
"<p>Interpolation time <span id='interpmsv' class='v'></span><input id='interpms' type='range' min='0' max='180' step='2'></p>"+
"<p>Adaptive fast response <span id='adaptivev' class='v'></span><input id='adaptive' type='range' min='0' max='1' step='.01'></p>"+
"<p>Render Hz <span id='hzv' class='v'></span><input id='hz' type='range' min='30' max='120' step='10'></p>"+
"<p class='note'>Interpolation never waits for a future TV frame. Large flashes automatically use a shorter catch-up time.</p></div>"+
"<div class='card'><h3>Color Cloud / TV border</h3>"+
"<div class='grid'><p>Brightness <span id='brightnessv' class='v'></span><input id='brightness' type='range' min='.4' max='1.8' step='.01'></p><p>Saturation <span id='saturationv' class='v'></span><input id='saturation' type='range' min='.5' max='2.5' step='.01'></p>"+
"<p>Spread <span id='spreadv' class='v'></span><input id='spread' type='range' min='.05' max='.9' step='.01'></p><p>Radius <span id='radiusv' class='v'></span><input id='radius' type='range' min='.08' max='.5' step='.01'></p>"+
"<p>Opacity <span id='opacityv' class='v'></span><input id='opacity' type='range' min='.05' max='1' step='.01'></p><p>Edge pull <span id='edgepullv' class='v'></span><input id='edgepull' type='range' min='0' max='1' step='.01'></p>"+
"<p>Softness <span id='softnessv' class='v'></span><input id='softness' type='range' min='0' max='1' step='.01'></p><p>Corner blend <span id='cornerblendv' class='v'></span><input id='cornerblend' type='range' min='0' max='1' step='.01'></p>"+
"<p>Corner radius <span id='cornerradiusv' class='v'></span><input id='cornerradius' type='range' min='.7' max='2.4' step='.01'></p><p>Outer fade <span id='fadev' class='v'></span><input id='fade' type='range' min='.02' max='.42' step='.01'></p>"+
"<p>Dynamic <span id='dynamicv' class='v'></span><input id='dynamic' type='range' min='0' max='1.5' step='.01'></p><p>Stretch <span id='stretchv' class='v'></span><input id='stretch' type='range' min='0' max='2' step='.01'></p></div></div>"+
"<div class='card'><h3>TV mask / borders</h3><p><label><input id='calibrate' class='toggle' type='checkbox'> Show calibration overlay on projector</label></p>"+
"<div class='g4'><p>Left <span id='tv0v' class='v'></span><input id='tv0' type='range' min='0' max='.9' step='.002'></p><p>Top <span id='tv1v' class='v'></span><input id='tv1' type='range' min='0' max='.9' step='.002'></p><p>Right <span id='tv2v' class='v'></span><input id='tv2' type='range' min='.1' max='1' step='.002'></p><p>Bottom <span id='tv3v' class='v'></span><input id='tv3' type='range' min='.1' max='1' step='.002'></p></div>"+
"<p class='note'>These four controls place the black TV hole precisely. Use Corner/Edge controls above to tune how light leaves the TV border.</p></div>"+
"<div class='card'><h3>Outer projector corners / keystone</h3><div class='grid'>"+
"<p>Top-left X <span id='k0v' class='v'></span><input id='k0' type='range' min='0' max='1' step='.002'></p><p>Top-left Y <span id='k1v' class='v'></span><input id='k1' type='range' min='0' max='1' step='.002'></p>"+
"<p>Top-right X <span id='k2v' class='v'></span><input id='k2' type='range' min='0' max='1' step='.002'></p><p>Top-right Y <span id='k3v' class='v'></span><input id='k3' type='range' min='0' max='1' step='.002'></p>"+
"<p>Bottom-right X <span id='k4v' class='v'></span><input id='k4' type='range' min='0' max='1' step='.002'></p><p>Bottom-right Y <span id='k5v' class='v'></span><input id='k5' type='range' min='0' max='1' step='.002'></p>"+
"<p>Bottom-left X <span id='k6v' class='v'></span><input id='k6' type='range' min='0' max='1' step='.002'></p><p>Bottom-left Y <span id='k7v' class='v'></span><input id='k7' type='range' min='0' max='1' step='.002'></p></div></div>"+
"<script>const g=x=>document.getElementById(x);const sliders=['smooth','interpms','adaptive','hz','brightness','saturation','spread','radius','opacity','dynamic','stretch','fade','edgepull','softness','cornerblend','cornerradius','tv0','tv1','tv2','tv3','k0','k1','k2','k3','k4','k5','k6','k7'];function labels(){sliders.forEach(k=>g(k+'v').textContent=(k==='interpms'||k==='hz')?Math.round(Number(g(k).value)):Number(g(k).value).toFixed(k.startsWith('tv')||k.startsWith('k')?3:2))}async function load(){let s=await(await fetch('/api/status')).json();g('source').value=s.source||'';g('interp').checked=!!s.interp;g('calibrate').checked=!!s.calibrate;sliders.forEach(k=>g(k).value=s[k]);labels()}let t;function save(){labels();clearTimeout(t);t=setTimeout(()=>{let q='source='+encodeURIComponent(g('source').value)+'&interp='+(g('interp').checked?1:0)+'&calibrate='+(g('calibrate').checked?1:0);sliders.forEach(k=>q+='&'+k+'='+g(k).value);fetch('/api/settings?'+q)},70)}sliders.forEach(k=>g(k).oninput=save);g('interp').onchange=save;g('calibrate').onchange=save;g('source').onchange=save;load()</script></body></html>";
}
