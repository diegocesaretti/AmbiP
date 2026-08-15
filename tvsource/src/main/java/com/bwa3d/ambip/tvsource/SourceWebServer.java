package com.bwa3d.ambip.tvsource;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** API-26-safe HTTP API/settings server plus compact binary WebSocket light stream. */
public final class SourceWebServer {
    private static final String TAG = "AmbiPSourceWeb";
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final Context context;
    private final int port;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final ExecutorService pushPool = Executors.newSingleThreadExecutor();
    private final AtomicBoolean pushBusy = new AtomicBoolean();
    private final Set<Socket> wsClients = Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    private volatile boolean running;
    private volatile long lastPushAt;
    private ServerSocket serverSocket;

    public SourceWebServer(Context context, int port) { this.context = context.getApplicationContext(); this.port = port; }

    public synchronized String start() throws IOException {
        if (running) return SourceHub.getWebUrl();
        serverSocket = new ServerSocket(port, 24, InetAddress.getByName("0.0.0.0"));
        serverSocket.setReuseAddress(true);
        running = true;
        pool.execute(this::acceptLoop);
        List<String> ips = localIpv4Addresses();
        String primaryIp = ips.isEmpty() ? "127.0.0.1" : ips.get(0);
        StringBuilder all = new StringBuilder();
        for (String ip : ips) { if (all.length() > 0) all.append("   "); all.append("http://").append(ip).append(':').append(port); }
        if (all.length() == 0) all.append("http://127.0.0.1:").append(port);
        String primary = "http://" + primaryIp + ":" + port;
        SourceHub.setWebAddresses(primary, all.toString());
        return primary;
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) try { serverSocket.close(); } catch (IOException ignored) {}
        for (Socket s : wsClients) try { s.close(); } catch (IOException ignored) {}
        wsClients.clear(); SourceHub.setClients(0); pool.shutdownNow(); pushPool.shutdownNow();
    }

    public void broadcast(byte[] packet) {
        if (packet == null || packet.length == 0 || wsClients.isEmpty()) return;
        for (Socket socket : wsClients) {
            try { synchronized (socket) { writeWebSocketFrame(socket.getOutputStream(), 2, packet); } }
            catch (Throwable t) { wsClients.remove(socket); try { socket.close(); } catch (IOException ignored) {} }
        }
        SourceHub.setClients(wsClients.size());
    }

    /** Optional processed-capture push. Disabled unless a URL is configured; never blocks capture. */
    public void maybePushCapture(long nowMs) {
        String target=SourceHub.pushUrl;
        if(target==null||target.trim().isEmpty())return;
        long interval=Math.max(100L,Math.round(1000f/Math.max(1,SourceHub.pushFps)));
        if(nowMs-lastPushAt<interval||!pushBusy.compareAndSet(false,true))return;
        lastPushAt=nowMs;
        pushPool.execute(() -> {
            try { postJson(target.trim(),SourceHub.latestCaptureJson()); }
            catch(Throwable t){Log.d(TAG,"capture push: "+t.getClass().getSimpleName());}
            finally { pushBusy.set(false); }
        });
    }

    private void acceptLoop() {
        while (running) {
            try { Socket socket = serverSocket.accept(); socket.setTcpNoDelay(true); socket.setSendBufferSize(16*1024); socket.setSoTimeout(10000); pool.execute(() -> handle(socket)); }
            catch (IOException e) { if (running) Log.w(TAG, "accept", e); }
        }
    }

    private void handle(Socket socket) {
        boolean upgraded = false;
        try {
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream(),8192);
            String requestLine = readLine(in); if (requestLine == null || requestLine.length() == 0) return;
            Map<String,String> headers = new HashMap<>(); String line;
            while ((line = readLine(in)) != null && line.length() > 0) { int p=line.indexOf(':'); if(p>0)headers.put(line.substring(0,p).trim().toLowerCase(Locale.US),line.substring(p+1).trim()); }
            String[] first=requestLine.split(" "); String method=first.length>0?first[0]:"GET";String rawPath=first.length>1?first[1]:"/"; String path=rawPath,query=""; int q=rawPath.indexOf('?'); if(q>=0){path=rawPath.substring(0,q);query=rawPath.substring(q+1);}
            if("OPTIONS".equalsIgnoreCase(method)){send(socket,"text/plain",new byte[0]);return;}
            if ("/ws".equals(path) && "websocket".equalsIgnoreCase(headers.get("upgrade"))) {
                String key=headers.get("sec-websocket-key"); if(key==null)return; OutputStream out=socket.getOutputStream();
                String response="HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+websocketAccept(key)+"\r\n\r\n";
                out.write(response.getBytes(StandardCharsets.US_ASCII)); out.flush(); upgraded=true; socket.setSoTimeout(0); wsClients.add(socket); SourceHub.setClients(wsClients.size());
                byte[] latest=SourceHub.getLatestBinary(); if(latest!=null) synchronized(socket){writeWebSocketFrame(out,2,latest);} keepWebSocketAlive(in,socket); return;
            }
            if("/health".equals(path))send(socket,"text/plain; charset=utf-8","OK AmbiP TV Source v0.6\n".getBytes(StandardCharsets.UTF_8));
            else if("/state.json".equals(path))send(socket,"application/json; charset=utf-8",SourceHub.stateJson().getBytes(StandardCharsets.UTF_8));
            else if("/api/settings".equals(path)){applySettings(parseQuery(query));send(socket,"application/json; charset=utf-8",SourceHub.settingsJson().getBytes(StandardCharsets.UTF_8));}
            else if("/api/status".equals(path))send(socket,"application/json; charset=utf-8",SourceHub.settingsJson().getBytes(StandardCharsets.UTF_8));
            else if("/api/capture/latest".equals(path))send(socket,"application/json; charset=utf-8",SourceHub.latestCaptureJson().getBytes(StandardCharsets.UTF_8));
            else if("/api/capture/snapshot.jpg".equals(path))sendSnapshot(socket);
            else send(socket,"text/html; charset=utf-8",PAGE.getBytes(StandardCharsets.UTF_8));
        } catch(Throwable t){Log.d(TAG,"client: "+t.getClass().getSimpleName());}
        finally { if(upgraded)wsClients.remove(socket);SourceHub.setClients(wsClients.size());try{socket.close();}catch(IOException ignored){} }
    }

    private void sendSnapshot(Socket socket)throws IOException{
        if(!SourceHub.isActive()){sendStatus(socket,409,"text/plain; charset=utf-8","Capture is not active\n".getBytes(StandardCharsets.UTF_8));return;}
        long version=SourceHub.requestSnapshot();
        byte[] jpeg=SourceHub.awaitSnapshot(version,900L);
        if(jpeg==null||jpeg.length==0){sendStatus(socket,504,"text/plain; charset=utf-8","Snapshot timeout\n".getBytes(StandardCharsets.UTF_8));return;}
        send(socket,"image/jpeg",jpeg);
    }

    private void applySettings(Map<String,String> q) {
        SourceHub.applySettings(context,intOrNull(q.get("fps")),floatOrNull(q.get("strip")),intOrNull(q.get("samples")),
                boolOrNull(q.get("autostart")),q.containsKey("pushUrl")?q.get("pushUrl"):null,intOrNull(q.get("pushFps")));
    }

    private void keepWebSocketAlive(InputStream in,Socket socket)throws IOException{
        while(running&&!socket.isClosed()){
            int b0=in.read();if(b0<0)break;int b1=in.read();if(b1<0)break;int opcode=b0&15;boolean masked=(b1&128)!=0;long length=b1&127;
            if(length==126){int a=in.read(),b=in.read();if(a<0||b<0)break;length=((long)(a&255)<<8)|(b&255);}else if(length==127){length=0;for(int i=0;i<8;i++){int v=in.read();if(v<0)return;length=(length<<8)|(v&255);}}
            if(length>65536)break;byte[] mask=masked?readExactly(in,4):null;byte[] payload=readExactly(in,(int)length);if(masked&&mask!=null)for(int i=0;i<payload.length;i++)payload[i]^=mask[i&3];if(opcode==8)break;if(opcode==9)synchronized(socket){writeControlFrame(socket.getOutputStream(),10,payload);}
        }
    }

    private static void postJson(String target,String json)throws IOException{
        HttpURLConnection c=null;
        try{
            URL u=new URL(target);c=(HttpURLConnection)u.openConnection();c.setConnectTimeout(650);c.setReadTimeout(800);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setRequestProperty("Connection","close");
            byte[] body=json.getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(body.length);OutputStream out=c.getOutputStream();out.write(body);out.flush();out.close();
            int code=c.getResponseCode();if(code<200||code>=300)throw new IOException("HTTP "+code);
        }finally{if(c!=null)c.disconnect();}
    }

    private static byte[] readExactly(InputStream in,int length)throws IOException{byte[] out=new byte[length];int offset=0;while(offset<length){int n=in.read(out,offset,length-offset);if(n<0)throw new IOException("Unexpected EOF");offset+=n;}return out;}
    private static void writeWebSocketFrame(OutputStream out,int opcode,byte[] payload)throws IOException{out.write(0x80|(opcode&15));int len=payload.length;if(len<=125)out.write(len);else if(len<=65535){out.write(126);out.write((len>>8)&255);out.write(len&255);}else{out.write(127);long n=len;for(int i=7;i>=0;i--)out.write((int)((n>>(8*i))&255));}out.write(payload);out.flush();}
    private static void writeControlFrame(OutputStream out,int opcode,byte[] payload)throws IOException{out.write(0x80|(opcode&15));out.write(payload.length&127);out.write(payload);out.flush();}
    private static String websocketAccept(String key)throws Exception{MessageDigest sha1=MessageDigest.getInstance("SHA-1");byte[] digest=sha1.digest((key.trim()+WS_GUID).getBytes(StandardCharsets.US_ASCII));return Base64.encodeToString(digest,Base64.NO_WRAP);}
    private static Map<String,String> parseQuery(String query){Map<String,String> out=new HashMap<>();if(query==null||query.length()==0)return out;for(String part:query.split("&")){int p=part.indexOf('=');String k=p<0?part:part.substring(0,p),v=p<0?"":part.substring(p+1);try{out.put(URLDecoder.decode(k,"UTF-8"),URLDecoder.decode(v,"UTF-8"));}catch(Exception ignored){}}return out;}
    private static Integer intOrNull(String s){try{return s==null?null:Integer.parseInt(s);}catch(Exception e){return null;}}
    private static Float floatOrNull(String s){try{return s==null?null:Float.parseFloat(s);}catch(Exception e){return null;}}
    private static Boolean boolOrNull(String s){if(s==null)return null;return "1".equals(s)||"true".equalsIgnoreCase(s)||"on".equalsIgnoreCase(s);}
    private static String readLine(InputStream in)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();int c;boolean cr=false;while((c=in.read())>=0){if(cr&&c=='\n')break;if(cr){b.write('\r');cr=false;}if(c=='\r')cr=true;else b.write(c);if(b.size()>16384)break;}if(c<0&&b.size()==0)return null;return new String(b.toByteArray(),StandardCharsets.UTF_8);}
    private static void send(Socket socket,String type,byte[] body)throws IOException{sendStatus(socket,200,type,body);}
    private static void sendStatus(Socket socket,int code,String type,byte[] body)throws IOException{BufferedOutputStream out=new BufferedOutputStream(socket.getOutputStream());String reason=code==200?"OK":code==409?"Conflict":code==504?"Gateway Timeout":"Error";String head="HTTP/1.1 "+code+" "+reason+"\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+"\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET,POST,OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type\r\nConnection: close\r\n\r\n";out.write(head.getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();}

    private static List<String> localIpv4Addresses(){List<String[]> found=new ArrayList<>();try{for(NetworkInterface ni:Collections.list(NetworkInterface.getNetworkInterfaces())){if(!ni.isUp()||ni.isLoopback())continue;String name=ni.getName()==null?"":ni.getName().toLowerCase(Locale.US);if(name.startsWith("tun")||name.startsWith("ppp")||name.startsWith("rmnet"))continue;for(InetAddress a:Collections.list(ni.getInetAddresses())){if(!(a instanceof Inet4Address)||a.isLoopbackAddress()||a.isLinkLocalAddress())continue;String ip=a.getHostAddress();if(ip==null)continue;int score=(name.startsWith("wlan")||name.startsWith("wifi"))?0:(name.startsWith("eth")?1:2);found.add(new String[]{String.valueOf(score),ip});}}}catch(Throwable ignored){}Collections.sort(found,new Comparator<String[]>(){@Override public int compare(String[] a,String[] b){return Integer.compare(Integer.parseInt(a[0]),Integer.parseInt(b[0]));}});List<String> out=new ArrayList<>();for(String[] item:found)if(!out.contains(item[1]))out.add(item[1]);return out;}

    private static final String PAGE="<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>AmbiP TV Source</title><style>body{background:#0a0c0f;color:#eee;font-family:system-ui,sans-serif;max-width:680px;margin:auto;padding:22px}.card{background:#15191f;padding:18px;border-radius:12px;margin:14px 0}input{width:100%}.text{padding:9px;background:#222831;color:#fff;border:1px solid #39434f;border-radius:7px}.v{float:right;color:#9fd}.ok{color:#8d9}.note{color:#aaa;font-size:13px}.toggle{width:auto}button{background:#293440;color:white;border:1px solid #536273;border-radius:8px;padding:9px;margin:3px}</style></head><body><h2>AmbiP TV Source · v0.6 diagnostics</h2><div class='card'><b id='state'>Checking...</b><p id='meta' class='note'></p><p id='urls' class='note'></p></div><div class='card'><b>TV performance / latency</b><p>Target FPS <span id='fv' class='v'></span><input id='fps' type='range' min='4' max='30' step='1'></p><p>Edge depth <span id='sv' class='v'></span><input id='strip' type='range' min='.03' max='.20' step='.01'></p><p>Samples per zone <span id='pv' class='v'></span><input id='samples' type='range' min='1' max='6'></p><p><label><input id='autostart' class='toggle' type='checkbox'> Open AmbiP automatically after TV boot</label></p></div><div class='card'><b>Capture API</b><p class='note'>Processed edge data: <code>/api/capture/latest</code><br>On-demand 128x72 JPEG: <code>/api/capture/snapshot.jpg</code>. JPEG is generated only when requested.</p><button onclick=\"window.open('/api/capture/latest')\">OPEN LATEST JSON</button><button onclick=\"document.getElementById('shot').src='/api/capture/snapshot.jpg?t='+Date.now()\">TAKE SNAPSHOT</button><p><img id='shot' style='max-width:100%;image-rendering:auto'></p><p>Optional POST destination</p><input id='pushUrl' class='text' placeholder='http://server/api/ambip'><p>Push FPS <span id='pushFpsv' class='v'></span><input id='pushFps' type='range' min='1' max='10' step='1'></p><p class='note'>Leave URL empty to disable push. The normal projector WebSocket is unaffected.</p></div><script>const g=x=>document.getElementById(x);function labels(){g('fv').textContent=g('fps').value;g('sv').textContent=Number(g('strip').value).toFixed(2);g('pv').textContent=g('samples').value;g('pushFpsv').textContent=g('pushFps').value}let t;function save(){labels();clearTimeout(t);t=setTimeout(function(){fetch('/api/settings?fps='+g('fps').value+'&strip='+g('strip').value+'&samples='+g('samples').value+'&autostart='+(g('autostart').checked?1:0)+'&pushFps='+g('pushFps').value+'&pushUrl='+encodeURIComponent(g('pushUrl').value))},100)}async function load(){let s=await(await fetch('/api/status')).json();g('state').textContent=s.active?'LIVE':'IDLE';g('state').className=s.active?'ok':'';g('meta').textContent=s.status+' | '+s.fpsActual+' actual fps | '+s.analysisW+'x'+s.analysisH+' | '+s.clients+' client(s) | '+s.protocol;g('urls').textContent=s.urls;g('fps').value=s.fps;g('strip').value=s.strip;g('samples').value=s.samples;g('autostart').checked=!!s.autostart;g('pushUrl').value=s.pushUrl||'';g('pushFps').value=s.pushFps||2;labels()}['fps','strip','samples','pushFps'].forEach(function(x){g(x).oninput=save});g('autostart').onchange=save;g('pushUrl').onchange=save;load();setInterval(load,1800)</script></body></html>";
}
