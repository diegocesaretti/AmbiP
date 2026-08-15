package com.bwa3d.ambiprojector;

import android.graphics.Color;
import android.util.Base64;
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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Reconnecting low-latency WebSocket client for AmbiP light data. */
public final class LightStreamClient {
    public interface Listener {
        void onState(AmbilightState state);
        void onStatus(String status, boolean connected);
    }

    private static final String TAG = "AmbiPLightClient";
    private static final int BINARY_HEADER = 18;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Listener listener;
    private volatile boolean running;
    private volatile Socket socket;
    /** Maximum smoothing used only for small/noisy changes. Large changes bypass it. */
    private volatile float smoothing = 0.25f;
    private volatile int generation;
    private String host;
    private int port;
    private int[] prevTop, prevBottom, prevLeft, prevRight;

    public LightStreamClient(Listener listener) { this.listener = listener; }
    public void setSmoothing(float value) { smoothing = Math.max(0f, Math.min(0.85f, value)); }

    public synchronized void connect(String address) {
        stopSocket();
        Endpoint e = Endpoint.parse(address);
        host = e.host;
        port = e.port;
        running = true;
        int g = ++generation;
        prevTop = prevBottom = prevLeft = prevRight = null;
        worker.execute(() -> connectionLoop(g));
    }

    public synchronized void stop() {
        running = false;
        generation++;
        stopSocket();
        worker.shutdownNow();
    }

    private void connectionLoop(int g) {
        while (running && g == generation) {
            try {
                notifyStatus("Connecting to " + host + ":" + port + "…", false);
                Socket s = new Socket();
                socket = s;
                s.setTcpNoDelay(true);
                s.setReceiveBufferSize(16 * 1024);
                s.connect(new InetSocketAddress(host, port), 2200);
                s.setSoTimeout(0);
                BufferedInputStream in = new BufferedInputStream(s.getInputStream(), 8192);
                BufferedOutputStream out = new BufferedOutputStream(s.getOutputStream(), 2048);
                websocketHandshake(in, out);
                if (g != generation) break;
                notifyStatus("LIVE · " + host + ":" + port, true);
                readFrames(in, out, g);
            } catch (Throwable t) {
                if (running && g == generation) {
                    Log.d(TAG, "stream reconnect: " + t.getClass().getSimpleName());
                    notifyStatus("Disconnected · retrying", false);
                }
            } finally {
                stopSocket();
            }
            if (running && g == generation) {
                try { Thread.sleep(350L); } catch (InterruptedException ignored) { break; }
            }
        }
    }

