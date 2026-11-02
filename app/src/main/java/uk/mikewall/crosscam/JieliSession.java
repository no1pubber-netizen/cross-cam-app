package uk.mikewall.crosscam;

import android.net.Network;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.BitSet;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client for JieLi-chip cameras ("CTP" control protocol on TCP 3333).
 * Protocol as documented by the OpenIPC decoder project (Jieli notes, #187/#196):
 * each message is "CTP:" + u16 LE topic length + topic + u32 LE JSON length + JSON.
 * Session: APP_ACCESS -> camera announces KEEP_ALIVE_INTERVAL / VIDEO_PARAM -> DATE_TIME ->
 * CTP_KEEP_ALIVE periodically -> OPEN_RT_STREAM. Video then arrives either as the camera's
 * own JPEG stream on UDP 2224 (20-byte header per piece) or as RTP to UDP 6666.
 * This is an independent implementation.
 */
public class JieliSession implements Closeable {

    public interface Listener {
        void log(String msg);
        void onJpeg(byte[] jpeg, int len);
    }

    public static final int CTP_PORT = 3333;
    public static final int STREAM_PORT = 2224;
    public static final int RTP_PORT = 6666;

    private final Network net;
    private final String host;
    private final Listener out;
    private final int format, reqW, reqH, reqFps;
    private final boolean listen2224;

    private volatile boolean running;
    private Socket sock;
    private DatagramSocket udp;
    private Thread ctpThread, udpThread;

    private long keepAliveMs = 5000;
    private long lastKeepAlive;
    private boolean streamRequested;
    private long accessSentAt;
    private int vpW = -1, vpH = -1, vpFps = -1;
    private int ctpLogged;
    volatile int udpPackets, udpFrames;
    volatile long tcpBytes;
    volatile int tcpFrames;
    public static final int TCP_STREAM_PORT = 2229;
    private Socket dataSock;
    private Thread tcpThread;
    private boolean dataOpened;
    private volatile boolean dataConnected;

    public JieliSession(Network net, String host, int format, int w, int h, int fps,
                        boolean listen2224, Listener out) {
        this.net = net;
        this.host = host;
        this.format = format;
        this.reqW = w;
        this.reqH = h;
        this.reqFps = fps;
        this.listen2224 = listen2224;
        this.out = out;
    }

    public void start() {
        running = true;
        if (listen2224) startUdp();
        ctpThread = new Thread(this::runCtp, "jieli-ctp");
        ctpThread.start();
    }

    // ---------------- CTP ----------------

    static byte[] encode(String topic, String json) {
        byte[] t = topic.getBytes(StandardCharsets.UTF_8);
        byte[] j = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write('C'); o.write('T'); o.write('P'); o.write(':');
        o.write(t.length & 0xFF); o.write((t.length >> 8) & 0xFF);
        o.write(t, 0, t.length);
        for (int i = 0; i < 4; i++) o.write((j.length >> (8 * i)) & 0xFF);
        o.write(j, 0, j.length);
        return o.toByteArray();
    }

    /** {"op":"PUT","param":{"k":"v",...}} */
    static String put(String... kv) {
        StringBuilder sb = new StringBuilder("{\"op\":\"PUT\"");
        if (kv.length > 0) {
            sb.append(",\"param\":{");
            for (int i = 0; i + 1 < kv.length; i += 2) {
                if (i > 0) sb.append(',');
                sb.append('"').append(kv[i]).append("\":\"").append(kv[i + 1]).append('"');
            }
            sb.append('}');
        }
        return sb.append('}').toString();
    }

    private synchronized void send(String topic, String json) throws IOException {
        OutputStream o = sock.getOutputStream();
        o.write(encode(topic, json));
        o.flush();
        if (!"CTP_KEEP_ALIVE".equals(topic) || ctpLogged < 6) {
            out.log("CTP → " + topic + " " + json);
        }
    }

    private void runCtp() {
        try {
            out.log("JieLi: connecting to " + host + ":" + CTP_PORT + "...");
            sock = net != null ? net.getSocketFactory().createSocket() : new Socket();
            sock.setTcpNoDelay(true);
            sock.connect(new InetSocketAddress(host, CTP_PORT), 5000);
            sock.setSoTimeout(150);
            out.log("JieLi: connected. Sending APP_ACCESS");
            send("APP_ACCESS", put("ver", "25", "type", "0"));
            accessSentAt = System.currentTimeMillis();
            lastKeepAlive = accessSentAt;
            boolean dateSent = false;

            InputStream in = sock.getInputStream();
            ByteArrayOutputStream acc = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (running) {
                int n;
                try {
                    n = in.read(buf);
                    if (n < 0) { out.log("JieLi: camera closed the control connection"); break; }
                    acc.write(buf, 0, n);
                    parse(acc);
                } catch (SocketTimeoutException ignored) { }

                long now = System.currentTimeMillis();
                if (!dateSent && now - accessSentAt > 300) {
                    send("DATE_TIME", put("date",
                            new SimpleDateFormat("yyyyMMddHHmmss", Locale.UK).format(new Date())));
                    dateSent = true;
                }
                if (!dataOpened && (vpW > 0 || now - accessSentAt > 1500)) {
                    dataOpened = true;
                    startTcpStream();
                }
                if (!streamRequested && dateSent && dataOpened && (dataConnected || now - accessSentAt > 6000)) {
                    requestStream();
                }
                if (now - lastKeepAlive > keepAliveMs) {
                    send("CTP_KEEP_ALIVE", put());
                    lastKeepAlive = now;
                }
            }
        } catch (IOException e) {
            if (running) out.log("JieLi: control connection error: " + e.getMessage());
        } finally {
            try { if (sock != null) sock.close(); } catch (IOException ignored) { }
        }
    }

    private void requestStream() throws IOException {
        streamRequested = true;
        int w = reqW > 0 ? reqW : (vpW > 0 ? vpW : 640);
        int h = reqH > 0 ? reqH : (vpH > 0 ? vpH : 480);
        int fps = reqFps > 0 ? reqFps : (vpFps > 0 ? vpFps : 25);
        out.log("JieLi: asking for live video " + w + "x" + h + " @" + fps + " ("
                + (format == 1 ? "H.264" : "JPEG") + ")");
        // Field order as the vendor app sends it
        send("OPEN_RT_STREAM", put("format", String.valueOf(format), "h", String.valueOf(h),
                "w", String.valueOf(w), "fps", String.valueOf(fps)));
    }

    /** Pulls complete CTP messages out of the buffer. */
    private void parse(ByteArrayOutputStream acc) {
        byte[] b = acc.toByteArray();
        int pos = 0;
        while (true) {
            int m = indexOfMagic(b, pos);
            if (m < 0) { pos = Math.max(pos, b.length - 3); break; }
            if (m > pos) out.log("JieLi: skipped " + (m - pos) + " non-CTP bytes");
            if (b.length - m < 6) { pos = m; break; }
            int tl = (b[m + 4] & 0xFF) | (b[m + 5] & 0xFF) << 8;
            if (tl > 256) { pos = m + 4; continue; }
            if (b.length - m < 6 + tl + 4) { pos = m; break; }
            String topic = new String(b, m + 6, tl, StandardCharsets.UTF_8);
            int jp = m + 6 + tl;
            int jl = (b[jp] & 0xFF) | (b[jp + 1] & 0xFF) << 8 | (b[jp + 2] & 0xFF) << 16 | (b[jp + 3] & 0xFF) << 24;
            if (jl < 0 || jl > 256 * 1024) { pos = m + 4; continue; }
            if (b.length - (jp + 4) < jl) { pos = m; break; }
            String json = new String(b, jp + 4, jl, StandardCharsets.UTF_8);
            handle(topic, json);
            pos = jp + 4 + jl;
        }
        acc.reset();
        if (pos < b.length) acc.write(b, pos, b.length - pos);
    }

    private static int indexOfMagic(byte[] b, int from) {
        for (int i = from; i + 4 <= b.length; i++) {
            if (b[i] == 'C' && b[i + 1] == 'T' && b[i + 2] == 'P' && b[i + 3] == ':') return i;
        }
        return -1;
    }

    private void handle(String topic, String json) {
        boolean ka = "CTP_KEEP_ALIVE".equals(topic);
        if (!ka || ctpLogged < 6) {
            ctpLogged++;
            if (ctpLogged <= 80) {
                String j = json.length() > 220 ? json.substring(0, 220) + "…" : json;
                out.log("CTP ← " + topic + " " + j);
            }
        }
        if ("KEEP_ALIVE_INTERVAL".equals(topic)) {
            int t = intParam(json, "timeout");
            if (t > 0) {
                keepAliveMs = Math.max(250, Math.min(t / 4, 7500));
                out.log("JieLi: keep-alive every " + keepAliveMs + " ms");
            }
        } else if ("VIDEO_PARAM".equals(topic)) {
            vpW = intParam(json, "w");
            vpH = intParam(json, "h");
            vpFps = intParam(json, "fps");
        }
    }

    static int intParam(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"?(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    // ---------------- TCP 2229: real-time stream data socket ----------------

    private void startTcpStream() {
        tcpThread = new Thread(() -> {
            try {
                dataSock = net != null ? net.getSocketFactory().createSocket() : new Socket();
                dataSock.setTcpNoDelay(true);
                dataSock.setReceiveBufferSize(1 << 20);
                dataSock.connect(new InetSocketAddress(host, TCP_STREAM_PORT), 4000);
                dataSock.setSoTimeout(500);
                out.log("JieLi: video data connection open (TCP " + TCP_STREAM_PORT + ")");
                dataConnected = true;
            } catch (IOException e) {
                out.log("JieLi: video data connection failed: " + e.getMessage());
                return;
            }
            Reassembler r = new Reassembler();
            byte[] buf = new byte[256 * 1024];
            int have = 0;
            boolean dumped = false;
            int unknownLogged = 0;
            try {
                InputStream in = dataSock.getInputStream();
                while (running) {
                    if (have == buf.length) {
                        // Grow for large frames
                        buf = java.util.Arrays.copyOf(buf, buf.length * 2);
                    }
                    int n;
                    try {
                        n = in.read(buf, have, buf.length - have);
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                    if (n < 0) { out.log("JieLi: camera closed the video connection"); break; }
                    have += n;
                    tcpBytes += n;
                    if (!dumped && have >= 48) {
                        dumped = true;
                        out.log("TCP 2229 first bytes: " + hex(buf, 48));
                    }
                    // Parse as many complete pieces as are in the buffer
                    int pos = 0;
                    while (have - pos >= 20) {
                        int type = buf[pos] & 0x7F;
                        int plen = (buf[pos + 2] & 0xFF) | (buf[pos + 3] & 0xFF) << 8;
                        int size = le32(buf, pos + 8);
                        int at = le32(buf, pos + 12);
                        if (type < 1 || type > 7 || size < 0 || size > (8 << 20)) {
                            if (unknownLogged++ < 3) {
                                out.log("TCP 2229: unexpected header at +" + pos + ": "
                                        + hex(java.util.Arrays.copyOfRange(buf, pos, pos + 20), 20));
                            }
                            pos = resync(buf, pos + 1, have);
                            continue;
                        }
                        // Piece length: the 16-bit field, or the whole frame when it doesn't fit
                        int payload = plen;
                        if (payload == 0 || (size > 0 && at + payload > size)) payload = size - at;
                        if (payload < 0) payload = 0;
                        if (pos + 20 + payload > buf.length) {
                            buf = java.util.Arrays.copyOf(buf, Math.max(buf.length * 2, pos + 20 + payload + 1024));
                        }
                        if (have - pos < 20 + payload) break; // wait for the rest
                        if (type == 2) {
                            int len = r.pieceAt(buf, pos, payload);
                            if (len > 0) {
                                tcpFrames++;
                                if (tcpFrames == 1) out.log("TCP 2229: first complete picture (" + len + " bytes)");
                                out.onJpeg(r.ready, len);
                            }
                        } else if (type == 3 && tcpFrames == 0 && unknownLogged++ < 1) {
                            out.log("TCP 2229: camera is sending H.264 (type 3); tick H.264 off/on and retry");
                        }
                        pos += 20 + payload;
                    }
                    if (pos > 0) {
                        System.arraycopy(buf, pos, buf, 0, have - pos);
                        have -= pos;
                    }
                }
            } catch (IOException e) {
                if (running) out.log("JieLi: video connection error: " + e.getMessage());
            } finally {
                try { dataSock.close(); } catch (IOException ignored) { }
            }
        }, "jieli-tcp");
        tcpThread.start();
    }

    /** Finds the next plausible 20-byte header (type 1..3 and sane sizes). */
    private static int resync(byte[] b, int from, int have) {
        for (int i = from; i + 20 <= have; i++) {
            int t = b[i] & 0x7F;
            if (t >= 1 && t <= 3) {
                int size = le32(b, i + 8), at = le32(b, i + 12);
                if (size > 0 && size < (8 << 20) && at >= 0 && at < size) return i;
            }
        }
        return Math.max(from, have - 19);
    }

    // ---------------- UDP 2224: camera's own JPEG stream ----------------

    private void startUdp() {
        try {
            udp = new DatagramSocket(null);
            udp.setReuseAddress(true);
            if (net != null) net.bindSocket(udp);
            udp.bind(new InetSocketAddress(STREAM_PORT));
            udp.setReceiveBufferSize(1 << 20);
            udp.setSoTimeout(500);
        } catch (IOException e) {
            out.log("JieLi: cannot listen on UDP " + STREAM_PORT + ": " + e.getMessage());
            return;
        }
        udpThread = new Thread(() -> {
            byte[] b = new byte[65536];
            DatagramPacket p = new DatagramPacket(b, b.length);
            Reassembler r = new Reassembler();
            while (running) {
                try {
                    p.setLength(b.length);
                    udp.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                } catch (IOException e) {
                    break;
                }
                int n = ++udpPackets;
                if (n <= 3) {
                    out.log("UDP 2224 packet " + n + " from " + p.getAddress().getHostAddress()
                            + ", " + p.getLength() + " bytes: " + hex(b, Math.min(p.getLength(), 28)));
                }
                int len = r.feed(b, p.getLength());
                if (len > 0) {
                    udpFrames++;
                    if (udpFrames == 1) out.log("UDP 2224: first complete picture (" + len + " bytes)");
                    out.onJpeg(r.ready, len);
                }
            }
        }, "jieli-udp");
        udpThread.start();
    }

    static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(String.format("%02x", b[i])).append(i % 4 == 3 ? " " : "");
        return sb.toString().trim();
    }

    static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16 | (b[o + 3] & 0xFF) << 24;
    }

    /**
     * Pieces behind a 20-byte header: [0] type (2 = JPEG, bit 7 = last piece), [2..3] piece
     * length, [4..7] counter, [8..11] whole frame length, [12..15] offset of this piece.
     * A datagram can carry several pieces back to back.
     */
    static final class Reassembler {
        byte[] frame = new byte[256 * 1024];
        byte[] ready = new byte[256 * 1024];
        int total = -1, filled;
        final BitSet got = new BitSet();

        int feed(byte[] b, int len) {
            int done = 0, pos = 0;
            while (len - pos > 20) {
                int plen = (b[pos + 2] & 0xFF) | (b[pos + 3] & 0xFF) << 8;
                if (plen == 0 || plen > len - pos - 20) plen = len - pos - 20;
                int r = piece(b, pos, plen);
                if (r > 0) done = r;
                pos += 20 + plen;
            }
            return done;
        }

        int pieceAt(byte[] b, int off, int plen) { return piece(b, off, plen); }

        private int piece(byte[] b, int off, int plen) {
            if ((b[off] & 0x7F) != 2) return 0;
            int size = le32(b, off + 8);
            int at = le32(b, off + 12);
            if (size < 4 || size > (4 << 20) || at < 0 || at >= size) return 0;
            if (size != total) {
                total = size;
                filled = 0;
                got.clear();
                if (frame.length < size) frame = new byte[size];
            }
            int part = Math.min(plen, size - at);
            int already = got.get(at, at + part).cardinality();
            System.arraycopy(b, off + 20, frame, at, part);
            got.set(at, at + part);
            filled += part - already;
            if (filled < total) return 0;
            if (ready.length < total) ready = new byte[total];
            System.arraycopy(frame, 0, ready, 0, total);
            int n = total;
            total = -1;
            filled = 0;
            got.clear();
            return n;
        }
    }

    @Override
    public void close() {
        if (!running) return;
        running = false;
        new Thread(() -> {
            try {
                if (sock != null && sock.isConnected() && !sock.isClosed()) {
                    send("CLOSE_RT_STREAM", put("status", "1"));
                }
            } catch (Exception ignored) { }
            try { if (sock != null) sock.close(); } catch (IOException ignored) { }
        }, "jieli-close").start();
        if (udp != null) udp.close();
        try { if (dataSock != null) dataSock.close(); } catch (IOException ignored) { }
    }
}
