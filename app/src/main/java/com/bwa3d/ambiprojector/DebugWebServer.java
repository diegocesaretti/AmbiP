package com.bwa3d.ambiprojector;

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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tiny dependency-free HTTP + WebSocket server for LAN debugging. */
public final class DebugWebServer {
    private static final String TAG = "AmbiWebServer";
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final int port;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Set<Socket> wsClients = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile boolean running;
    private ServerSocket serverSocket;

    public DebugWebServer(int port) { this.port = port; }

    public synchronized String start() throws IOException {
        if (running) return getUrl();
        serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);
        running = true;
        pool.execute(this::acceptLoop);
        String url = getUrl();
        ScreenCaptureHub.setWebUrl(url);
        return url;
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) try { serverSocket.close(); } catch (IOException ignored) {}
        for (Socket socket : wsClients) try { socket.close(); } catch (IOException ignored) {}
        wsClients.clear();
        pool.shutdownNow();
    }

    public String getUrl() { return "http://" + localIpv4() + ":" + port; }

    public void broadcast(String json) {
        if (json == null || json.isEmpty()) return;
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        for (Socket socket : wsClients) {
            try {
                synchronized (socket) {
                    writeWebSocketFrame(socket.getOutputStream(), payload);
                }
            } catch (Throwable t) {
                wsClients.remove(socket);
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
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
            Map<String, String> headers = new ConcurrentHashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int p = line.indexOf(':');
                if (p > 0) headers.put(line.substring(0, p).trim().toLowerCase(Locale.US), line.substring(p + 1).trim());
            }
            String[] first = requestLine.split(" ");
            String path = first.length > 1 ? first[1] : "/";
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            if ("/ws".equals(path) && "websocket".equalsIgnoreCase(headers.get("upgrade"))) {
                String key = headers.get("sec-websocket-key");
                if (key == null) return;
                String accept = websocketAccept(key);
                OutputStream rawOut = socket.getOutputStream();
                String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
                rawOut.write(response.getBytes(StandardCharsets.US_ASCII));
                rawOut.flush();
                upgraded = true;
                wsClients.add(socket);
                synchronized (socket) { writeWebSocketFrame(rawOut, ScreenCaptureHub.getLatestJson().getBytes(StandardCharsets.UTF_8)); }
                keepWebSocketAlive(in, socket);
                return;
            }

            if ("/state.json".equals(path)) {
                send(socket, "application/json; charset=utf-8", ScreenCaptureHub.getLatestJson().getBytes(StandardCharsets.UTF_8));
            } else if ("/preview.jpg".equals(path)) {
                byte[] jpeg = ScreenCaptureHub.getPreviewJpeg();
                if (jpeg == null) send(socket, "text/plain; charset=utf-8", "No preview yet".getBytes(StandardCharsets.UTF_8), 404, "Not Found");
                else send(socket, "image/jpeg", jpeg);
            } else {
                send(socket, "text/html; charset=utf-8", PAGE.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            Log.d(TAG, "client closed: " + t.getClass().getSimpleName());
        } finally {
            if (!upgraded) try { socket.close(); } catch (IOException ignored) {}
            else {
                wsClients.remove(socket);
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }

    private void keepWebSocketAlive(InputStream in, Socket socket) throws IOException {
        while (running && !socket.isClosed()) {
            int b0 = in.read();
            if (b0 < 0) break;
            int b1 = in.read();
            if (b1 < 0) break;
            int opcode = b0 & 0x0f;
            boolean masked = (b1 & 0x80) != 0;
            long length = b1 & 0x7f;
            if (length == 126) length = ((long) in.read() << 8) | in.read();
            else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) length = (length << 8) | (in.read() & 0xff);
            }
            byte[] mask = masked ? in.readNBytes(4) : null;
            if (length > 1024 * 1024) break;
            byte[] payload = in.readNBytes((int) length);
            if (masked && mask != null) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
            if (opcode == 8) break;
            if (opcode == 9) {
                synchronized (socket) { writeControlFrame(socket.getOutputStream(), 10, payload); }
            }
        }
    }

    private static void writeWebSocketFrame(OutputStream out, byte[] payload) throws IOException {
        out.write(0x81);
        int len = payload.length;
        if (len <= 125) out.write(len);
        else if (len <= 65535) {
            out.write(126); out.write((len >> 8) & 0xff); out.write(len & 0xff);
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) out.write((len >> (8 * i)) & 0xff);
        }
        out.write(payload);
        out.flush();
    }

    private static void writeControlFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        out.write(0x80 | (opcode & 0x0f));
        out.write(payload.length & 0x7f);
        out.write(payload);
        out.flush();
    }

    private static String websocketAccept(String key) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        return Base64.getEncoder().encodeToString(sha1.digest((key.trim() + WS_GUID).getBytes(StandardCharsets.US_ASCII)));
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        boolean cr = false;
        while ((c = in.read()) >= 0) {
            if (cr && c == '\n') break;
            if (cr) { b.write('\r'); cr = false; }
            if (c == '\r') cr = true; else b.write(c);
            if (b.size() > 16384) break;
        }
        if (c < 0 && b.size() == 0) return null;
        return b.toString(StandardCharsets.UTF_8);
    }

    private static void send(Socket socket, String contentType, byte[] body) throws IOException {
        send(socket, contentType, body, 200, "OK");
    }

    private static void send(Socket socket, String contentType, byte[] body, int status, String reason) throws IOException {
        BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Cache-Control: no-store, no-cache, must-revalidate\r\n" +
                "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String localIpv4() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress address : Collections.list(ni.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) return address.getHostAddress();
                }
            }
        } catch (Throwable ignored) {}
        return "127.0.0.1";
    }

    private static final String PAGE = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>AmbiP Screen Debug</title>
