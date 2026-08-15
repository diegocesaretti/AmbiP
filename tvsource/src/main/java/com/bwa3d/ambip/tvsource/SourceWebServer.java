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
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
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

/** Small API-26-safe HTTP settings server plus WebSocket light stream. */
public final class SourceWebServer {
    private static final String TAG = "AmbiPSourceWeb";
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final Context context;
    private final int port;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Set<Socket> wsClients = Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    private volatile boolean running;
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
        wsClients.clear(); SourceHub.setClients(0); pool.shutdownNow();
    }

    public void broadcast(String json) {
        if (json == null || json.length() == 0 || wsClients.isEmpty()) return;
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        for (Socket socket : wsClients) {
            try { synchronized (socket) { writeWebSocketFrame(socket.getOutputStream(), payload); } }
            catch (Throwable t) { wsClients.remove(socket); try { socket.close(); } catch (IOException ignored) {} }
        }
        SourceHub.setClients(wsClients.size());
    }

    private void acceptLoop() {
        while (running) {
            try { Socket socket = serverSocket.accept(); socket.setTcpNoDelay(true); socket.setSoTimeout(10000); pool.execute(() -> handle(socket)); }
            catch (IOException e) { if (running) Log.w(TAG, "accept", e); }
        }
    }

    private void handle(Socket socket) {
        boolean upgraded = false;
        try {
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            String requestLine = readLine(in); if (requestLine == null || requestLine.length() == 0) return;
            Map<String,String> headers = new HashMap<>(); String line;
            while ((line = readLine(in)) != null && line.length() > 0) { int p=line.indexOf(':'); if(p>0)headers.put(line.substring(0,p).trim().toLowerCase(Locale.US),line.substring(p+1).trim()); }
            String[] first=requestLine.split(" "); String rawPath=first.length>1?first[1]:"/"; String path=rawPath,query=""; int q=rawPath.indexOf('?'); if(q>=0){path=rawPath.substring(0,q);query=rawPath.substring(q+1);}
            if ("/ws".equals(path) && "websocket".equalsIgnoreCase(headers.get("upgrade"))) {
                String key=headers.get("sec-websocket-key"); if(key==null)return; OutputStream out=socket.getOutputStream();
                String response="HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+websocketAccept(key)+"\r\n\r\n";
                out.write(response.getBytes(StandardCharsets.US_ASCII)); out.flush(); upgraded=true; socket.setSoTimeout(0); wsClients.add(socket); SourceHub.setClients(wsClients.size());
                synchronized(socket){writeWebSocketFrame(out,SourceHub.getLatestJson().getBytes(StandardCharsets.UTF_8));} keepWebSocketAlive(in,socket); return;
            }
            if("/health".equals(path))send(socket,"text/plain; charset=utf-8","OK AmbiP TV Source\n".getBytes(StandardCharsets.UTF_8));
            else if("/state.json".equals(path))send(socket,"application/json; charset=utf-8",SourceHub.getLatestJson().getBytes(StandardCharsets.UTF_8));
            else if("/api/settings".equals(path)){applySettings(parseQuery(query));send(socket,"application/json; charset=utf-8",SourceHub.settingsJson().getBytes(StandardCharsets.UTF_8));}
            else if("/api/status".equals(path))send(socket,"application/json; charset=utf-8",SourceHub.settingsJson().getBytes(StandardCharsets.UTF_8));
            else send(socket,"text/html; charset=utf-8",PAGE.getBytes(StandardCharsets.UTF_8));
        } catch(Throwable t){Log.d(TAG,"client: "+t.getClass().getSimpleName());}
        finally { if(upgraded)wsClients.remove(socket);SourceHub.setClients(wsClients.size());try{socket.close();}catch(IOException ignored){} }
    }

    private void applySettings(Map<String,String> q) {
        SourceHub.applySettings(context,intOrNull(q.get("fps")),floatOrNull(q.get("strip")),intOrNull(q.get("samples")),boolOrNull(q.get("autostart")));
    }

    private void keepWebSocketAlive(InputStream in,Socket socket)throws IOException{
        while(running&&!socket.isClosed()){
            int b0=in.read();if(b0<0)break;int b1=in.read();if(b1<0)break;int opcode=b0&15;boolean masked=(b1&128)!=0;long length=b1&127;
            if(length==126){int a=in.read(),b=in.read();if(a<0||b<0)break;length=((long)(a&255)<<8)|(b&255);}else if(length==127){length=0;for(int i=0;i<8;i++){int v=in.read();if(v<0)return;length=(length<<8)|(v&255);}}
            if(length>65536)break;byte[] mask=masked?readExactly(in,4):null;byte[] payload=readExactly(in,(int)length);if(masked&&mask!=null)for(int i=0;i<payload.length;i++)payload[i]^=mask[i&3];if(opcode==8)break;if(opcode==9)synchronized(socket){writeControlFrame(socket.getOutputStream(),10,payload);}
        }
    }

    private static byte[] readExactly(InputStream in,int length)throws IOException{byte[] out=new byte[length];int offset=0;while(offset<length){int n=in.read(out,offset,length-offset);if(n<0)throw new IOException("Unexpected EOF");offset+=n;}return out;}
    private static void writeWebSocketFrame(OutputStream out,byte[] payload)throws IOException{out.write(0x81);int len=payload.length;if(len<=125)out.write(len);else if(len<=65535){out.write(126);out.write((len>>8)&255);out.write(len&255);}else{out.write(127);long n=len;for(int i=7;i>=0;i--)out.write((int)((n>>(8*i))&255));}out.write(payload);out.flush();}
    private static void writeControlFrame(OutputStream out,int opcode,byte[] payload)throws IOException{out.write(0x80|(opcode&15));out.write(payload.length&127);out.write(payload);out.flush();}
    private static String websocketAccept(String key)throws Exception{MessageDigest sha1=MessageDigest.getInstance("SHA-1");byte[] digest=sha1.digest((key.trim()+WS_GUID).getBytes(StandardCharsets.US_ASCII));return Base64.encodeToString(digest,Base64.NO_WRAP);}
    private static Map<String,String> parseQuery(String query){Map<String,String> out=new HashMap<>();if(query==null||query.length()==0)return out;for(String part:query.split("&")){int p=part.indexOf('=');String k=p<0?part:part.substring(0,p),v=p<0?"":part.substring(p+1);try{out.put(URLDecoder.decode(k,"UTF-8"),URLDecoder.decode(v,"UTF-8"));}catch(Exception ignored){}}return out;}
    private static Integer intOrNull(String s){try{return s==null?null:Integer.parseInt(s);}catch(Exception e){return null;}}
    private static Float floatOrNull(String s){try{return s==null?null:Float.parseFloat(s);}catch(Exception e){return null;}}
    private static Boolean boolOrNull(String s){if(s==null)return null;return "1".equals(s)||"true".equalsIgnoreCase(s)||"on".equalsIgnoreCase(s);}
    private static String readLine(InputStream in)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();int c;boolean cr=false;while((c=in.read())>=0){if(cr&&c=='\n')break;if(cr){b.write('\r');cr=false;}if(c=='\r')cr=true;else b.write(c);if(b.size()>16384)break;}if(c<0&&b.size()==0)return null;return new String(b.toByteArray(),StandardCharsets.UTF_8);}
    private static void send(Socket socket,String type,byte[] body)throws IOException{BufferedOutputStream out=new BufferedOutputStream(socket.getOutputStream());String head="HTTP/1.1 200 OK\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";out.write(head.getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();}

    private static List<String> localIpv4Addresses(){List<String[]> found=new ArrayList<>();try{for(NetworkInterface ni:Collections.list(NetworkInterface.getNetworkInterfaces())){if(!ni.isUp()||ni.isLoopback())continue;String name=ni.getName()==null?"":ni.getName().toLowerCase(Locale.US);if(name.startsWith("tun")||name.startsWith("ppp")||name.startsWith("rmnet"))continue;for(InetAddress a:Collections.list(ni.getInetAddresses())){if(!(a instanceof Inet4Address)||a.isLoopbackAddress()||a.isLinkLocalAddress())continue;String ip=a.getHostAddress();if(ip==null)continue;int score=(name.startsWith("wlan")||name.startsWith("wifi"))?0:(name.startsWith("eth")?1:2);found.add(new String[]{String.valueOf(score),ip});}}}catch(Throwable ignored){}Collections.sort(found,new Comparator<String[]>(){@Override public int compare(String[] a,String[] b){return Integer.compare(Integer.parseInt(a[0]),Integer.parseInt(b[0]));}});List<String> out=new ArrayList<>();for(String[] item:found)if(!out.contains(item[1]))out.add(item[1]);return out;}

    private static final String PAGE="<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>AmbiP TV Source</title><style>body{background:#0a0c0f;color:#eee;font-family:system-ui,sans-serif;max-width:680px;margin:auto;padding:22px}.card{background:#15191f;padding:18px;border-radius:12px;margin:14px 0}input{width:100%}.v{float:right;color:#9fd}.ok{color:#8d9}.note{color:#aaa;font-size:13px}.toggle{width:auto}</style></head><body><h2>AmbiP TV Source v0.3</h2><div class='card'><b id='state'>Checking...</b><p id='meta' class='note'></p><p id='urls' class='note'></p></div><div class='card'><b>TV performance / latency</b><p>Target FPS <span id='fv' class='v'></span><input id='fps' type='range' min='4' max='30' step='1'></p><p>Edge depth <span id='sv' class='v'></span><input id='strip' type='range' min='.03' max='.20' step='.01'></p><p>Samples per zone <span id='pv' class='v'></span><input id='samples' type='range' min='1' max='6'></p><p><label><input id='autostart' class='toggle' type='checkbox'> Open AmbiP automatically after TV boot</label></p><p class='note'>Oreo can open the capture permission screen automatically on boot, but Android still requires you to approve screen capture. Start around 8-12 fps / 2 samples; try 16-20 fps only if the TV stays responsive.</p></div><script>const g=x=>document.getElementById(x);function labels(){g('fv').textContent=g('fps').value;g('sv').textContent=Number(g('strip').value).toFixed(2);g('pv').textContent=g('samples').value}let t;function save(){labels();clearTimeout(t);t=setTimeout(function(){fetch('/api/settings?fps='+g('fps').value+'&strip='+g('strip').value+'&samples='+g('samples').value+'&autostart='+(g('autostart').checked?1:0))},100)}async function load(){let s=await(await fetch('/api/status')).json();g('state').textContent=s.active?'LIVE':'IDLE';g('state').className=s.active?'ok':'';g('meta').textContent=s.status+' | '+s.analysisW+'x'+s.analysisH+' analysis | '+s.clients+' client(s)';g('urls').textContent=s.urls;g('fps').value=s.fps;g('strip').value=s.strip;g('samples').value=s.samples;g('autostart').checked=!!s.autostart;labels()}['fps','strip','samples'].forEach(function(x){g(x).oninput=save});g('autostart').onchange=save;load();setInterval(load,2000)</script></body></html>";
}
