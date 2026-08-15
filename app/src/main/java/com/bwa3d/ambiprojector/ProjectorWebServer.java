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

/** Unified phone portal for TV Source + Projector with visual geometry calibration. */
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
                applyProjector(parseQuery(query));
                send(socket,"application/json; charset=utf-8",settingsJson().getBytes(StandardCharsets.UTF_8));
            } else if ("/api/status".equals(path)) {
                send(socket,"application/json; charset=utf-8",settingsJson().getBytes(StandardCharsets.UTF_8));
            } else if ("/api/source/status".equals(path)) {
                send(socket,"application/json; charset=utf-8",proxySource("/api/status").getBytes(StandardCharsets.UTF_8));
            } else if ("/api/source/settings".equals(path)) {
                String suffix=query.isEmpty()?"":"?"+query;
                send(socket,"application/json; charset=utf-8",proxySource("/api/settings"+suffix).getBytes(StandardCharsets.UTF_8));
            } else if ("/health".equals(path)) {
                send(socket,"text/plain; charset=utf-8","OK AmbiP Control Center\n".getBytes(StandardCharsets.UTF_8));
            } else {
                send(socket,"text/html; charset=utf-8",PAGE.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void applyProjector(Map<String,String> q) {
        SharedPreferences.Editor e=prefs.edit();
        String source=q.get("source"); if(source!=null)e.putString("networkTvSource",source.trim());
        String style=q.get("style"); if(style!=null)e.putString("projectionStyle","EDGE_GRADIENT".equals(style)?"EDGE_GRADIENT":"COLOR_CLOUD");

        putFloat(e,q,"smooth","networkSmoothing",0f,0.85f);
        putBoolean(e,q,"interp","interpolationEnabled");
        putInt(e,q,"interpms","interpolationMs",0,140);
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
        for(int i=0;i<8;i++)putFloat(e,q,"k"+i,"keystone"+i,0f,1f);
        putBoolean(e,q,"calibrate","calibrationOverlay");
        for(int z=0;z<4;z++)for(int i=0;i<4;i++)putFloat(e,q,"tf"+z+"_"+i,"textFrame"+z+"_"+i,0f,1f);
        e.apply();
        sanitizeTvRect();
        sanitizeTextFrames();
        if(listener!=null){listener.onSettingsChanged();if(source!=null)listener.onSourceChanged(source.trim());}
    }

    private void sanitizeTvRect(){
        float l=prefs.getFloat("projectedTv0",.20f),t=prefs.getFloat("projectedTv1",.27f),r=prefs.getFloat("projectedTv2",.80f),b=prefs.getFloat("projectedTv3",.73f);
        if(r-l<.08f)r=Math.min(1f,l+.08f);if(b-t<.08f)b=Math.min(1f,t+.08f);if(r-l<.08f)l=Math.max(0f,r-.08f);if(b-t<.08f)t=Math.max(0f,b-.08f);
        prefs.edit().putFloat("projectedTv0",l).putFloat("projectedTv1",t).putFloat("projectedTv2",r).putFloat("projectedTv3",b).apply();
    }

    private void sanitizeTextFrames(){
        float[][] d={{.24f,.06f,.76f,.18f},{.24f,.82f,.76f,.94f},{.03f,.32f,.18f,.68f},{.82f,.32f,.97f,.68f}};SharedPreferences.Editor e=prefs.edit();
        for(int z=0;z<4;z++){float l=prefs.getFloat("textFrame"+z+"_0",d[z][0]),t=prefs.getFloat("textFrame"+z+"_1",d[z][1]),r=prefs.getFloat("textFrame"+z+"_2",d[z][2]),b=prefs.getFloat("textFrame"+z+"_3",d[z][3]);if(r-l<.05f)r=Math.min(1f,l+.05f);if(b-t<.05f)b=Math.min(1f,t+.05f);e.putFloat("textFrame"+z+"_0",l).putFloat("textFrame"+z+"_1",t).putFloat("textFrame"+z+"_2",r).putFloat("textFrame"+z+"_3",b);}e.apply();
    }

    private String proxySource(String sourcePath){
        Endpoint ep=endpoint(prefs.getString("networkTvSource",""));if(ep==null)return"{\"reachable\":false,\"error\":\"TV Source address not configured\"}";long started=System.nanoTime();
        try{String body=rawHttpGet(ep,sourcePath);long rtt=Math.max(0L,Math.round((System.nanoTime()-started)/1_000_000.0));String trimmed=body==null?"":body.trim();if(trimmed.isEmpty())trimmed="{}";return"{\"reachable\":true,\"rttMs\":"+rtt+",\"data\":"+trimmed+"}";}catch(Throwable t){return"{\"reachable\":false,\"error\":\""+escape(t.getClass().getSimpleName())+"\"}";}
    }

    private static String rawHttpGet(Endpoint ep,String path)throws IOException{
        Socket s=new Socket();try{s.connect(new InetSocketAddress(ep.host,ep.port),500);s.setSoTimeout(900);s.setTcpNoDelay(true);OutputStream out=s.getOutputStream();String request="GET "+path+" HTTP/1.1\r\nHost: "+ep.host+"\r\nConnection: close\r\nCache-Control: no-cache\r\n\r\n";out.write(request.getBytes(StandardCharsets.US_ASCII));out.flush();BufferedInputStream in=new BufferedInputStream(s.getInputStream());String status=readLine(in);if(status==null||!status.contains(" 200 "))throw new IOException("HTTP response");String line;while((line=readLine(in))!=null&&line.length()>0){}ByteArrayOutputStream body=new ByteArrayOutputStream(2048);byte[] buf=new byte[1024];int n,total=0;while((n=in.read(buf))>=0){if(n==0)continue;body.write(buf,0,n);total+=n;if(total>65536)break;}return new String(body.toByteArray(),StandardCharsets.UTF_8);}finally{try{s.close();}catch(IOException ignored){}}
    }

    private static Endpoint endpoint(String raw){if(raw==null)return null;String s=raw.trim();if(s.isEmpty())return null;int scheme=s.indexOf("://");if(scheme>=0)s=s.substring(scheme+3);int slash=s.indexOf('/');if(slash>=0)s=s.substring(0,slash);int port=SOURCE_PORT;String host=s;int colon=s.lastIndexOf(':');if(colon>0&&s.indexOf(':')==colon){try{port=Integer.parseInt(s.substring(colon+1));host=s.substring(0,colon);}catch(Exception ignored){}}return host.isEmpty()?null:new Endpoint(host,port);}
    private static final class Endpoint{final String host;final int port;Endpoint(String h,int p){host=h;port=p;}}

    private String settingsJson(){
        StringBuilder s=new StringBuilder(2800);s.append('{');
        s.append("\"source\":\"").append(escape(prefs.getString("networkTvSource",""))).append("\",");
        s.append("\"style\":\"").append(escape(prefs.getString("projectionStyle","COLOR_CLOUD"))).append("\",");
        s.append("\"smooth\":").append(f(prefs.getFloat("networkSmoothing",.25f))).append(',');s.append("\"interp\":").append(prefs.getBoolean("interpolationEnabled",true)).append(',');s.append("\"interpms\":").append(prefs.getInt("interpolationMs",46)).append(',');s.append("\"adaptive\":").append(f(prefs.getFloat("interpolationAdaptive",.88f))).append(',');s.append("\"hz\":").append(prefs.getInt("interpolationHz",60)).append(',');
        s.append("\"brightness\":").append(f(prefs.getFloat("cloudBrightness",1.08f))).append(',');s.append("\"saturation\":").append(f(prefs.getFloat("cloudSaturation",1.32f))).append(',');s.append("\"spread\":").append(f(prefs.getFloat("cloudSpread",.42f))).append(',');s.append("\"radius\":").append(f(prefs.getFloat("cloudRadius",.26f))).append(',');s.append("\"opacity\":").append(f(prefs.getFloat("cloudOpacity",.60f))).append(',');s.append("\"edgepull\":").append(f(prefs.getFloat("cloudEdgePull",.62f))).append(',');s.append("\"softness\":").append(f(prefs.getFloat("cloudSoftness",.72f))).append(',');s.append("\"cornerblend\":").append(f(prefs.getFloat("cornerBlend",.82f))).append(',');s.append("\"cornerradius\":").append(f(prefs.getFloat("cornerRadius",1.48f))).append(',');s.append("\"dynamic\":").append(f(prefs.getFloat("cloudDynamicAmount",.85f))).append(',');s.append("\"dynradius\":").append(f(prefs.getFloat("cloudDynamicRadius",.65f))).append(',');s.append("\"stretch\":").append(f(prefs.getFloat("cloudDynamicStretch",.85f))).append(',');s.append("\"dynopacity\":").append(f(prefs.getFloat("cloudDynamicOpacity",.18f))).append(',');s.append("\"energygamma\":").append(f(prefs.getFloat("cloudEnergyGamma",1.15f))).append(',');s.append("\"satweight\":").append(f(prefs.getFloat("cloudSaturationWeight",.60f))).append(',');s.append("\"lumaweight\":").append(f(prefs.getFloat("cloudLumaWeight",.40f))).append(',');s.append("\"fade\":").append(f(prefs.getFloat("outerFade",.16f))).append(',');
        float[] td={.20f,.27f,.80f,.73f};for(int i=0;i<4;i++)s.append("\"tv").append(i).append("\":").append(f(prefs.getFloat("projectedTv"+i,td[i]))).append(',');float[] kd={0f,0f,1f,0f,1f,1f,0f,1f};for(int i=0;i<8;i++)s.append("\"k").append(i).append("\":").append(f(prefs.getFloat("keystone"+i,kd[i]))).append(',');
        float[][] d={{.24f,.06f,.76f,.18f},{.24f,.82f,.76f,.94f},{.03f,.32f,.18f,.68f},{.82f,.32f,.97f,.68f}};for(int z=0;z<4;z++)for(int i=0;i<4;i++)s.append("\"tf").append(z).append('_').append(i).append("\":").append(f(prefs.getFloat("textFrame"+z+"_"+i,d[z][i]))).append(',');s.append("\"calibrate\":").append(prefs.getBoolean("calibrationOverlay",false));s.append('}');return s.toString();
    }

    private static void putFloat(SharedPreferences.Editor e,Map<String,String> q,String key,String pref,float lo,float hi){String s=q.get(key);if(s==null)return;try{float v=Float.parseFloat(s);e.putFloat(pref,Math.max(lo,Math.min(hi,v)));}catch(Exception ignored){}}
    private static void putInt(SharedPreferences.Editor e,Map<String,String> q,String key,String pref,int lo,int hi){String s=q.get(key);if(s==null)return;try{int v=Integer.parseInt(s);e.putInt(pref,Math.max(lo,Math.min(hi,v)));}catch(Exception ignored){}}
    private static void putBoolean(SharedPreferences.Editor e,Map<String,String> q,String key,String pref){String s=q.get(key);if(s!=null)e.putBoolean(pref,"1".equals(s)||"true".equalsIgnoreCase(s)||"on".equalsIgnoreCase(s));}
    private static String f(float v){return String.format(Locale.US,"%.4f",v);}private static String escape(String s){if(s==null)return"";return s.replace("\\","\\\\").replace("\"","\\\"");}
    private static Map<String,String> parseQuery(String query){Map<String,String> out=new HashMap<>();if(query==null)return out;for(String p:query.split("&")){int x=p.indexOf('=');String k=x<0?p:p.substring(0,x),v=x<0?"":p.substring(x+1);try{out.put(URLDecoder.decode(k,"UTF-8"),URLDecoder.decode(v,"UTF-8"));}catch(Exception ignored){}}return out;}
    private static String readLine(InputStream in)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();int c;boolean cr=false;while((c=in.read())>=0){if(cr&&c=='\n')break;if(cr){b.write('\r');cr=false;}if(c=='\r')cr=true;else b.write(c);if(b.size()>16384)break;}if(c<0&&b.size()==0)return null;return new String(b.toByteArray(),StandardCharsets.UTF_8);}
    private static void send(Socket s,String type,byte[] body)throws IOException{BufferedOutputStream out=new BufferedOutputStream(s.getOutputStream());String h="HTTP/1.1 200 OK\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";out.write(h.getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();}
    private static List<String> localIpv4Addresses(){List<String[]> found=new ArrayList<>();try{for(NetworkInterface ni:Collections.list(NetworkInterface.getNetworkInterfaces())){if(!ni.isUp()||ni.isLoopback())continue;String name=ni.getName()==null?"":ni.getName().toLowerCase(Locale.US);if(name.startsWith("tun")||name.startsWith("rmnet"))continue;for(InetAddress a:Collections.list(ni.getInetAddresses())){if(!(a instanceof Inet4Address)||a.isLoopbackAddress()||a.isLinkLocalAddress())continue;int score=(name.startsWith("wlan")||name.startsWith("wifi"))?0:(name.startsWith("eth")?1:2);found.add(new String[]{String.valueOf(score),a.getHostAddress()});}}}catch(Throwable ignored){}Collections.sort(found,new Comparator<String[]>(){@Override public int compare(String[] a,String[] b){return Integer.compare(Integer.parseInt(a[0]),Integer.parseInt(b[0]));}});List<String> out=new ArrayList<>();for(String[] x:found)if(x[1]!=null&&!out.contains(x[1]))out.add(x[1]);return out;}

    private static final String PAGE = """
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1"><title>AmbiP Control Center</title><style>
*{box-sizing:border-box}body{background:#090b0e;color:#eee;font-family:system-ui,sans-serif;max-width:920px;margin:auto;padding:16px}.card{background:#15191f;padding:16px;border-radius:14px;margin:12px 0}.grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}input[type=range]{width:100%}.text{padding:11px;width:100%;background:#222831;color:#fff;border:1px solid #39434f;border-radius:8px}.v{float:right;color:#9fd}.note{color:#9ba6b4;font-size:13px}.toggle{width:auto}.pill{display:inline-block;padding:5px 9px;border-radius:999px;background:#272e38;margin-right:6px}.ok{background:#173d29;color:#9ff0bc}.bad{background:#4b2424;color:#ffb3b3}button{background:#28313c;color:#fff;border:1px solid #465362;border-radius:9px;padding:10px 13px;margin:3px}#geom{width:100%;background:#080b10;border:1px solid #39434f;border-radius:12px;touch-action:none}.outer{fill:rgba(40,199,255,.05);stroke:#28c7ff;stroke-width:4}.inner{fill:rgba(96,255,156,.06);stroke:#60ff9c;stroke-width:4}.handleO{fill:#28c7ff;stroke:#fff;stroke-width:3}.handleI{fill:#60ff9c;stroke:#fff;stroke-width:3}.label{fill:#fff;font-size:24px;pointer-events:none}.guide{stroke:#29313a;stroke-width:1}.small{font-size:12px;color:#9aa}h2,h3{margin:5px 0 12px}@media(max-width:650px){.grid{grid-template-columns:1fr}}
</style></head><body>
<h2>AmbiP Control Center · v0.19</h2><div><span id="pb" class="pill">Projector</span><span id="tb" class="pill">TV Source</span><span id="rtt" class="pill">—</span></div>
<div class="card"><h3>Connection</h3><input id="source" class="text" placeholder="TV Source IP, e.g. 192.168.1.50"><p class="note">One portal controls both APKs. Geometry changes are applied live while dragging.</p><button onclick="presetLow()">LOW LATENCY PRESET</button><button onclick="presetBalanced()">BALANCED</button></div>
<div class="card"><h3>TV Source</h3><div class="grid"><p>Target FPS <span id="sfpsv" class="v"></span><input id="sfps" type="range" min="4" max="30" step="1"></p><p>Samples / zone <span id="ssamplesv" class="v"></span><input id="ssamples" type="range" min="1" max="6"></p><p>Edge depth <span id="sstripv" class="v"></span><input id="sstrip" type="range" min=".03" max=".20" step=".01"></p><p><label><input id="sauto" class="toggle" type="checkbox"> Auto boot</label></p></div><p id="smeta" class="note"></p></div>
<div class="card"><h3>Motion / latency</h3><p><label><input id="interp" class="toggle" type="checkbox"> VSYNC interpolation</label></p><div class="grid"><p>Adaptive noise smoothing <span id="smoothv" class="v"></span><input id="smooth" type="range" min="0" max=".85" step=".01"></p><p>Catch-up time <span id="interpmsv" class="v"></span><input id="interpms" type="range" min="0" max="140" step="2"></p><p>Fast response <span id="adaptivev" class="v"></span><input id="adaptive" type="range" min="0" max="1" step=".01"></p><p>Render Hz <span id="hzv" class="v"></span><input id="hz" type="range" min="30" max="120" step="10"></p></div><p class="note">Smoothing now releases automatically on real motion; large changes bypass it. Interpolation starts fast and is locked to display VSYNC.</p></div>
<div class="card"><h3>Color Cloud</h3><select id="style" class="text"><option value="COLOR_CLOUD">Color Cloud</option><option value="EDGE_GRADIENT">Edge Gradient</option></select><div class="grid">
<p>Brightness <span id="brightnessv" class="v"></span><input id="brightness" type="range" min=".4" max="1.8" step=".01"></p><p>Saturation <span id="saturationv" class="v"></span><input id="saturation" type="range" min=".5" max="2.5" step=".01"></p><p>Spread <span id="spreadv" class="v"></span><input id="spread" type="range" min=".05" max=".9" step=".01"></p><p>Radius <span id="radiusv" class="v"></span><input id="radius" type="range" min=".08" max=".5" step=".01"></p><p>Opacity <span id="opacityv" class="v"></span><input id="opacity" type="range" min=".05" max="1" step=".01"></p><p>Edge pull <span id="edgepullv" class="v"></span><input id="edgepull" type="range" min="0" max="1" step=".01"></p><p>Softness <span id="softnessv" class="v"></span><input id="softness" type="range" min="0" max="1" step=".01"></p><p>Outer fade <span id="fadev" class="v"></span><input id="fade" type="range" min=".02" max=".42" step=".01"></p><p>Corner blend <span id="cornerblendv" class="v"></span><input id="cornerblend" type="range" min="0" max="1" step=".01"></p><p>Corner radius <span id="cornerradiusv" class="v"></span><input id="cornerradius" type="range" min=".7" max="2.4" step=".02"></p><p>Dynamic amount <span id="dynamicv" class="v"></span><input id="dynamic" type="range" min="0" max="1.5" step=".01"></p><p>Dynamic radius <span id="dynradiusv" class="v"></span><input id="dynradius" type="range" min="0" max="1.5" step=".01"></p><p>Dynamic stretch <span id="stretchv" class="v"></span><input id="stretch" type="range" min="0" max="2" step=".01"></p><p>Dynamic opacity <span id="dynopacityv" class="v"></span><input id="dynopacity" type="range" min="0" max=".8" step=".01"></p><p>Energy curve <span id="energygammav" class="v"></span><input id="energygamma" type="range" min=".4" max="2.5" step=".01"></p><p>Saturation weight <span id="satweightv" class="v"></span><input id="satweight" type="range" min="0" max="1" step=".01"></p><p>Brightness weight <span id="lumaweightv" class="v"></span><input id="lumaweight" type="range" min="0" max="1" step=".01"></p></div></div>
<div class="card"><h3>Projection geometry · drag corners</h3><p><label><input id="calibrate" class="toggle" type="checkbox"> Show calibration overlay on projector</label></p><svg id="geom" viewBox="0 0 1000 600"><g id="grid"></g><polygon id="outer" class="outer"></polygon><rect id="inner" class="inner"></rect><text x="25" y="38" class="label">OUTER PROJECTION</text><text id="tvlabel" class="label">TV</text><g id="handles"></g></svg><p class="note">Blue = outer projected border / keystone. Green = TV black mask. Drag any corner; changes are saved live.</p><button onclick="resetOuter()">RESET OUTER</button><button onclick="resetTv()">RESET TV</button></div>
<div class="card"><details><summary>Context text zones</summary><p class="note">Future contextual overlays remain configurable here.</p><div id="textzones"></div></details></div>
<script>
const $=id=>document.getElementById(id);let cfg={},src={},timer,stimer,drag=null,dragging=false;
const keys=['smooth','interpms','adaptive','hz','brightness','saturation','spread','radius','opacity','edgepull','softness','fade','cornerblend','cornerradius','dynamic','dynradius','stretch','dynopacity','energygamma','satweight','lumaweight'];
function labels(){keys.forEach(k=>{let e=$(k+'v');if(e)e.textContent=$(k).value});$('sfpsv').textContent=$('sfps').value;$('ssamplesv').textContent=$('ssamples').value;$('sstripv').textContent=Number($('sstrip').value).toFixed(2)}
function localQuery(){let q='source='+encodeURIComponent($('source').value)+'&interp='+($('interp').checked?1:0)+'&style='+$('style').value+'&calibrate='+($('calibrate').checked?1:0);keys.forEach(k=>q+='&'+k+'='+encodeURIComponent($(k).value));return q}
function saveLocal(extra=''){labels();clearTimeout(timer);timer=setTimeout(()=>fetch('/api/settings?'+localQuery()+(extra?'&'+extra:'')),50)}
function saveSource(){labels();clearTimeout(stimer);stimer=setTimeout(()=>fetch('/api/source/settings?fps='+$('sfps').value+'&samples='+$('ssamples').value+'&strip='+$('sstrip').value+'&autostart='+($('sauto').checked?1:0)),65)}
async function load(){try{cfg=await(await fetch('/api/status')).json();if(!dragging)applyCfg();$('pb').className='pill ok'}catch(e){$('pb').className='pill bad'}try{let wrap=await(await fetch('/api/source/status')).json();src=wrap.data||{};$('tb').textContent=wrap.reachable?(src.active?'TV LIVE':'TV IDLE'):'TV OFFLINE';$('tb').className='pill '+(wrap.reachable?'ok':'bad');$('rtt').textContent=wrap.reachable?(wrap.rttMs+' ms ctrl'):'—';$('smeta').textContent=wrap.reachable?((src.fpsActual||0)+' actual fps · '+(src.analysisW||0)+'×'+(src.analysisH||0)+' · '+(src.protocol||'')):(wrap.error||'unreachable');if(wrap.reachable&&!dragging){$('sfps').value=src.fps;$('ssamples').value=src.samples;$('sstrip').value=src.strip;$('sauto').checked=!!src.autostart}}catch(e){$('tb').className='pill bad'}labels()}
function applyCfg(){$('source').value=cfg.source||'';$('interp').checked=!!cfg.interp;$('style').value=cfg.style||'COLOR_CLOUD';$('calibrate').checked=!!cfg.calibrate;keys.forEach(k=>{if(cfg[k]!==undefined)$(k).value=cfg[k]});for(let z=0;z<4;z++)for(let i=0;i<4;i++){let e=$('tf'+z+'_'+i);if(e&&cfg['tf'+z+'_'+i]!==undefined)e.value=cfg['tf'+z+'_'+i]}drawGeom()}
function drawGrid(){let s='';for(let x=100;x<1000;x+=100)s+=`<line x1="${x}" y1="0" x2="${x}" y2="600" class="guide"/>`;for(let y=100;y<600;y+=100)s+=`<line x1="0" y1="${y}" x2="1000" y2="${y}" class="guide"/>`;$('grid').innerHTML=s}
function xy(i){return[(cfg['k'+i]??0)*1000,(cfg['k'+(i+1)]??0)*600]}
function drawGeom(){let p=[xy(0),xy(2),xy(4),xy(6)];$('outer').setAttribute('points',p.map(v=>v.join(',')).join(' '));let l=(cfg.tv0??.2)*1000,t=(cfg.tv1??.27)*600,r=(cfg.tv2??.8)*1000,b=(cfg.tv3??.73)*600;$('inner').setAttribute('x',l);$('inner').setAttribute('y',t);$('inner').setAttribute('width',r-l);$('inner').setAttribute('height',b-t);$('tvlabel').setAttribute('x',l+18);$('tvlabel').setAttribute('y',t+34);let s='';p.forEach((v,i)=>s+=`<circle class="handleO" data-type="o" data-i="${i}" cx="${v[0]}" cy="${v[1]}" r="15"/>`);[[l,t],[r,t],[r,b],[l,b]].forEach((v,i)=>s+=`<circle class="handleI" data-type="i" data-i="${i}" cx="${v[0]}" cy="${v[1]}" r="15"/>`);$('handles').innerHTML=s}
function point(ev){let r=$('geom').getBoundingClientRect();return[Math.max(0,Math.min(1,(ev.clientX-r.left)/r.width)),Math.max(0,Math.min(1,(ev.clientY-r.top)/r.height))]}
function down(ev){let t=ev.target;if(!t.dataset.type)return;drag={type:t.dataset.type,i:+t.dataset.i};dragging=true;$('geom').setPointerCapture(ev.pointerId);ev.preventDefault()}
function move(ev){if(!drag)return;let [x,y]=point(ev);if(drag.type==='o'){let j=drag.i*2;cfg['k'+j]=x;cfg['k'+(j+1)]=y}else{let l=+cfg.tv0,t=+cfg.tv1,r=+cfg.tv2,b=+cfg.tv3,i=drag.i;if(i===0||i===3)l=Math.min(x,r-.08);else r=Math.max(x,l+.08);if(i===0||i===1)t=Math.min(y,b-.08);else b=Math.max(y,t+.08);cfg.tv0=l;cfg.tv1=t;cfg.tv2=r;cfg.tv3=b}drawGeom();saveGeometry();ev.preventDefault()}
function up(){drag=null;setTimeout(()=>dragging=false,100)}
function saveGeometry(){let q=`tv0=${cfg.tv0}&tv1=${cfg.tv1}&tv2=${cfg.tv2}&tv3=${cfg.tv3}`;for(let i=0;i<8;i++)q+=`&k${i}=${cfg['k'+i]}`;saveLocal(q)}
function resetOuter(){[0,0,1,0,1,1,0,1].forEach((v,i)=>cfg['k'+i]=v);drawGeom();saveGeometry()}function resetTv(){cfg.tv0=.2;cfg.tv1=.27;cfg.tv2=.8;cfg.tv3=.73;drawGeom();saveGeometry()}
function presetLow(){$('smooth').value=.10;$('interpms').value=32;$('adaptive').value=.96;$('hz').value=60;$('sfps').value=12;$('ssamples').value=1;saveLocal();saveSource()}function presetBalanced(){$('smooth').value=.22;$('interpms').value=46;$('adaptive').value=.88;$('hz').value=60;$('sfps').value=10;$('ssamples').value=2;saveLocal();saveSource()}
function buildText(){let names=['TOP','BOTTOM','LEFT','RIGHT'],html='';for(let z=0;z<4;z++){html+=`<h4>${names[z]}</h4><div class="grid">`;for(let i=0;i<4;i++)html+=`<label>${['L','T','R','B'][i]}<input id="tf${z}_${i}" type="range" min="0" max="1" step=".01"></label>`;html+='</div>'}$('textzones').innerHTML=html;for(let z=0;z<4;z++)for(let i=0;i<4;i++)$('tf'+z+'_'+i).oninput=saveText}
function saveText(){let q='';for(let z=0;z<4;z++)for(let i=0;i<4;i++)q+=(q?'&':'')+`tf${z}_${i}=${$('tf'+z+'_'+i).value}`;fetch('/api/settings?'+q)}
drawGrid();buildText();$('geom').addEventListener('pointerdown',down);$('geom').addEventListener('pointermove',move);$('geom').addEventListener('pointerup',up);$('geom').addEventListener('pointercancel',up);keys.forEach(k=>$(k).oninput=()=>saveLocal());$('interp').onchange=()=>saveLocal();$('style').onchange=()=>saveLocal();$('calibrate').onchange=()=>saveLocal();$('source').onchange=()=>saveLocal();['sfps','ssamples','sstrip'].forEach(k=>$(k).oninput=saveSource);$('sauto').onchange=saveSource;load();setInterval(load,1500);
</script></body></html>
""";
}