<style>
:root{color-scheme:dark}*{box-sizing:border-box}body{margin:0;background:#090b0e;color:#eef;font-family:system-ui,-apple-system,sans-serif}header{padding:18px 22px;border-bottom:1px solid #242830;display:flex;justify-content:space-between;gap:15px;align-items:center}h1{font-size:20px;margin:0}.badge{font:600 12px ui-monospace,monospace;padding:7px 10px;border-radius:20px;background:#252a33}.wrap{display:grid;grid-template-columns:minmax(320px,1fr) minmax(360px,1.4fr);gap:18px;padding:18px}.card{background:#11151b;border:1px solid #252b35;border-radius:14px;padding:14px;min-width:0}.card h2{font-size:14px;margin:0 0 10px;color:#aeb7c5}.preview{width:100%;aspect-ratio:16/9;object-fit:contain;background:#000;border-radius:8px}.stats{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;margin-top:12px}.stat{background:#0b0e12;border-radius:8px;padding:10px}.stat b{display:block;font-size:18px}.stat span{font-size:11px;color:#98a2b3}.stage{position:relative;width:100%;aspect-ratio:16/9;background:#050608;border-radius:10px;overflow:hidden}.tv{position:absolute;left:14%;right:14%;top:17%;bottom:17%;background:#000;border:1px solid #333;box-shadow:0 0 20px #000;z-index:2}.edge{position:absolute;z-index:1;filter:blur(10px);opacity:.95}.top{left:11%;right:11%;top:5%;height:22%}.bottom{left:11%;right:11%;bottom:5%;height:22%}.left{left:3%;top:14%;bottom:14%;width:22%}.right{right:3%;top:14%;bottom:14%;width:22%}.note{font-size:12px;color:#9099a8;margin-top:10px;line-height:1.4}@media(max-width:850px){.wrap{grid-template-columns:1fr}}
</style>
</head>
<body>
<header><h1>AmbiP · Screen capture prototype</h1><div class="badge" id="conn">CONNECTING</div></header>
<div class="wrap">
 <section class="card"><h2>CAPTURED FRAME</h2><img id="preview" class="preview" src="/preview.jpg"><div class="stats"><div class="stat"><b id="fps">0.0</b><span>FPS</span></div><div class="stat"><b id="res">—</b><span>CAPTURE SIZE</span></div></div><div class="note">If Stremio's UI appears but the movie area is black, that stream is protected from screen capture. If the movie appears here, digital Ambilight capture is viable.</div></section>
 <section class="card"><h2>LIVE EDGE COLORS</h2><div class="stage"><div id="top" class="edge top"></div><div id="bottom" class="edge bottom"></div><div id="left" class="edge left"></div><div id="right" class="edge right"></div><div class="tv"></div></div><div class="note">32 samples on top/bottom and 18 on each side. The browser interpolates them into continuous gradients.</div></section>
</div>
<script>
const $=id=>document.getElementById(id);let lastPreview=0;
function grad(a,vertical=false){if(!a||!a.length)return '#000';const pts=a.map((c,i)=>`${c} ${Math.round(i*100/(a.length-1||1))}%`).join(',');return `linear-gradient(${vertical?'180deg':'90deg'},${pts})`}
function render(s){$('fps').textContent=(s.fps||0).toFixed(1);$('res').textContent=`${s.width||0}×${s.height||0}`;$('top').style.background=grad(s.top);$('bottom').style.background=grad(s.bottom);$('left').style.background=grad(s.left,true);$('right').style.background=grad(s.right,true);const now=Date.now();if(now-lastPreview>700){$('preview').src='/preview.jpg?t='+now;lastPreview=now}}
function connect(){const proto=location.protocol==='https:'?'wss':'ws';const ws=new WebSocket(`${proto}://${location.host}/ws`);ws.onopen=()=>{$('conn').textContent='LIVE';$('conn').style.background='#173d2a'};ws.onmessage=e=>{try{render(JSON.parse(e.data))}catch(_){}};ws.onclose=()=>{$('conn').textContent='RECONNECTING';$('conn').style.background='#493521';setTimeout(connect,1000)};ws.onerror=()=>ws.close()}
connect();setInterval(()=>{if(Date.now()-lastPreview>2500)$('preview').src='/preview.jpg?t='+Date.now()},2500);
</script>
</body></html>
""";
}