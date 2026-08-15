package com.bwa3d.ambiprojector;

import android.content.SharedPreferences;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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

/**
 * AmbiP Control Center. Serves projector settings and proxies the lightweight TV Source control API,
 * so the phone only needs one portal: http://PROJECTOR:8081.
 */
public final class ProjectorWebServer {
    public interface Listener {
        void onSettingsChanged();
        void onSourceChanged(String source);
    }

    public static final int PORT = 8081;
    private static final int SOURCE_PORT = 8080;
    private final SharedPreferences prefs;
    private final Listener listener;
    private final ExecutorService pool = Executors.newFixedThreadPool(5);
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
                s.setSoTimeout(2500);
                pool.execute(() -> handle(s));
            } catch (IOException ignored) {
                if (!running) break;
            }
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
            if (q >= 0) { path = raw.substring(0, q); query = raw.substring(q + 1); }

            if ("/api/settings".equals(path)) {
                applyProjector(parseQuery(query));
                send(socket, "application/json; charset=utf-8", settingsJson().getBytes(StandardCharsets.UTF_8));
            } else if ("/api/status".equals(path)) {
                send(socket, "application/json; charset=utf-8", settingsJson().getBytes(StandardCharsets.UTF_8));
            } else if ("/api/source/status".equals(path)) {
                send(socket, "application/json; charset=utf-8", proxySource("/api/status").getBytes(StandardCharsets.UTF_8));
            } else if ("/api/source/settings".equals(path)) {
                String suffix = query.isEmpty() ? "" : "?" + query;
                send(socket, "application/json; charset=utf-8", proxySource("/api/settings" + suffix).getBytes(StandardCharsets.UTF_8));
            } else if ("/health".equals(path)) {
                send(socket, "text/plain; charset=utf-8", "OK AmbiP Control Center\n".getBytes(StandardCharsets.UTF_8));
            } else {
                send(socket, "text/html; charset=utf-8", PAGE.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void applyProjector(Map<String, String> q) {
        SharedPreferences.Editor e = prefs.edit();
        String source = q.get("source");
        if (source != null) e.putString("networkTvSource", source.trim());
        String style = q.get("style");
        if (style != null) e.putString("projectionStyle", "EDGE_GRADIENT".equals(style) ? "EDGE_GRADIENT" : "COLOR_CLOUD");

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
        putFloat(e,q,"edgepull","cloudEdgePull",0f,1f);
        putFloat(e,q,"softness","cloudSoftness",0f,1f);
        putFloat(e,q,"cornerblend","cornerBlend",0f,1f);
        putFloat(e,q,"cornerradius","cornerRadius",0.70f,2.40f);
        putFloat(e,q,"dynamic","cloudDynamicAmount",0f,1.50f);
        putFloat(e,q,"dynradius","cloudDynamicRadius",0f,1.50f);
        putFloat(e,q,"stretch","cloudDynamicStretch",0f,2.00f);
        putFloat(e,q,"dynopacity","cloudDynamicOpacity",0f,0.80f);
        putFloat(e,q,"energygamma","cloudEnergyGamma",0.40f,2.50f);
        putFloat(e,q,"satweight","cloudSaturationWeight",0f,1f);
        putFloat(e,q,"lumaweight","cloudLumaWeight",0f,1f);
        putFloat(e,q,"fade","outerFade",0.02f,0.42f);

        putFloat(e,q,"tv0","projectedTv0",0f,0.94f);
        putFloat(e,q,"tv1","projectedTv1",0f,0.94f);
        putFloat(e,q,"tv2","projectedTv2",0.06f,1f);
        putFloat(e,q,"tv3","projectedTv3",0.06f,1f);
        for (int i=0;i<8;i++) putFloat(e,q,"k"+i,"keystone"+i,0f,1f);
        putBoolean(e,q,"calibrate","calibrationOverlay");

        for (int z=0;z<4;z++) {
            for (int i=0;i<4;i++) putFloat(e,q,"tf"+z+"_"+i,"textFrame"+z+"_"+i,0f,1f);
        }
        e.apply();

        sanitizeTvRect();
        sanitizeTextFrames();
        if (listener != null) {
            listener.onSettingsChanged();
            if (source != null) listener.onSourceChanged(source.trim());
        }
    }

    private void sanitizeTvRect() {
        float l=prefs.getFloat("projectedTv0",0.20f), t=prefs.getFloat("projectedTv1",0.27f);
        float r=prefs.getFloat("projectedTv2",0.80f), b=prefs.getFloat("projectedTv3",0.73f);
        if (r-l < 0.08f) r=Math.min(1f,l+0.08f);
        if (b-t < 0.08f) b=Math.min(1f,t+0.08f);
        if (r-l < 0.08f) l=Math.max(0f,r-0.08f);
        if (b-t < 0.08f) t=Math.max(0f,b-0.08f);
        prefs.edit().putFloat("projectedTv0",l).putFloat("projectedTv1",t)
                .putFloat("projectedTv2",r).putFloat("projectedTv3",b).apply();
    }

    private void sanitizeTextFrames() {
        float[][] defaults={{0.24f,0.06f,0.76f,0.18f},{0.24f,0.82f,0.76f,0.94f},{0.03f,0.32f,0.18f,0.68f},{0.82f,0.32f,0.97f,0.68f}};
        SharedPreferences.Editor e=prefs.edit();
        for(int z=0;z<4;z++) {
            float l=prefs.getFloat("textFrame"+z+"_0",defaults[z][0]);
            float t=prefs.getFloat("textFrame"+z+"_1",defaults[z][1]);
            float r=prefs.getFloat("textFrame"+z+"_2",defaults[z][2]);
            float b=prefs.getFloat("textFrame"+z+"_3",defaults[z][3]);
            if(r-l<0.05f)r=Math.min(1f,l+0.05f); if(b-t<0.05f)b=Math.min(1f,t+0.05f);
            if(r-l<0.05f)l=Math.max(0f,r-0.05f); if(b-t<0.05f)t=Math.max(0f,b-0.05f);
            e.putFloat("textFrame"+z+"_0",l).putFloat("textFrame"+z+"_1",t)
                    .putFloat("textFrame"+z+"_2",r).putFloat("textFrame"+z+"_3",b);
        }
        e.apply();
    }

    private String proxySource(String sourcePath) {
        String raw = prefs.getString("networkTvSource", "");
        Endpoint endpoint = endpoint(raw);
        if (endpoint == null) return "{\"reachable\":false,\"error\":\"TV Source address not configured\"}";
        long started=System.nanoTime();
        try {
            String body = rawHttpGet(endpoint, sourcePath);
            long rtt=Math.max(0L,Math.round((System.nanoTime()-started)/1_000_000.0));
            String trimmed=body==null?"":body.trim();
            if(trimmed.isEmpty()) trimmed="{}";
            return "{\"reachable\":true,\"rttMs\":"+rtt+",\"data\":"+trimmed+"}";
        } catch (Throwable t) {
            return "{\"reachable\":false,\"error\":\""+escape(t.getClass().getSimpleName())+"\"}";
        }
    }

    private static String rawHttpGet(Endpoint ep, String path) throws IOException {
        Socket s=new Socket();
        try {
            s.connect(new InetSocketAddress(ep.host,ep.port),550);
            s.setSoTimeout(1000);
            s.setTcpNoDelay(true);
            OutputStream out=s.getOutputStream();
            String request="GET "+path+" HTTP/1.1\r\nHost: "+ep.host+"\r\nConnection: close\r\nCache-Control: no-cache\r\n\r\n";
            out.write(request.getBytes(StandardCharsets.US_ASCII)); out.flush();
            BufferedInputStream in=new BufferedInputStream(s.getInputStream());
            String status=readLine(in);
            if(status==null||!status.contains(" 200 ")) throw new IOException("HTTP response");
            String line;
            while((line=readLine(in))!=null&&line.length()>0){}
            ByteArrayOutputStream body=new ByteArrayOutputStream(2048);
            byte[] buf=new byte[1024]; int n,total=0;
            while((n=in.read(buf))>=0){if(n==0)continue;body.write(buf,0,n);total+=n;if(total>65536)break;}
            return new String(body.toByteArray(),StandardCharsets.UTF_8);
        } finally { try{s.close();}catch(IOException ignored){} }
    }

    private static Endpoint endpoint(String raw) {
        if(raw==null)return null; String s=raw.trim(); if(s.isEmpty())return null;
        int scheme=s.indexOf("://"); if(scheme>=0)s=s.substring(scheme+3);
        int slash=s.indexOf('/'); if(slash>=0)s=s.substring(0,slash);
        int port=SOURCE_PORT; String host=s;
        int colon=s.lastIndexOf(':');
        if(colon>0&&s.indexOf(':')==colon){try{port=Integer.parseInt(s.substring(colon+1));host=s.substring(0,colon);}catch(Exception ignored){}}
        if(host.isEmpty())return null; return new Endpoint(host,port);
    }

    private static final class Endpoint { final String host; final int port; Endpoint(String h,int p){host=h;port=p;} }

    private String settingsJson() {
        StringBuilder s=new StringBuilder(2600); s.append('{');
        s.append("\"source\":\"").append(escape(prefs.getString("networkTvSource",""))).append("\",");
        s.append("\"style\":\"").append(escape(prefs.getString("projectionStyle","COLOR_CLOUD"))).append("\",");
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
        s.append("\"edgepull\":").append(f(prefs.getFloat("cloudEdgePull",0.62f))).append(',');
        s.append("\"softness\":").append(f(prefs.getFloat("cloudSoftness",0.72f))).append(',');
        s.append("\"cornerblend\":").append(f(prefs.getFloat("cornerBlend",0.82f))).append(',');
        s.append("\"cornerradius\":").append(f(prefs.getFloat("cornerRadius",1.48f))).append(',');
        s.append("\"dynamic\":").append(f(prefs.getFloat("cloudDynamicAmount",0.85f))).append(',');
        s.append("\"dynradius\":").append(f(prefs.getFloat("cloudDynamicRadius",0.65f))).append(',');
        s.append("\"stretch\":").append(f(prefs.getFloat("cloudDynamicStretch",0.85f))).append(',');
        s.append("\"dynopacity\":").append(f(prefs.getFloat("cloudDynamicOpacity",0.18f))).append(',');
        s.append("\"energygamma\":").append(f(prefs.getFloat("cloudEnergyGamma",1.15f))).append(',');
        s.append("\"satweight\":").append(f(prefs.getFloat("cloudSaturationWeight",0.60f))).append(',');
        s.append("\"lumaweight\":").append(f(prefs.getFloat("cloudLumaWeight",0.40f))).append(',');
        s.append("\"fade\":").append(f(prefs.getFloat("outerFade",0.16f))).append(',');
        float[] td={0.20f,0.27f,0.80f,0.73f};
        for(int i=0;i<4;i++)s.append("\"tv").append(i).append("\":").append(f(prefs.getFloat("projectedTv"+i,td[i]))).append(',');
        float[] kd={0f,0f,1f,0f,1f,1f,0f,1f};
        for(int i=0;i<8;i++)s.append("\"k").append(i).append("\":").append(f(prefs.getFloat("keystone"+i,kd[i]))).append(',');
        float[][] fd={{0.24f,0.06f,0.76f,0.18f},{0.24f,0.82f,0.76f,0.94f},{0.03f,0.32f,0.18f,0.68f},{0.82f,0.32f,0.97f,0.68f}};
        for(int z=0;z<4;z++)for(int i=0;i<4;i++)s.append("\"tf").append(z).append('_').append(i).append("\":").append(f(prefs.getFloat("textFrame"+z+"_"+i,fd[z][i]))).append(',');
        s.append("\"calibrate\":").append(prefs.getBoolean("calibrationOverlay",false)); s.append('}'); return s.toString();
    }

    private static void putFloat(SharedPreferences.Editor e,Map<String,String> q,String key,String pref,float lo,float hi){String v=q.get(key);if(v==null)return;try{float x=Float.parseFloat(v);e.putFloat(pref,Math.max(lo,Math.min(hi,x)));}catch(Exception ignored){}}
    private static void putInt(SharedPreferences.Editor e,Map<String,String> q,String key,String pref,int lo,int hi){String v=q.get(key);if(v==null)return;try{int x=Integer.parseInt(v);e.putInt(pref,Math.max(lo,Math.min(hi,x)));}catch(Exception ignored){}}
    private static void putBoolean(SharedPreferences.Editor e,Map<String,String> q,String key,String pref){String v=q.get(key);if(v!=null)e.putBoolean(pref,"1".equals(v)||"true".equalsIgnoreCase(v)||"on".equalsIgnoreCase(v));}
    private static String f(float v){return String.format(Locale.US,"%.3f",v);}
    private static String escape(String s){if(s==null)return"";return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n"," ").replace("\r"," ");}

    private static Map<String,String> parseQuery(String query){Map<String,String> out=new HashMap<>();if(query==null)return out;for(String p:query.split("&")){int x=p.indexOf('=');String k=x<0?p:p.substring(0,x);String v=x<0?"":p.substring(x+1);try{out.put(URLDecoder.decode(k,"UTF-8"),URLDecoder.decode(v,"UTF-8"));}catch(Exception ignored){}}return out;}
    private static String readLine(InputStream in)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();int c;boolean cr=false;while((c=in.read())>=0){if(cr&&c=='\n')break;if(cr){b.write('\r');cr=false;}if(c=='\r')cr=true;else b.write(c);if(b.size()>16384)break;}if(c<0&&b.size()==0)return null;return new String(b.toByteArray(),StandardCharsets.UTF_8);}
    private static void send(Socket s,String type,byte[] body)throws IOException{BufferedOutputStream out=new BufferedOutputStream(s.getOutputStream());String h="HTTP/1.1 200 OK\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";out.write(h.getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();}

    private static List<String> localIpv4Addresses(){List<String[]> found=new ArrayList<>();try{for(NetworkInterface ni:Collections.list(NetworkInterface.getNetworkInterfaces())){if(!ni.isUp()||ni.isLoopback())continue;String name=ni.getName()==null?"":ni.getName().toLowerCase(Locale.US);if(name.startsWith("tun")||name.startsWith("rmnet"))continue;for(InetAddress a:Collections.list(ni.getInetAddresses())){if(!(a instanceof Inet4Address)||a.isLoopbackAddress()||a.isLinkLocalAddress())continue;int score=(name.startsWith("wlan")||name.startsWith("wifi"))?0:(name.startsWith("eth")?1:2);found.add(new String[]{String.valueOf(score),a.getHostAddress()});}}}catch(Throwable ignored){}Collections.sort(found,new Comparator<String[]>(){@Override public int compare(String[] a,String[] b){return Integer.compare(Integer.parseInt(a[0]),Integer.parseInt(b[0]));}});List<String> out=new ArrayList<>();for(String[] x:found)if(x[1]!=null&&!out.contains(x[1]))out.add(x[1]);return out;}

    private static final String PAGE = """
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>AmbiP Control Center</title><style>
:root{color-scheme:dark}*{box-sizing:border-box}body{background:#090b0e;color:#eef2f6;font-family:system-ui,sans-serif;max-width:940px;margin:auto;padding:18px}.head{display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap}.badges{display:flex;gap:8px}.badge{padding:6px 10px;border-radius:999px;background:#252b33;color:#aab4c0;font-size:12px}.ok{background:#153a2b;color:#8be7b8}.bad{background:#452026;color:#ff9da6}.card{background:#15191f;padding:16px;border-radius:14px;margin:12px 0;border:1px solid #222a34}.grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}.g4{display:grid;grid-template-columns:repeat(4,1fr);gap:8px}.text,select{padding:11px;width:100%;background:#222831;color:#fff;border:1px solid #3a4350;border-radius:8px}.row{display:flex;align-items:center;gap:10px}.v{float:right;color:#9fd}.note{color:#9ca7b5;font-size:13px}.small{font-size:12px;color:#9aa}.toggle{width:auto}input[type=range]{width:100%}h2,h3{margin:6px 0 12px}details{margin-top:8px}summary{cursor:pointer;color:#b8c7d9}.zone{padding:8px 0;border-top:1px solid #242c36}@media(max-width:680px){.grid,.g4{grid-template-columns:1fr 1fr}.head{align-items:flex-start}}
</style></head><body>
<div class="head"><div><h2>AmbiP Control Center · v0.18</h2><div class="note">One portal for TV Source + Projector</div></div><div class="badges"><span id="pbadge" class="badge ok">PROJECTOR ONLINE</span><span id="tbadge" class="badge">TV CHECKING</span></div></div>
<div class="card"><h3>Connection</h3><label>TV Source address</label><input id="source" class="text" placeholder="192.168.1.50"><p id="sourceMeta" class="note">The Projector proxies TV controls, so the phone stays on this page.</p></div>
<div class="card"><h3>TV Source</h3><div id="tvMeta" class="note">Checking TV…</div><div class="grid"><p>Target FPS <span id="sfpsv" class="v"></span><input id="sfps" type="range" min="4" max="30" step="1"></p><p>Samples / zone <span id="ssamplesv" class="v"></span><input id="ssamples" type="range" min="1" max="6" step="1"></p></div><p>Edge depth <span id="sstripv" class="v"></span><input id="sstrip" type="range" min=".03" max=".20" step=".01"></p><p><label><input id="sauto" class="toggle" type="checkbox"> Auto boot on TV</label></p><p class="note">Oreo: Auto boot opens AmbiP and the mandatory MediaProjection permission screen. Heavy color processing stays on the Projector.</p></div>
<div class="card"><h3>Motion / latency</h3><p><label><input id="interp" class="toggle" type="checkbox"> Interpolation</label></p><div class="grid"><p>Input smoothing <span id="smoothv" class="v"></span><input id="smooth" type="range" min="0" max=".8" step=".01"></p><p>Interpolation time <span id="interpmsv" class="v"></span><input id="interpms" type="range" min="0" max="180" step="2"></p><p>Adaptive fast response <span id="adaptivev" class="v"></span><input id="adaptive" type="range" min="0" max="1" step=".01"></p><p>Render Hz <span id="hzv" class="v"></span><input id="hz" type="range" min="30" max="120" step="10"></p></div></div>
<div class="card"><h3>Projection / Color Cloud</h3><label>Renderer</label><select id="style"><option value="COLOR_CLOUD">Color Cloud</option><option value="EDGE_GRADIENT">Edge Gradient</option></select><div class="grid"><p>Brightness <span id="brightnessv" class="v"></span><input id="brightness" type="range" min=".4" max="1.8" step=".01"></p><p>Saturation <span id="saturationv" class="v"></span><input id="saturation" type="range" min=".5" max="2.5" step=".01"></p><p>Spread <span id="spreadv" class="v"></span><input id="spread" type="range" min=".05" max=".9" step=".01"></p><p>Radius <span id="radiusv" class="v"></span><input id="radius" type="range" min=".08" max=".5" step=".01"></p><p>Opacity <span id="opacityv" class="v"></span><input id="opacity" type="range" min=".05" max="1" step=".01"></p><p>Outer fade <span id="fadev" class="v"></span><input id="fade" type="range" min=".02" max=".42" step=".01"></p><p>Edge pull <span id="edgepullv" class="v"></span><input id="edgepull" type="range" min="0" max="1" step=".01"></p><p>Softness <span id="softnessv" class="v"></span><input id="softness" type="range" min="0" max="1" step=".01"></p><p>Corner blend <span id="cornerblendv" class="v"></span><input id="cornerblend" type="range" min="0" max="1" step=".01"></p><p>Corner radius <span id="cornerradiusv" class="v"></span><input id="cornerradius" type="range" min=".7" max="2.4" step=".01"></p></div><details><summary>Dynamic cloud response</summary><div class="grid"><p>Dynamic amount <span id="dynamicv" class="v"></span><input id="dynamic" type="range" min="0" max="1.5" step=".01"></p><p>Dynamic radius <span id="dynradiusv" class="v"></span><input id="dynradius" type="range" min="0" max="1.5" step=".01"></p><p>Dynamic stretch <span id="stretchv" class="v"></span><input id="stretch" type="range" min="0" max="2" step=".01"></p><p>Dynamic opacity <span id="dynopacityv" class="v"></span><input id="dynopacity" type="range" min="0" max=".8" step=".01"></p><p>Energy curve <span id="energygammav" class="v"></span><input id="energygamma" type="range" min=".4" max="2.5" step=".01"></p><p>Saturation weight <span id="satweightv" class="v"></span><input id="satweight" type="range" min="0" max="1" step=".01"></p><p>Brightness weight <span id="lumaweightv" class="v"></span><input id="lumaweight" type="range" min="0" max="1" step=".01"></p></div></details></div>
<div class="card"><h3>Geometry / calibration</h3><p><label><input id="calibrate" class="toggle" type="checkbox"> Show calibration overlay on projector</label></p><h4>TV mask</h4><div class="g4"><p>Left <span id="tv0v" class="v"></span><input id="tv0" type="range" min="0" max=".94" step=".002"></p><p>Top <span id="tv1v" class="v"></span><input id="tv1" type="range" min="0" max=".94" step=".002"></p><p>Right <span id="tv2v" class="v"></span><input id="tv2" type="range" min=".06" max="1" step=".002"></p><p>Bottom <span id="tv3v" class="v"></span><input id="tv3" type="range" min=".06" max="1" step=".002"></p></div><h4>Outer projector corners</h4><div class="grid"><p>Top-left X <span id="k0v" class="v"></span><input id="k0" type="range" min="0" max="1" step=".002"></p><p>Top-left Y <span id="k1v" class="v"></span><input id="k1" type="range" min="0" max="1" step=".002"></p><p>Top-right X <span id="k2v" class="v"></span><input id="k2" type="range" min="0" max="1" step=".002"></p><p>Top-right Y <span id="k3v" class="v"></span><input id="k3" type="range" min="0" max="1" step=".002"></p><p>Bottom-right X <span id="k4v" class="v"></span><input id="k4" type="range" min="0" max="1" step=".002"></p><p>Bottom-right Y <span id="k5v" class="v"></span><input id="k5" type="range" min="0" max="1" step=".002"></p><p>Bottom-left X <span id="k6v" class="v"></span><input id="k6" type="range" min="0" max="1" step=".002"></p><p>Bottom-left Y <span id="k7v" class="v"></span><input id="k7" type="range" min="0" max="1" step=".002"></p></div></div>
<div class="card"><details><summary><b>Context text zones</b></summary><p class="note">Reserved for contextual information overlays.</p><div id="zones"></div></details></div>
<script>
const g=x=>document.getElementById(x);
const projRange=['smooth','interpms','adaptive','hz','brightness','saturation','spread','radius','opacity','fade','edgepull','softness','cornerblend','cornerradius','dynamic','dynradius','stretch','dynopacity','energygamma','satweight','lumaweight','tv0','tv1','tv2','tv3','k0','k1','k2','k3','k4','k5','k6','k7'];
const zoneNames=['TOP','BOTTOM','LEFT','RIGHT'];for(let z=0;z<4;z++){let h='<div class="zone"><b>'+zoneNames[z]+'</b><div class="g4">';['Left','Top','Right','Bottom'].forEach((n,i)=>{let id='tf'+z+'_'+i;h+='<p>'+n+' <span id="'+id+'v" class="v"></span><input id="'+id+'" type="range" min="0" max="1" step=".002"></p>';projRange.push(id)});h+='</div></div>';g('zones').insertAdjacentHTML('beforeend',h)}
function label(id){let e=g(id),v=g(id+'v');if(v)v.textContent=(id==='interpms'?Math.round(e.value)+' ms':id==='hz'?Math.round(e.value)+' Hz':Number(e.value).toFixed(id.startsWith('tv')||id.startsWith('k')||id.startsWith('tf')?3:2))}function labels(){projRange.forEach(label);g('sfpsv').textContent=g('sfps').value;g('ssamplesv').textContent=g('ssamples').value;g('sstripv').textContent=Number(g('sstrip').value).toFixed(2)}
let pt;function saveProjector(){labels();clearTimeout(pt);pt=setTimeout(()=>{let q='source='+encodeURIComponent(g('source').value)+'&style='+g('style').value+'&interp='+(g('interp').checked?1:0)+'&calibrate='+(g('calibrate').checked?1:0);projRange.forEach(k=>q+='&'+k+'='+g(k).value);fetch('/api/settings?'+q)},90)}
let st;function saveSource(){labels();clearTimeout(st);st=setTimeout(()=>{let q='fps='+g('sfps').value+'&strip='+g('sstrip').value+'&samples='+g('ssamples').value+'&autostart='+(g('sauto').checked?1:0);fetch('/api/source/settings?'+q).then(()=>setTimeout(loadSource,120))},100)}
function idleSet(id,v){if(document.activeElement!==g(id))g(id).value=v}
async function loadProjector(){let s=await(await fetch('/api/status')).json();g('source').value=s.source||'';g('style').value=s.style||'COLOR_CLOUD';g('interp').checked=!!s.interp;g('calibrate').checked=!!s.calibrate;projRange.forEach(k=>{if(s[k]!==undefined)g(k).value=s[k]});labels()}
async function loadSource(){try{let w=await(await fetch('/api/source/status')).json();if(!w.reachable){g('tbadge').className='badge bad';g('tbadge').textContent='TV OFFLINE';g('tvMeta').textContent=w.error||'TV Source unreachable';return}let s=w.data||{};g('tbadge').className='badge ok';g('tbadge').textContent=s.active?'TV LIVE':'TV IDLE';g('tvMeta').textContent=(s.status||'')+' · '+Number(s.analysisW||0)+'×'+Number(s.analysisH||0)+' · '+Number(s.clients||0)+' client(s) · control RTT '+Number(w.rttMs||0)+' ms';idleSet('sfps',s.fps);idleSet('sstrip',s.strip);idleSet('ssamples',s.samples);if(document.activeElement!==g('sauto'))g('sauto').checked=!!s.autostart;labels()}catch(e){g('tbadge').className='badge bad';g('tbadge').textContent='TV OFFLINE';g('tvMeta').textContent='TV Source unreachable'}}
projRange.forEach(k=>g(k).oninput=saveProjector);g('interp').onchange=saveProjector;g('calibrate').onchange=saveProjector;g('style').onchange=saveProjector;g('source').onchange=()=>{saveProjector();setTimeout(loadSource,450)};['sfps','sstrip','ssamples'].forEach(k=>g(k).oninput=saveSource);g('sauto').onchange=saveSource;loadProjector().then(loadSource);setInterval(loadSource,2200);
</script></body></html>
""";
}
