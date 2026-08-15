package com.bwa3d.ambiprojector;

import android.graphics.Color;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lightweight reconnecting WebSocket client for the TV Source ambip-light-v1 metadata stream. */
public final class LightStreamClient {
    public interface Listener {
        void onState(AmbilightState state);
        void onStatus(String status, boolean connected);
    }

    private static final String TAG = "AmbiPLightClient";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Listener listener;
    private volatile boolean running;
    private volatile Socket socket;
    private String host;
    private int port;

    public LightStreamClient(Listener listener) { this.listener = listener; }

    public synchronized void connect(String address) {
        stopSocket();
        Endpoint e = Endpoint.parse(address);
        host = e.host;
        port = e.port;
        running = true;
        worker.execute(this::connectionLoop);
    }

    public synchronized void stop() {
        running = false;
        stopSocket();
        worker.shutdownNow();
    }

    private void connectionLoop() {
        while (running) {
            try {
                notifyStatus("Connecting to " + host + ":" + port + "…", false);
                Socket s = new Socket();
                socket = s;
                s.setTcpNoDelay(true);
                s.connect(new InetSocketAddress(host, port), 3000);
                s.setSoTimeout(0);
                BufferedInputStream in = new BufferedInputStream(s.getInputStream());
                BufferedOutputStream out = new BufferedOutputStream(s.getOutputStream());
                websocketHandshake(in, out);
                notifyStatus("LIVE · " + host + ":" + port, true);
                readFrames(in, out);
            } catch (Throwable t) {
                if (running) {
                    Log.d(TAG, "stream reconnect: " + t.getClass().getSimpleName());
                    notifyStatus("Disconnected · retrying", false);
                }
            } finally {
                stopSocket();
            }
            if (running) try { Thread.sleep(1000L); } catch (InterruptedException ignored) { break; }
        }
    }

    private void websocketHandshake(InputStream in, OutputStream out) throws Exception {
        byte[] random = new byte[16]; new SecureRandom().nextBytes(random);
        String key = Base64.getEncoder().encodeToString(random);
        String request = "GET /ws HTTP/1.1\r\n" +
                "Host: " + host + ":" + port + "\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: " + key + "\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(request.getBytes(StandardCharsets.US_ASCII)); out.flush();
        String status = readLine(in);
        if (status == null || !status.contains(" 101 ")) throw new IOException("WebSocket upgrade failed: " + status);
        String line; while ((line = readLine(in)) != null && !line.isEmpty()) {}
    }

    private void readFrames(InputStream in, OutputStream out) throws Exception {
        while (running && socket != null && !socket.isClosed()) {
            int b0 = in.read(); if (b0 < 0) throw new IOException("EOF");
            int b1 = in.read(); if (b1 < 0) throw new IOException("EOF");
            int opcode = b0 & 0x0f;
            boolean masked = (b1 & 0x80) != 0;
            long length = b1 & 0x7f;
            if (length == 126) length = ((long)(in.read() & 255) << 8) | (in.read() & 255);
            else if (length == 127) { length = 0; for (int i=0;i<8;i++) length = (length << 8) | (in.read() & 255); }
            if (length < 0 || length > 1024 * 1024) throw new IOException("Invalid frame length");
            byte[] mask = masked ? in.readNBytes(4) : null;
            byte[] payload = in.readNBytes((int)length);
            if (payload.length != (int)length) throw new IOException("Short frame");
            if (masked && mask != null) for (int i=0;i<payload.length;i++) payload[i] ^= mask[i & 3];
            if (opcode == 8) throw new IOException("Server closed");
            if (opcode == 9) { writeMaskedControl(out, 10, payload); continue; }
            if (opcode != 1) continue;
            parseLightFrame(new String(payload, StandardCharsets.UTF_8));
        }
    }

    private void parseLightFrame(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        if (!"ambip-light-v1".equals(o.optString("protocol"))) return;
        int[] top = colors(o.getJSONArray("top"), AmbilightState.H_SEGMENTS);
        int[] bottom = colors(o.getJSONArray("bottom"), AmbilightState.H_SEGMENTS);
        int[] left = colors(o.getJSONArray("left"), AmbilightState.V_SEGMENTS);
        int[] right = colors(o.getJSONArray("right"), AmbilightState.V_SEGMENTS);
        AmbilightState state = new AmbilightState(top, bottom, left, right,
                (float)o.optDouble("fps", 0), o.optInt("w", 0), o.optInt("h", 0), 1f);
        if (listener != null) listener.onState(state);
    }

    private static int[] colors(JSONArray a, int required) throws Exception {
        int[] out = new int[required];
        for (int i=0;i<required;i++) {
            JSONArray sample = a.getJSONArray(Math.min(i, a.length()-1));
            out[i] = Color.rgb(clamp(sample.optInt(0)), clamp(sample.optInt(1)), clamp(sample.optInt(2)));
        }
        return out;
    }

    private void notifyStatus(String value, boolean connected) {
        if (listener != null) listener.onStatus(value, connected);
    }

    private synchronized void stopSocket() {
        Socket s = socket; socket = null;
        if (s != null) try { s.close(); } catch (IOException ignored) {}
    }

    private static void writeMaskedControl(OutputStream out, int opcode, byte[] payload) throws IOException {
        byte[] mask = new byte[4]; new SecureRandom().nextBytes(mask);
        out.write(0x80 | (opcode & 0x0f)); out.write(0x80 | (payload.length & 0x7f)); out.write(mask);
        for (int i=0;i<payload.length;i++) out.write(payload[i] ^ mask[i & 3]); out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); int c; boolean cr=false;
        while ((c=in.read())>=0) { if(cr&&c=='\n')break; if(cr){b.write('\r');cr=false;} if(c=='\r')cr=true;else b.write(c); if(b.size()>16384)break; }
        if(c<0&&b.size()==0)return null; return b.toString(StandardCharsets.UTF_8);
    }

    private static int clamp(int v){return Math.max(0,Math.min(255,v));}

    private static final class Endpoint {
        final String host; final int port;
        Endpoint(String host,int port){this.host=host;this.port=port;}
        static Endpoint parse(String raw) {
            String s = raw == null ? "" : raw.trim();
            s = s.replaceFirst("^wss?://", "").replaceFirst("^https?://", "");
            int slash=s.indexOf('/'); if(slash>=0)s=s.substring(0,slash);
            String host=s; int port=8080;
            int colon=s.lastIndexOf(':');
            if(colon>0 && colon<s.length()-1){try{port=Integer.parseInt(s.substring(colon+1));host=s.substring(0,colon);}catch(Exception ignored){}}
            if(host.isEmpty())host="127.0.0.1";
            return new Endpoint(host,port);
        }
    }
}