    private void websocketHandshake(InputStream in, OutputStream out) throws Exception {
        byte[] random = new byte[16]; new SecureRandom().nextBytes(random);
        String key = Base64.encodeToString(random, Base64.NO_WRAP);
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

    private void readFrames(InputStream in, OutputStream out, int g) throws Exception {
        while (running && g == generation && socket != null && !socket.isClosed()) {
            int b0 = in.read(); if (b0 < 0) throw new IOException("EOF");
            int b1 = in.read(); if (b1 < 0) throw new IOException("EOF");
            int opcode = b0 & 15;
            boolean masked = (b1 & 128) != 0;
            long length = b1 & 127;
            if (length == 126) {
                int a=in.read(), b=in.read(); if(a<0||b<0)throw new IOException("EOF");
                length=((long)(a&255)<<8)|(b&255);
            } else if (length == 127) {
                length=0; for(int i=0;i<8;i++){int v=in.read();if(v<0)throw new IOException("EOF");length=(length<<8)|(v&255);}
            }
            if (length < 0 || length > 1024 * 1024) throw new IOException("Invalid frame length");
            byte[] mask = masked ? readExactly(in,4) : null;
            byte[] payload = readExactly(in,(int)length);
            if (masked && mask != null) for (int i=0;i<payload.length;i++) payload[i] ^= mask[i & 3];
            if (opcode == 8) throw new IOException("Server closed");
            if (opcode == 9) { writeMaskedControl(out,10,payload); continue; }
            if (opcode == 2) { parseBinaryFrame(payload); continue; }
            if (opcode == 1) parseLightFrame(new String(payload, StandardCharsets.UTF_8));
        }
    }

    /** ambip-light-v3 binary packet: 18-byte header + packed RGB samples. */
    private void parseBinaryFrame(byte[] payload) {
        if (payload == null || payload.length < BINARY_HEADER) return;
        if (payload[0] != 'A' || payload[1] != 'B' || payload[2] != 'P' || payload[3] != '3') return;
        ByteBuffer b = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        b.position(4);
        int version = b.get() & 255;
        b.get(); // flags/reserved
        b.getInt(); // sequence
        float fps = (b.getShort() & 0xffff) / 100f;
        int width = b.getShort() & 0xffff;
        int height = b.getShort() & 0xffff;
        int hs = b.get() & 255;
        int vs = b.get() & 255;
        if (version != 3 || hs <= 0 || vs <= 0) return;
        int need = BINARY_HEADER + (hs + vs + hs + vs) * 3;
        if (payload.length < need) return;

        int[] top = readColors(b, hs, AmbilightState.H_SEGMENTS);
        int[] right = readColors(b, vs, AmbilightState.V_SEGMENTS);
        int[] bottom = readColors(b, hs, AmbilightState.H_SEGMENTS);
        int[] left = readColors(b, vs, AmbilightState.V_SEGMENTS);
        deliver(top,bottom,left,right,fps,width,height);
    }

    private static int[] readColors(ByteBuffer b,int sourceCount,int required) {
        int[] src = new int[sourceCount];
        for(int i=0;i<sourceCount;i++) src[i]=0xff000000|((b.get()&255)<<16)|((b.get()&255)<<8)|(b.get()&255);
        if(sourceCount==required) return src;
        int[] out=new int[required];
        for(int i=0;i<required;i++){
            int idx=required==1?0:Math.round(i*(sourceCount-1)/(float)(required-1));
            out[i]=src[Math.max(0,Math.min(sourceCount-1,idx))];
        }
        return out;
    }

    private void parseLightFrame(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        String protocol = o.optString("protocol");
        int[] top, bottom, left, right;
        if ("ambip-light-v2".equals(protocol)) {
            top = packedColors(o.getJSONArray("top"), AmbilightState.H_SEGMENTS);
            bottom = packedColors(o.getJSONArray("bottom"), AmbilightState.H_SEGMENTS);
            left = packedColors(o.getJSONArray("left"), AmbilightState.V_SEGMENTS);
            right = packedColors(o.getJSONArray("right"), AmbilightState.V_SEGMENTS);
        } else if ("ambip-light-v1".equals(protocol)) {
            top = legacyColors(o.getJSONArray("top"), AmbilightState.H_SEGMENTS);
            bottom = legacyColors(o.getJSONArray("bottom"), AmbilightState.H_SEGMENTS);
            left = legacyColors(o.getJSONArray("left"), AmbilightState.V_SEGMENTS);
            right = legacyColors(o.getJSONArray("right"), AmbilightState.V_SEGMENTS);
        } else return;
        deliver(top,bottom,left,right,(float)o.optDouble("fps",0),o.optInt("w",0),o.optInt("h",0));
    }

    private void deliver(int[] top,int[] bottom,int[] left,int[] right,float fps,int width,int height) {
        prevTop = adaptiveSmooth(top, prevTop, smoothing);
        prevBottom = adaptiveSmooth(bottom, prevBottom, smoothing);
        prevLeft = adaptiveSmooth(left, prevLeft, smoothing);
        prevRight = adaptiveSmooth(right, prevRight, smoothing);
        AmbilightState state = new AmbilightState(prevTop, prevBottom, prevLeft, prevRight,
                fps, width, height, 1f);
        if (listener != null) listener.onState(state);
    }

    private static int[] packedColors(JSONArray a,int required) {
        int[] out=new int[required]; int last=Math.max(0,a.length()-1);
        for(int i=0;i<required;i++){int n=a.optInt(Math.min(i,last),0)&0x00ffffff;out[i]=0xff000000|n;}
        return out;
    }

    private static int[] legacyColors(JSONArray a,int required) throws Exception {
        int[] out=new int[required];
        for(int i=0;i<required;i++){
            JSONArray sample=a.getJSONArray(Math.min(i,a.length()-1));
            out[i]=Color.rgb(clamp(sample.optInt(0)),clamp(sample.optInt(1)),clamp(sample.optInt(2)));
        }
        return out;
    }

    /**
     * Noise-aware smoothing. Tiny changes can use the configured smoothing, while medium/large
     * changes progressively bypass the old value. This avoids the classic EMA one-frame lag.
     */
    private static int[] adaptiveSmooth(int[] current,int[] previous,float maxAmount){
        if(previous==null||previous.length!=current.length||maxAmount<=0f)return current.clone();
        int[] out=new int[current.length];
        for(int i=0;i<current.length;i++){
            int a=previous[i],b=current[i];
            int dr=Math.abs(((a>>16)&255)-((b>>16)&255));
            int dg=Math.abs(((a>>8)&255)-((b>>8)&255));
            int db=Math.abs((a&255)-(b&255));
            float delta=(dr+dg+db)/(3f*255f);
            float motion=clamp01((delta-0.012f)/0.18f);
            // Accelerate the release of smoothing as soon as real motion appears.
            motion=(float)Math.sqrt(motion);
            float old=maxAmount*(1f-motion);
            if(delta>0.28f) old=0f;
            float fresh=1f-old;
            int r=Math.round(((a>>16)&255)*old+((b>>16)&255)*fresh);
            int g=Math.round(((a>>8)&255)*old+((b>>8)&255)*fresh);
            int bl=Math.round((a&255)*old+(b&255)*fresh);
            out[i]=0xff000000|(r<<16)|(g<<8)|bl;
        }
        return out;
    }

    private void notifyStatus(String value,boolean connected){if(listener!=null)listener.onStatus(value,connected);}
    private synchronized void stopSocket(){Socket s=socket;socket=null;if(s!=null)try{s.close();}catch(IOException ignored){}}

    private static byte[] readExactly(InputStream in,int length)throws IOException{
        byte[] out=new byte[length];int offset=0;while(offset<length){int n=in.read(out,offset,length-offset);if(n<0)throw new IOException("Unexpected EOF");offset+=n;}return out;
    }
    private static void writeMaskedControl(OutputStream out,int opcode,byte[] payload)throws IOException{
        byte[] mask=new byte[4];new SecureRandom().nextBytes(mask);out.write(0x80|(opcode&15));out.write(0x80|(payload.length&127));out.write(mask);for(int i=0;i<payload.length;i++)out.write(payload[i]^mask[i&3]);out.flush();
    }
    private static String readLine(InputStream in)throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();int c;boolean cr=false;while((c=in.read())>=0){if(cr&&c=='\n')break;if(cr){b.write('\r');cr=false;}if(c=='\r')cr=true;else b.write(c);if(b.size()>16384)break;}if(c<0&&b.size()==0)return null;return new String(b.toByteArray(),StandardCharsets.UTF_8);
    }
    private static int clamp(int v){return Math.max(0,Math.min(255,v));}
    private static float clamp01(float v){return Math.max(0f,Math.min(1f,v));}

    private static final class Endpoint{
        final String host;final int port;Endpoint(String host,int port){this.host=host;this.port=port;}
        static Endpoint parse(String raw){String s=raw==null?"":raw.trim();s=s.replaceFirst("^wss?://","").replaceFirst("^https?://","");int slash=s.indexOf('/');if(slash>=0)s=s.substring(0,slash);String host=s;int port=8080;int colon=s.lastIndexOf(':');if(colon>0&&colon<s.length()-1){try{port=Integer.parseInt(s.substring(colon+1));host=s.substring(0,colon);}catch(Exception ignored){}}if(host.isEmpty())host="127.0.0.1";return new Endpoint(host,port);}
    }
}
