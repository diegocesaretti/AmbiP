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
        putFloat(e,q,"smooth","networkSmoothing",0f,0.95f);
        putFloat(e,q,"brightness","cloudBrightness",0.40f,1.80f);
        putFloat(e,q,"saturation","cloudSaturation",0.50f,2.50f);
        putFloat(e,q,"spread","cloudSpread",0.05f,0.90f);
        putFloat(e,q,"radius","cloudRadius",0.08f,0.50f);
        putFloat(e,q,"opacity","cloudOpacity",0.05f,1.00f);
        putFloat(e,q,"dynamic","cloudDynamicAmount",0f,1.50f);
        putFloat(e,q,"stretch","cloudDynamicStretch",0f,2.00f);
        putFloat(e,q,"fade","outerFade",0.02f,0.42f);
        e.apply();
        if (listener != null) {
            listener.onSettingsChanged();
            if (source != null) listener.onSourceChanged(source.trim());
        }
    }

    private static void putFloat(SharedPreferences.Editor e,Map<String,String> q,String key,String pref,float lo,float hi){
        String s=q.get(key);if(s==null)return;try{float v=Float.parseFloat(s);e.putFloat(pref,Math.max(lo,Math.min(hi,v)));}catch(Exception ignored){}
    }

    private String settingsJson() {
        return "{\"source\":\""+escape(prefs.getString("networkTvSource",""))+"\","+
                "\"smooth\":"+f(prefs.getFloat("networkSmoothing",0.45f))+","+
                "\"brightness\":"+f(prefs.getFloat("cloudBrightness",1.08f))+","+
                "\"saturation\":"+f(prefs.getFloat("cloudSaturation",1.32f))+","+
                "\"spread\":"+f(prefs.getFloat("cloudSpread",0.42f))+","+
                "\"radius\":"+f(prefs.getFloat("cloudRadius",0.26f))+","+
                "\"opacity\":"+f(prefs.getFloat("cloudOpacity",0.60f))+","+
                "\"dynamic\":"+f(prefs.getFloat("cloudDynamicAmount",0.85f))+","+
                "\"stretch\":"+f(prefs.getFloat("cloudDynamicStretch",0.85f))+","+
                "\"fade\":"+f(prefs.getFloat("outerFade",0.16f))+"}";
    }

    private static String f(float v){return String.format(Locale.US,"%.3f",v);}
    private static String escape(String s){if(s==null)return"";return s.replace("\\","\\\\").replace("\"","\\\"");}

    private static Map<String,String> parseQuery(String query){
        Map<String,String> out=new HashMap<>();if(query==null)return out;for(String p:query.split("&")){int x=p.indexOf('=');String k=x<0?p:p.substring(0,x);String v=x<0?"":p.substring(x+1);try{out.put(URLDecoder.decode(k,"UTF-8"),URLDecoder.decode(v,"UTF-8"));}catch(Exception ignored){}}return out;
    }

    private static String readLine(InputStream in)throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();int c;boolean cr=false;while((c=in.read())>=0){if(cr&&c=='\n')break;if(cr){b.write('\r');cr=false;}if(c=='\r')cr=true;else b.write(c);if(b.size()>16384)break;}if(c<0&&b.size()==0)return null;return new String(b.toByteArray(),StandardCharsets.UTF_8);
    }

    private static void send(Socket s,String type,byte[] body)throws IOException{
        BufferedOutputStream out=new BufferedOutputStream(s.getOutputStream());String h="HTTP/1.1 200 OK\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";out.write(h.getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
    }

    private static List<String> localIpv4Addresses(){
        List<String[]> found=new ArrayList<>();try{for(NetworkInterface ni:Collections.list(NetworkInterface.getNetworkInterfaces())){if(!ni.isUp()||ni.isLoopback())continue;String name=ni.getName()==null?"":ni.getName().toLowerCase(Locale.US);if(name.startsWith("tun")||name.startsWith("rmnet"))continue;for(InetAddress a:Collections.list(ni.getInetAddresses())){if(!(a instanceof Inet4Address)||a.isLoopbackAddress()||a.isLinkLocalAddress())continue;int score=(name.startsWith("wlan")||name.startsWith("wifi"))?0:(name.startsWith("eth")?1:2);found.add(new String[]{String.valueOf(score),a.getHostAddress()});}}}catch(Throwable ignored){}Collections.sort(found,new Comparator<String[]>(){@Override public int compare(String[] a,String[] b){return Integer.compare(Integer.parseInt(a[0]),Integer.parseInt(b[0]));}});List<String> out=new ArrayList<>();for(String[] x:found)if(x[1]!=null&&!out.contains(x[1]))out.add(x[1]);return out;
    }

    private static final String PAGE="<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>AmbiP Projector</title><style>body{background:#090b0e;color:#eee;font-family:sans-serif;max-width:680px;margin:auto;padding:22px}.card{background:#15191f;padding:18px;border-radius:12px;margin:14px 0}input{width:100%}.text{padding:10px;width:100%;background:#222;color:#fff;border:0}.v{float:right;color:#9fd}.note{color:#aaa;font-size:13px}</style></head><body><h2>AmbiP Projector</h2><div class='card'>TV Source<input id='source' class='text' placeholder='192.168.1.50'></div><div class='card'><b>Processing / Color Cloud</b><p>Smoothing <span id='smoothv' class='v'></span><input id='smooth' type='range' min='0' max='.95' step='.01'></p><p>Brightness <span id='brightnessv' class='v'></span><input id='brightness' type='range' min='.4' max='1.8' step='.01'></p><p>Saturation <span id='saturationv' class='v'></span><input id='saturation' type='range' min='.5' max='2.5' step='.01'></p><p>Spread <span id='spreadv' class='v'></span><input id='spread' type='range' min='.05' max='.9' step='.01'></p><p>Radius <span id='radiusv' class='v'></span><input id='radius' type='range' min='.08' max='.5' step='.01'></p><p>Opacity <span id='opacityv' class='v'></span><input id='opacity' type='range' min='.05' max='1' step='.01'></p><p>Dynamic <span id='dynamicv' class='v'></span><input id='dynamic' type='range' min='0' max='1.5' step='.01'></p><p>Stretch <span id='stretchv' class='v'></span><input id='stretch' type='range' min='0' max='2' step='.01'></p><p>Outer fade <span id='fadev' class='v'></span><input id='fade' type='range' min='.02' max='.42' step='.01'></p><p class='note'>These calculations run on the projector, not on the TV.</p></div><script>const g=x=>document.getElementById(x),keys=['smooth','brightness','saturation','spread','radius','opacity','dynamic','stretch','fade'];function labels(){keys.forEach(k=>g(k+'v').textContent=Number(g(k).value).toFixed(2))}async function load(){let s=await(await fetch('/api/status')).json();g('source').value=s.source;keys.forEach(k=>g(k).value=s[k]);labels()}let t;function save(){labels();clearTimeout(t);t=setTimeout(()=>{let q='source='+encodeURIComponent(g('source').value);keys.forEach(k=>q+='&'+k+'='+g(k).value);fetch('/api/settings?'+q)},150)}keys.forEach(k=>g(k).oninput=save);g('source').onchange=save;load()</script></body></html>";
}
