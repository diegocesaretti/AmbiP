package com.bwa3d.ambip.tvsource;

import android.content.Context;
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
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Dependency-free HTTP settings server + WebSocket light-data stream. */
public final class SourceWebServer {
    private static final String TAG = "AmbiPSourceWeb";
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final Context context;
    private final int port;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Set<Socket> wsClients = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile boolean running;
    private ServerSocket serverSocket;

    public SourceWebServer(Context context, int port) {
        this.context = context.getApplicationContext();
        this.port = port;
    }

    public synchronized String start() throws IOException {
        if (running) return getUrl();
        serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);
        running = true;
        pool.execute(this::acceptLoop);
        String url = getUrl();
        SourceHub.setWebUrl(url);
        return url;
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) try { serverSocket.close(); } catch (IOException ignored) {}
        for (Socket s : wsClients) try { s.close(); } catch (IOException ignored) {}
        wsClients.clear();
        SourceHub.setClients(0);
        pool.shutdownNow();
    }

    public String getUrl() { return "http://" + localIpv4() + ":" + port; }

    public void broadcast(String json) {
        if (json == null || json.isEmpty()) return;
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        for (Socket socket : wsClients) {
            try {
                synchronized (socket) { writeWebSocketFrame(socket.getOutputStream(), payload); }
            } catch (Throwable t) {
                wsClients.remove(socket);
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
        SourceHub.setClients(wsClients.size());
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                pool.execute(() -> handle(socket));
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept", e);
            }
        }
    }

    private void handle(Socket socket) {
        boolean upgraded = false;
        try {
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            Map<String,String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int p = line.indexOf(':');
                if (p > 0) headers.put(line.substring(0,p).trim().toLowerCase(Locale.US), line.substring(p+1).trim());
            }
            String[] first = requestLine.split(" ");
            String rawPath = first.length > 1 ? first[1] : "/";
            String path = rawPath;
            String query = "";
            int q = rawPath.indexOf('?');
            if (q >= 0) { path = rawPath.substring(0,q); query = rawPath.substring(q+1); }

            if ("/ws".equals(path) && "websocket".equalsIgnoreCase(headers.get("upgrade"))) {
                String key = headers.get("sec-websocket-key");
                if (key == null) return;
                OutputStream out = socket.getOutputStream();
                String response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + websocketAccept(key) + "\r\n\r\n";
                out.write(response.getBytes(StandardCharsets.US_ASCII)); out.flush();
                upgraded = true;
                wsClients.add(socket); SourceHub.setClients(wsClients.size());
                synchronized (socket) { writeWebSocketFrame(out, SourceHub.getLatestJson().getBytes(StandardCharsets.UTF_8)); }
                keepWebSocketAlive(in, socket);
                return;
            }

            if ("/state.json".equals(path)) {
                send(socket, "application/json; charset=utf-8", SourceHub.getLatestJson().getBytes(StandardCharsets.UTF_8));
            } else if ("/api/settings".equals(path)) {
                applySettings(parseQuery(query));
                send(socket, "application/json; charset=utf-8", SourceHub.settingsJson().getBytes(StandardCharsets.UTF_8));
            } else if ("/api/status".equals(path)) {
                send(socket, "application/json; charset=utf-8", SourceHub.settingsJson().getBytes(StandardCharsets.UTF_8));
            } else {
                send(socket, "text/html; charset=utf-8", PAGE.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            Log.d(TAG, "client: " + t.getClass().getSimpleName());
        } finally {
            if (upgraded) wsClients.remove(socket);
            SourceHub.setClients(wsClients.size());
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void applySettings(Map<String,String> q) {
        Integer fps = intOrNull(q.get("fps"));
        Float strip = floatOrNull(q.get("strip"));
        Float smooth = floatOrNull(q.get("smooth"));
        Float bright = floatOrNull(q.get("brightness"));
        Float sat = floatOrNull(q.get("saturation"));
        SourceHub.applySettings(context, fps, strip, smooth, bright, sat);
    }

    private void keepWebSocketAlive(InputStream in, Socket socket) throws IOException {
        while (running && !socket.isClosed()) {
            int b0 = in.read(); if (b0 < 0) break;
            int b1 = in.read(); if (b1 < 0) break;
            int opcode = b0 & 0x0f; boolean masked = (b1 & 0x80) != 0;
            long length = b1 & 0x7f;
            if (length == 126) length = ((long)(in.read() & 0xff) << 8) | (in.read() & 0xff);
            else if (length == 127) { length = 0; for (int i=0;i<8;i++) length = (length << 8) | (in.read() & 0xff); }
            byte[] mask = masked ? in.readNBytes(4) : null;
            if (length > 65536) break;
            byte[] payload = in.readNBytes((int)length);
            if (masked && mask != null) for (int i=0;i<payload.length;i++) payload[i] ^= mask[i & 3];
            if (opcode == 8) break;
            if (opcode == 9) synchronized (socket) { writeControlFrame(socket.getOutputStream(), 10, payload); }
        }
    }

    private static void writeWebSocketFrame(OutputStream out, byte[] payload) throws IOException {
        out.write(0x81); int len = payload.length;
        if (len <= 125) out.write(len);
        else if (len <= 65535) { out.write(126); out.write((len >> 8) & 0xff); out.write(len & 0xff); }
        else { out.write(127); long n = len; for (int i=7;i>=0;i--) out.write((int)((n >> (8*i)) & 0xff)); }
        out.write(payload); out.flush();
    }

    private static void writeControlFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        out.write(0x80 | (opcode & 0x0f)); out.write(payload.length & 0x7f); out.write(payload); out.flush();
    }

    private static String websocketAccept(String key) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        return Base64.getEncoder().encodeToString(sha1.digest((key.trim()+WS_GUID).getBytes(StandardCharsets.US_ASCII)));
    }

    private static Map<String,String> parseQuery(String query) {
        Map<String,String> out = new HashMap<>();
        if (query == null || query.isEmpty()) return out;
        for (String part : query.split("&")) {
            int p = part.indexOf('=');
            String k = p < 0 ? part : part.substring(0,p);
            String v = p < 0 ? "" : part.substring(p+1);
            try { out.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8")); } catch (Exception ignored) {}
        }
        return out;
    }

    private static Integer intOrNull(String s) { try { return s == null ? null : Integer.parseInt(s); } catch (Exception e) { return null; } }
    private static Float floatOrNull(String s) { try { return s == null ? null : Float.parseFloat(s); } catch (Exception e) { return null; } }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); int c; boolean cr = false;
        while ((c = in.read()) >= 0) {
            if (cr && c == '\n') break;
            if (cr) { b.write('\r'); cr = false; }
            if (c == '\r') cr = true; else b.write(c);
            if (b.size() > 16384) break;
        }
        if (c < 0 && b.size() == 0) return null;
        return b.toString(StandardCharsets.UTF_8);
    }

    private static void send(Socket socket, String type, byte[] body) throws IOException {
        BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
        String head = "HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII)); out.write(body); out.flush();
    }

    private static String localIpv4() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
            }
        } catch (Throwable ignored) {}
        return "127.0.0.1";
    }

    private static final String PAGE = """
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>AmbiP TV Source</title><style>
:root{color-scheme:dark}*{box-sizing:border-box}body{margin:0;background:#090b0e;color:#eef;font-family:system-ui,sans-serif}header{padding:18px 20px;border-bottom:1px solid #242830}h1{font-size:20px;margin:0}.wrap{max-width:760px;margin:auto;padding:18px}.card{background:#11151b;border:1px solid #252b35;border-radius:14px;padding:16px;margin-bottom:14px}.row{display:grid;grid-template-columns:1fr 90px;gap:12px;align-items:center;margin:13px 0}input[type=range]{width:100%}.value{font:600 13px ui-monospace,monospace;text-align:right}.badge{display:inline-block;padding:7px 10px;border-radius:20px;background:#493521;font:600 12px ui-monospace,monospace}.stage{position:relative;aspect-ratio:16/9;background:#050608;border-radius:10px;overflow:hidden;margin-top:12px}.tv{position:absolute;left:16%;right:16%;top:18%;bottom:18%;background:#000;z-index:2}.edge{position:absolute;z-index:1;filter:blur(10px)}.top{left:12%;right:12%;top:5%;height:22%}.bottom{left:12%;right:12%;bottom:5%;height:22%}.left{left:4%;top:14%;bottom:14%;width:22%}.right{right:4%;top:14%;bottom:14%;width:22%}.note{color:#9aa3af;font-size:12px;line-height:1.4}</style></head>
<body><header><h1>AmbiP TV Source <span class="badge" id="state">CONNECTING</span></h1></header><div class="wrap">
<div class="card"><b>Light stream</b><div class="note" id="meta">—</div><div class="stage"><div id="top" class="edge top"></div><div id="right" class="edge right"></div><div id="bottom" class="edge bottom"></div><div id="left" class="edge left"></div><div class="tv"></div></div><p class="note">Only RGB + luminance + saturation are streamed. No screenshot, JPEG or video leaves the TV.</p></div>
<div class="card"><b>Capture / stream settings</b>
<div class="row"><label>Target FPS <input id="fps" type="range" min="5" max="30" step="1"></label><span id="fpsv" class="value"></span></div>
<div class="row"><label>Edge strip depth <input id="strip" type="range" min="0.03" max="0.25" step="0.01"></label><span id="stripv" class="value"></span></div>
<div class="row"><label>Temporal smoothing <input id="smooth" type="range" min="0" max="0.95" step="0.01"></label><span id="smoothv" class="value"></span></div>
<div class="row"><label>Source brightness <input id="brightness" type="range" min="0.40" max="2" step="0.01"></label><span id="brightnessv" class="value"></span></div>
<div class="row"><label>Source saturation <input id="saturation" type="range" min="0" max="2" step="0.01"></label><span id="saturationv" class="value"></span></div>
<p class="note">These settings are saved on the TV. The projector can keep its own Color Cloud geometry/dynamics independently.</p></div></div>
<script>
const $=x=>document.getElementById(x);let updating=false;
function grad(samples,vertical=false){if(!samples||!samples.length)return '#000';return `linear-gradient(${vertical?'180deg':'90deg'},${samples.map((x,i)=>`rgb(${x[0]},${x[1]},${x[2]}) ${Math.round(i*100/(samples.length-1||1))}%`).join(',')})`}
function render(s){$('top').style.background=grad(s.top);$('bottom').style.background=grad(s.bottom);$('left').style.background=grad(s.left,true);$('right').style.background=grad(s.right,true);$('meta').textContent=`${s.fps||0} fps · ${s.w||0}×${s.h||0} · protocol ${s.protocol||'ambip-light-v1'}`}
async function load(){const r=await fetch('/api/status');const s=await r.json();updating=true;for(const k of ['fps','strip','smoothing','brightness','saturation']){const id=k==='smoothing'?'smooth':k;$(id).value=s[k];$(id+'v').textContent=Number(s[k]).toFixed(k==='fps'?0:2)}updating=false}
let timer;function changed(){if(updating)return;for(const id of ['fps','strip','smooth','brightness','saturation'])$(id+'v').textContent=Number($(id).value).toFixed(id==='fps'?0:2);clearTimeout(timer);timer=setTimeout(()=>fetch(`/api/settings?fps=${$('fps').value}&strip=${$('strip').value}&smooth=${$('smooth').value}&brightness=${$('brightness').value}&saturation=${$('saturation').value}`),120)}
for(const id of ['fps','strip','smooth','brightness','saturation'])$(id).addEventListener('input',changed);
function connect(){const ws=new WebSocket(`ws://${location.host}/ws`);ws.onopen=()=>{$('state').textContent='LIVE';$('state').style.background='#173d2a'};ws.onmessage=e=>{try{render(JSON.parse(e.data))}catch(_){}};ws.onclose=()=>{$('state').textContent='RECONNECTING';$('state').style.background='#493521';setTimeout(connect,1000)};ws.onerror=()=>ws.close()}
load();connect();</script></body></html>
""";
}
