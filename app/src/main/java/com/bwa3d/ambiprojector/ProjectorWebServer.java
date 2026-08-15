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

/** Unified phone portal for TV Source + Projector, optimized for low latency and visual calibration. */
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

        putFloat(e,q,"smooth","networkSmoothing",0f,0.50f);
        putBoolean(e,q,"interp","interpolationEnabled");
        putInt(e,q,"interpms","interpolationMs",0,100);
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
        StringBuilder s=new StringBuilder(3000);s.append('{');
        s.append("\"source\":\"").append(escape(prefs.getString("networkTvSource",""))).append("\",");
        s.append("\"style\":\"").append(escape(prefs.getString("projectionStyle","COLOR_CLOUD"))).append("\",");
        s.append("\"smooth\":").append(f(prefs.getFloat("networkSmoothing",.06f))).append(',');s.append("\"interp\":").append(prefs.getBoolean("interpolationEnabled",true)).append(',');s.append("\"interpms\":").append(prefs.getInt("interpolationMs",28)).append(',');s.append("\"adaptive\":").append(f(prefs.getFloat("interpolationAdaptive",.95f))).append(',');s.append("\"hz\":").append(prefs.getInt("interpolationHz",60)).append(',');
        s.append("\"brightness\":").append(f(prefs.getFloat("cloudBrightness",1.08f))).append(',');s.append("\"saturation\":").append(f(prefs.getFloat("cloudSaturation",1.32f))).append(',');s.append("\"spread\":").append(f(prefs.getFloat("cloudSpread",.42f))).append(',');s.append("\"radius\":").append(f(prefs.getFloat("cloudRadius",.26f))).append(',');s.append("\"opacity\":").append(f(prefs.getFloat("cloudOpacity",.60f))).append(',');s.append("\"edgepull\":").append(f(prefs.getFloat("cloudEdgePull",.62f))).append(',');s.append("\"softness\":").append(f(prefs.getFloat("cloudSoftness",.72f))).append(',');s.append("\"cornerblend\":").append(f(prefs.getFloat("cornerBlend",.82f))).append(',');s.append("\"cornerradius\":").append(f(prefs.getFloat("cornerRadius",1.48f))).append(',');s.append("\"dynamic\":").append(f(prefs.getFloat("cloudDynamicAmount",.85f))).append(',');s.append("\"dynradius\":").append(f(prefs.getFloat("cloudDynamicRadius",.65f))).append(',');s.append("\"stretch\":").append(f(prefs.getFloat("cloudDynamicStretch",.85f))).append(',');s.append("\"dynopacity\":").append(f(prefs.getFloat("cloudDynamicOpacity",.18f))).append(',');s.append("\"energygamma\":").append(f(prefs.getFloat("cloudEnergyGamma",1.15f))).append(',');s.append("\"satweight\":").append(f(prefs.getFloat("cloudSaturationWeight",.60f))).append(',');s.append("\"lumaweight\":").append(f(prefs.getFloat("cloudLumaWeight",.40f))).append(',');s.append("\"fade\":").append(f(prefs.getFloat("outerFade",.16f))).append(',');
        float[] td={.20f,.27f,.80f,.73f};for(int i=0;i<4;i++)s.append("\"tv").append(i).append("\":").append(f(prefs.getFloat("projectedTv"+i,td[i]))).append(',');float[] kd={0f,0f,1f,0f,1f,1f,0f,1f};for(int i=0;i<8;i++)s.append("\"k").append(i).append("\":").append(f(prefs.getFloat("keystone"+i,kd[i]))).append(',');
        float[][] d={{.24f,.06f,.76f,.18f},{.24f,.82f,.76f,.94f},{.03f,.32f,.18f,.68f},{.82f,.32f,.97f,.68f}};for(int z=0;z<4;z++)for(int i=0;i<4;i++)s.append("\"tf").append(z).append('_').append(i).append("\":").append(f(prefs.getFloat("textFrame"+z+"_"+i,d[z][i]))).append(',');s.append("\"calibrate\":").append(prefs.getBoolean("calibrationOverlay",false));s.append('}');return s.toString();
    }

    private static void putFloat(SharedPreferences.Editor e,Map<String,String> q,String key,String pref,float lo,float hi){String v=q.get(key);if(v==null)return;try{float x=Float.parseFloat(v);e.putFloat(pref,Math.max(lo,Math.min(hi,x)));}catch(Exception ignored){}}
    private static void putInt(SharedPreferences.Editor e,Map<String,String> q,String key,String pref,int lo,int hi){String v=q.get(key);if(v==null)return;try{int x=Integer.parseInt(v);e.putInt(pref,Math.max(lo,Math.min(hi,x)));}catch(Exception ignored){}}
    private static void putBoolean(SharedPreferences.Editor e,Map<String,String> q,String key,String pref){String v=q.get(key);if(v!=null)e.putBoolean(pref,"1".equals(v)||"true".equalsIgnoreCase(v)||"on".equalsIgnoreCase(v));}
    private static String f(float v){return String.format(Locale.US,"%.4f",v);}
    private static String escape(String s){if(s==null)return"";return s.replace("\\","\\\\").replace("\"","\\\"");}
    private static Map<String,String> parseQuery(String query){Map<String,String> out=new HashMap<>();if(query==null||query.isEmpty())return out;for(String part:query.split("&")){int p=part.indexOf('=');String k=p<0?part:part.substring(0,p),v=p<0?"":part.substring(p+1);try{out.put(URLDecoder.decode(k,"UTF-8"),URLDecoder.decode(v,"UTF-8"));}catch(Exception ignored){}}return out;}
    private static String readLine(InputStream in)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();int c;boolean cr=false;while((c=in.read())>=0){if(cr&&c=='\n')break;if(cr){b.write('\r');cr=false;}if(c=='\r')cr=true;else b.write(c);if(b.size()>16384)break;}if(c<0&&b.size()==0)return null;return new String(b.toByteArray(),StandardCharsets.UTF_8);}
    private static void send(Socket s,String type,byte[] body)throws IOException{BufferedOutputStream out=new BufferedOutputStream(s.getOutputStream());String h="HTTP/1.1 200 OK\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";out.write(h.getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();}
    private static List<String> localIpv4Addresses(){List<String[]> found=new ArrayList<>();try{for(NetworkInterface ni:Collections.list(NetworkInterface.getNetworkInterfaces())){if(!ni.isUp()||ni.isLoopback())continue;String name=ni.getName()==null?"":ni.getName().toLowerCase(Locale.US);if(name.startsWith("tun")||name.startsWith("rmnet"))continue;for(InetAddress a:Collections.list(ni.getInetAddresses())){if(!(a instanceof Inet4Address)||a.isLoopbackAddress()||a.isLinkLocalAddress())continue;int score=(name.startsWith("wlan")||name.startsWith("wifi"))?0:(name.startsWith("eth")?1:2);found.add(new String[]{String.valueOf(score),a.getHostAddress()});}}}catch(Throwable ignored){}Collections.sort(found,new Comparator<String[]>(){@Override public int compare(String[] a,String[] b){return Integer.compare(Integer.parseInt(a[0]),Integer.parseInt(b[0]));}});List<String> out=new ArrayList<>();for(String[] x:found)if(x[1]!=null&&!out.contains(x[1]))out.add(x[1]);return out;}

    private static final String PAGE = """
<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1,user-scalable=no'>
<title>AmbiP Control Center</title><style>
:root{color-scheme:dark}*{box-sizing:border-box}body{margin:0;background:#080a0d;color:#eef2f6;font-family:system-ui,-apple-system,sans-serif}.wrap{max-width:920px;margin:auto;padding:18px}.card{background:#14191f;border:1px solid #242b34;border-radius:16px;padding:16px;margin:12px 0}.grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}.row{display:flex;gap:10px;align-items:center;flex-wrap:wrap}h1{font-size:25px;margin:4px 0 2px}h2{font-size:18px;margin:0 0 12px}p{margin:8px 0}input[type=range]{width:100%}input[type=text],select{width:100%;padding:11px;background:#20262d;color:#fff;border:1px solid #3a4551;border-radius:9px}.v{float:right;color:#8ee7ff}.muted{color:#9ca8b5;font-size:13px}.good{color:#86efac}.bad{color:#fca5a5}.btn{border:1px solid #42505e;background:#252e37;color:#fff;border-radius:9px;padding:10px 13px;font-weight:650}.btn:active{transform:scale(.98)}.accent{background:#075b70;border-color:#20d4ff}.canvasWrap{background:#050607;border:1px solid #303a45;border-radius:14px;overflow:hidden;touch-action:none}.calCanvas{display:block;width:100%;aspect-ratio:16/9;touch-action:none}.legend{display:flex;gap:18px;font-size:13px;margin:9px 0}.dot{width:12px;height:12px;border-radius:50%;display:inline-block;margin-right:5px}.cyan{background:#24d8ff}.orange{background:#ffad42}.sectionSep{height:1px;background:#27303a;margin:14px 0}@media(max-width:650px){.grid{grid-template-columns:1fr}}
</style></head><body><div class='wrap'><h1>AmbiP Control Center</h1><div class='muted'>v0.20 · ultra-low-latency pipeline + drag calibration</div>

<div class='card'><h2>TV Source</h2><input id='source' type='text' placeholder='TV Source IP, e.g. 192.168.1.50'><div class='row' style='margin-top:10px'><button class='btn accent' onclick='saveSource()'>CONNECT / SAVE</button><button class='btn' onclick='latencyPreset()'>LOW LATENCY PRESET</button><span id='tvbadge' class='muted'>checking…</span></div><div class='grid'><p>Target FPS <span id='fpsv' class='v'></span><input id='fps' type='range' min='4' max='30' step='1'></p><p>Samples / zone <span id='samplesv' class='v'></span><input id='samples' type='range' min='1' max='6' step='1'></p><p>Edge depth <span id='stripv' class='v'></span><input id='strip' type='range' min='.03' max='.20' step='.01'></p><p><label><input id='autostart' type='checkbox'> Auto boot on TV</label></p></div></div>

<div class='card'><h2>Motion / latency</h2><div class='grid'><p>Noise smoothing <span id='smoothv' class='v'></span><input id='smooth' type='range' min='0' max='.30' step='.01'></p><p>Interpolation time <span id='interpmsv' class='v'></span><input id='interpms' type='range' min='0' max='100' step='1'></p><p>Adaptive response <span id='adaptivev' class='v'></span><input id='adaptive' type='range' min='0' max='1' step='.01'></p><p>Render Hz <span id='hzv' class='v'></span><input id='hz' type='range' min='30' max='120' step='10'></p></div><p><label><input id='interp' type='checkbox'> Interpolation enabled</label></p><div class='muted'>Smoothing now only suppresses tiny capture noise. Real motion bypasses it. Frames are coalesced so stale packets can never build a UI queue.</div></div>

<div class='card'><h2>Projection calibration</h2><div class='legend'><span><i class='dot cyan'></i>Outer projector border</span><span><i class='dot orange'></i>TV rectangle</span></div><div class='canvasWrap'><canvas id='geom' class='calCanvas' width='960' height='540'></canvas></div><div class='row' style='margin-top:12px'><button class='btn' onclick='resetTv()'>RESET TV</button><button class='btn' onclick='resetOuter()'>RESET OUTER</button><label><input id='calibrate' type='checkbox'> Show calibration overlay</label></div><div class='muted' style='margin-top:9px'>Drag the 4 cyan outer corners independently. Drag any orange TV corner; the TV remains a rectangle while its left/right/top/bottom edges follow the dragged corner. Changes are applied live.</div></div>

<div class='card'><h2>Color Cloud</h2><div class='grid'><p>Brightness <span id='brightnessv' class='v'></span><input id='brightness' type='range' min='.4' max='1.8' step='.01'></p><p>Saturation <span id='saturationv' class='v'></span><input id='saturation' type='range' min='.5' max='2.5' step='.01'></p><p>Spread <span id='spreadv' class='v'></span><input id='spread' type='range' min='.05' max='.9' step='.01'></p><p>Radius <span id='radiusv' class='v'></span><input id='radius' type='range' min='.08' max='.5' step='.01'></p><p>Opacity <span id='opacityv' class='v'></span><input id='opacity' type='range' min='.05' max='1' step='.01'></p><p>Edge pull <span id='edgepullv' class='v'></span><input id='edgepull' type='range' min='0' max='1' step='.01'></p><p>Softness <span id='softnessv' class='v'></span><input id='softness' type='range' min='0' max='1' step='.01'></p><p>Corner blend <span id='cornerblendv' class='v'></span><input id='cornerblend' type='range' min='0' max='1' step='.01'></p><p>Corner radius <span id='cornerradiusv' class='v'></span><input id='cornerradius' type='range' min='.7' max='2.4' step='.01'></p><p>Outer fade <span id='fadev' class='v'></span><input id='fade' type='range' min='.02' max='.42' step='.01'></p></div></div>

<div class='card'><h2>Dynamic response</h2><div class='grid'><p>Amount <span id='dynamicv' class='v'></span><input id='dynamic' type='range' min='0' max='1.5' step='.01'></p><p>Dynamic radius <span id='dynradiusv' class='v'></span><input id='dynradius' type='range' min='0' max='1.5' step='.01'></p><p>Stretch <span id='stretchv' class='v'></span><input id='stretch' type='range' min='0' max='2' step='.01'></p><p>Dynamic opacity <span id='dynopacityv' class='v'></span><input id='dynopacity' type='range' min='0' max='.8' step='.01'></p><p>Energy curve <span id='energygammav' class='v'></span><input id='energygamma' type='range' min='.4' max='2.5' step='.01'></p><p>Saturation weight <span id='satweightv' class='v'></span><input id='satweight' type='range' min='0' max='1' step='.01'></p><p>Luma weight <span id='lumaweightv' class='v'></span><input id='lumaweight' type='range' min='0' max='1' step='.01'></p></div></div>
</div>
<script>
const $=id=>document.getElementById(id);let S={},T={};const ranges=['smooth','interpms','adaptive','hz','brightness','saturation','spread','radius','opacity','edgepull','softness','cornerblend','cornerradius','fade','dynamic','dynradius','stretch','dynopacity','energygamma','satweight','lumaweight'];let saveTimer,srcTimer,drag=null;
function show(id,v){let e=$(id+'v');if(e)e.textContent=(typeof v==='number'&&!Number.isInteger(v))?v.toFixed(2):v}
function q(obj){return Object.entries(obj).map(([k,v])=>encodeURIComponent(k)+'='+encodeURIComponent(v)).join('&')}
async function setP(obj){await fetch('/api/settings?'+q(obj));}
function bind(){ranges.forEach(id=>{$(id).oninput=()=>{let v=Number($(id).value);show(id,v);clearTimeout(saveTimer);saveTimer=setTimeout(()=>setP({[id]:v}),35)}});$('interp').onchange=()=>setP({interp:$('interp').checked?1:0});$('calibrate').onchange=()=>setP({calibrate:$('calibrate').checked?1:0});['fps','samples','strip'].forEach(id=>{$(id).oninput=()=>{show(id,Number($(id).value));clearTimeout(srcTimer);srcTimer=setTimeout(saveTvSource,55)}});$('autostart').onchange=saveTvSource}
async function load(){S=await(await fetch('/api/status')).json();$('source').value=S.source||'';ranges.forEach(id=>{if(S[id]!==undefined){$(id).value=S[id];show(id,Number(S[id]))}});$('interp').checked=!!S.interp;$('calibrate').checked=!!S.calibrate;draw();await loadSource()}
async function loadSource(){try{let x=await(await fetch('/api/source/status')).json();if(!x.reachable){$('tvbadge').textContent='TV unreachable';$('tvbadge').className='bad';return}T=x.data||{};$('tvbadge').textContent=(T.active?'LIVE':'IDLE')+' · '+(T.fpsActual||0)+' fps · '+x.rttMs+' ms control RTT';$('tvbadge').className=T.active?'good':'muted';['fps','samples','strip'].forEach(id=>{if(T[id]!==undefined){$(id).value=T[id];show(id,Number(T[id]))}});$('autostart').checked=!!T.autostart}catch(e){}}
async function saveSource(){await setP({source:$('source').value.trim()});setTimeout(loadSource,200)}
async function saveTvSource(){let x={fps:$('fps').value,samples:$('samples').value,strip:$('strip').value,autostart:$('autostart').checked?1:0};await fetch('/api/source/settings?'+q(x));}
async function latencyPreset(){$('smooth').value=.03;show('smooth',.03);$('interpms').value=22;show('interpms',22);$('adaptive').value=.98;show('adaptive',.98);$('hz').value=60;show('hz',60);$('fps').value=16;show('fps',16);$('samples').value=1;show('samples',1);await setP({smooth:.03,interp:1,interpms:22,adaptive:.98,hz:60});await saveTvSource()}
function geom(){return {outer:[[S.k0??0,S.k1??0],[S.k2??1,S.k3??0],[S.k4??1,S.k5??1],[S.k6??0,S.k7??1]],tv:[[S.tv0??.2,S.tv1??.27],[S.tv2??.8,S.tv1??.27],[S.tv2??.8,S.tv3??.73],[S.tv0??.2,S.tv3??.73]]}}
function draw(){let c=$('geom'),x=c.getContext('2d'),g=geom(),w=c.width,h=c.height;x.clearRect(0,0,w,h);x.fillStyle='#06080b';x.fillRect(0,0,w,h);x.strokeStyle='#1d2630';x.lineWidth=2;for(let i=1;i<8;i++){x.beginPath();x.moveTo(i*w/8,0);x.lineTo(i*w/8,h);x.stroke()}for(let i=1;i<5;i++){x.beginPath();x.moveTo(0,i*h/5);x.lineTo(w,i*h/5);x.stroke()}poly(x,g.outer,w,h,'#24d8ff',4);poly(x,g.tv,w,h,'#ffad42',4);handles(x,g.outer,w,h,'#24d8ff');handles(x,g.tv,w,h,'#ffad42');x.fillStyle='#fff';x.font='24px system-ui';x.fillText('PROJECTOR OUTER',26,38);x.fillStyle='#ffcf8b';x.fillText('TV',((S.tv0+S.tv2)/2)*w-14,((S.tv1+S.tv3)/2)*h+8)}
function poly(x,p,w,h,col,lw){x.strokeStyle=col;x.lineWidth=lw;x.beginPath();p.forEach((a,i)=>{let X=a[0]*w,Y=a[1]*h;i?x.lineTo(X,Y):x.moveTo(X,Y)});x.closePath();x.stroke()}
function handles(x,p,w,h,col){p.forEach(a=>{x.beginPath();x.arc(a[0]*w,a[1]*h,14,0,Math.PI*2);x.fillStyle='#fff';x.fill();x.beginPath();x.arc(a[0]*w,a[1]*h,10,0,Math.PI*2);x.fillStyle=col;x.fill()})}
function pos(ev){let c=$('geom'),r=c.getBoundingClientRect();return [(ev.clientX-r.left)/r.width,(ev.clientY-r.top)/r.height]}
function nearest(p){let g=geom(),best=null,bd=.065;[['outer',g.outer],['tv',g.tv]].forEach(([kind,arr])=>arr.forEach((a,i)=>{let d=Math.hypot(a[0]-p[0],a[1]-p[1]);if(d<bd){bd=d;best={kind,i}}}));return best}
function moveDrag(p){p=[Math.max(0,Math.min(1,p[0])),Math.max(0,Math.min(1,p[1]))];if(drag.kind==='outer'){S['k'+drag.i*2]=p[0];S['k'+(drag.i*2+1)]=p[1]}else{let l=S.tv0,t=S.tv1,r=S.tv2,b=S.tv3,min=.08;if(drag.i===0||drag.i===3)l=Math.min(p[0],r-min);else r=Math.max(p[0],l+min);if(drag.i===0||drag.i===1)t=Math.min(p[1],b-min);else b=Math.max(p[1],t+min);S.tv0=Math.max(0,l);S.tv1=Math.max(0,t);S.tv2=Math.min(1,r);S.tv3=Math.min(1,b)}draw();clearTimeout(saveTimer);saveTimer=setTimeout(saveGeom,35)}
async function saveGeom(){let o={tv0:S.tv0,tv1:S.tv1,tv2:S.tv2,tv3:S.tv3};for(let i=0;i<8;i++)o['k'+i]=S['k'+i];await setP(o)}
function resetTv(){Object.assign(S,{tv0:.2,tv1:.27,tv2:.8,tv3:.73});draw();saveGeom()}function resetOuter(){Object.assign(S,{k0:0,k1:0,k2:1,k3:0,k4:1,k5:1,k6:0,k7:1});draw();saveGeom()}
let c=$('geom');c.onpointerdown=e=>{drag=nearest(pos(e));if(drag){c.setPointerCapture(e.pointerId);e.preventDefault()}};c.onpointermove=e=>{if(drag){moveDrag(pos(e));e.preventDefault()}};c.onpointerup=e=>{if(drag){moveDrag(pos(e));saveGeom();drag=null}};c.onpointercancel=()=>drag=null;
bind();load();setInterval(loadSource,1800);
</script></body></html>
""";
}
