package uk.mikewall.crosscam;

import android.net.Network;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Finds out how the camera talks: scans every TCP port, then pokes each open port
 * with an HTTP and an RTSP request and logs what comes back.
 */
public class CameraScanner implements Runnable {

    private final Network net;
    private final String host;
    private final PtpIpSession.Logger log;

    public CameraScanner(Network net, String host, PtpIpSession.Logger log) {
        this.net = net;
        this.host = host;
        this.log = log;
    }

    @Override
    public void run() {
        log.log("Scanning all TCP ports on " + host + " (about 30s)...");
        List<Integer> open = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(128);
        for (int port = 1; port <= 65535; port++) {
            final int p = port;
            pool.execute(() -> {
                try (Socket s = net.getSocketFactory().createSocket()) {
                    s.connect(new InetSocketAddress(host, p), 700);
                    open.add(p);
                } catch (Exception ignored) { }
            });
        }
        pool.shutdown();
        try { pool.awaitTermination(5, TimeUnit.MINUTES); } catch (InterruptedException ignored) { }

        List<Integer> ports = new ArrayList<>(open);
        Collections.sort(ports);
        log.log("Open TCP ports: " + (ports.isEmpty() ? "NONE" : ports.toString()));

        for (int p : ports) probe(p);
        probeUdp();
        log.log("Scan finished. Tap 'Copy log' and paste it to Claude.");
    }

    private void probe(int port) {
        // 1. Does it talk first (banner)?
        String banner = exchange(port, null);
        if (!banner.isEmpty()) log.log("[" + port + "] banner: " + banner);

        // 2. HTTP
        String http = exchange(port, "GET / HTTP/1.0\r\nHost: " + host + "\r\n\r\n");
        log.log("[" + port + "] HTTP: " + (http.isEmpty() ? "(no reply)" : http));

        // 3. RTSP
        String rtsp = exchange(port, "OPTIONS rtsp://" + host + ":" + port + "/ RTSP/1.0\r\nCSeq: 1\r\n\r\n");
        log.log("[" + port + "] RTSP: " + (rtsp.isEmpty() ? "(no reply)" : rtsp));
    }

    private static final String[] RTSP_PATHS = buildPaths();

    private static String[] buildPaths() {
        java.util.LinkedHashSet<String> p = new java.util.LinkedHashSet<>();
        String[] base = {
                "mjpg", "", "live", "livestream", "live/ch0", "live/ch1", "liveview", "preview", "stream", "stream1", "stream2",
                "ch0", "ch1", "ch0_0.h264", "11", "12", "1", "0", "2", "av0_0", "av0_1", "live.sdp", "live1.sdp",
                "H264", "h264", "MJPG", "Mjpg", "mjpeg", "MJPEG", "xxx.mov", "video", "video1", "video0", "main", "sub",
                "media/stream1", "cam/realmonitor?channel=1&subtype=0", "unicast", "test", "camera", "jpeg", "jpg",
                "MJPG?W=640&H=360&Q=50&BR=3000000", "H264?W=640&H=360&BR=3000000", "PSIA/streaming/channels/1",
                "onvif1", "onvif2", "videoMain", "videoSub", "media/live/1/1", "tcp/av0_0",
                // HiSilicon / Linux-based action cams
                "livestream/0", "livestream/1", "livestream/2", "livestream/11", "livestream/12",
                "livestream/main", "livestream/sub", "live/0", "live/1", "live/main", "live/sub", "live/av0",
                "live/stream", "live/mjpeg", "live/mjpg", "live/h264", "liveRTSP/av4", "liveRTSP/v1",
                "stream0", "stream/0", "stream/1", "streaming/channels/1", "streaming/channels/101",
                "Streaming/Channels/1", "Streaming/Channels/101", "h264/ch1/main/av_stream",
                "ch01", "ch01.264", "ch1/main", "ch1/sub", "chn0", "chn1", "channel1", "channel=1",
                "mpeg4", "MPEG4", "mpeg4/media.amp", "axis-media/media.amp", "media.amp", "media", "media/1",
                "VideoInput/1/h264/1", "img/media.sav", "play1.sdp", "stream.sdp", "test.sdp", "h264.sdp",
                "mjpeg.sdp", "video.sdp", "cam1", "cam0", "cam", "cam1/h264", "cam1/mjpeg", "rtsp", "rtsp_live0",
                "live_mpeg4.sdp", "live_h264", "h264_stream", "mjpeg_stream", "sport", "sportdv", "dv",
                "car", "dvr", "record", "rec", "now", "realtime", "real", "rt", "preview/0", "preview/1",
                "previewStream", "PreviewStream", "LiveStream", "Live", "LIVE", "STREAM", "Stream",
                "front", "rear", "tx", "wifi", "wificam", "app", "v1", "vs1", "av1", "av0", "0.sdp", "1.sdp",
                "11.sdp", "user=admin_password=_channel=1_stream=0.sdp", "videostream", "mainstream", "substream"
        };
        Collections.addAll(p, base);
        return p.toArray(new String[0]);
    }

    /** One RTSP request; reads only up to the end of the reply (fast). */
    private String rtspRequest(int port, String request) {
        try (Socket s = net.getSocketFactory().createSocket()) {
            s.connect(new InetSocketAddress(host, port), 1500);
            s.setSoTimeout(1500);
            OutputStream o = s.getOutputStream();
            o.write(request.getBytes(StandardCharsets.US_ASCII));
            o.flush();
            InputStream in = s.getInputStream();
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            int contentLen = -1, headerEnd = -1;
            byte[] b = new byte[1024];
            while (true) {
                int n;
                try { n = in.read(b); } catch (Exception e) { break; }
                if (n <= 0) break;
                buf.write(b, 0, n);
                String sofar = buf.toString("ISO-8859-1");
                if (headerEnd < 0) {
                    headerEnd = sofar.indexOf("\r\n\r\n");
                    if (headerEnd >= 0) {
                        java.util.regex.Matcher m = java.util.regex.Pattern
                                .compile("(?i)content-length:\\s*(\\d+)").matcher(sofar.substring(0, headerEnd));
                        contentLen = m.find() ? Integer.parseInt(m.group(1)) : 0;
                    }
                }
                if (headerEnd >= 0 && buf.size() >= headerEnd + 4 + contentLen) break;
                if (buf.size() > 16000) break;
            }
            byte[] all = buf.toByteArray();
            return printable(all, all.length);
        } catch (Exception e) {
            return "";
        }
    }

    /** Asks the RTSP server about each candidate path. Returns paths that answered 200 with an SDP. */
    public List<String> findRtspPaths(int port) {
        log.log("Asking RTSP server on port " + port + " about " + RTSP_PATHS.length + " stream names...");
        List<String> found = new ArrayList<>();
        int cseq = 2, notFound = 0;
        boolean shownHeaders = false;
        for (String path : RTSP_PATHS) {
            String url = "rtsp://" + host + ":" + port + "/" + path;
            String reply = rtspRequest(port, "DESCRIBE " + url + " RTSP/1.0\r\nCSeq: " + (cseq++) +
                    "\r\nAccept: application/sdp\r\nUser-Agent: LibVLC/3.0.20 (LIVE555 Streaming Media v2016.11.28)\r\n\r\n");
            if (!shownHeaders && !reply.isEmpty()) {
                log.log("Full reply to /" + path + ": " + reply);
                shownHeaders = true;
            }
            String status = reply.contains("|") ? reply.substring(0, reply.indexOf('|')).trim() : reply;
            if (status.isEmpty()) status = "(no reply)";
            if (status.contains(" 404")) { notFound++; continue; }
            if (status.contains(" 200")) {
                found.add(url);
                log.log("FOUND /" + path + " -> " + status);
                // Show the useful SDP lines (media type and codec).
                for (String part : reply.split("\\|")) {
                    String t = part.trim();
                    if (t.startsWith("m=") || t.startsWith("a=rtpmap") || t.startsWith("a=control")
                            || t.startsWith("s=") || t.startsWith("a=framesize") || t.startsWith("a=x-dimensions")) {
                        log.log("     " + t);
                    }
                }
            } else {
                log.log("/" + path + " -> " + status);
            }
        }
        log.log(notFound + " names answered 404 (not shown).");
        log.log(found.isEmpty() ? "No stream name matched." : "Working stream(s): " + found);
        return found;
    }

    private static final String[] HTTP_PATHS = {
            "/", "/index.html", "/live", "/stream", "/mjpeg", "/video", "/videostream.cgi", "/snapshot.jpg",
            "/?custom=1&cmd=3014", "/DCIM/", "/cgi-bin/", "/setting", "/status"
    };

    /** Lists what the web server on the given port answers for a few common camera paths. */
    public void probeHttpPaths(int port) {
        log.log("Asking web server on port " + port + " about common paths...");
        for (String p : HTTP_PATHS) {
            String reply = exchange(port, "GET " + p + " HTTP/1.1\r\nHost: " + host + ":" + port +
                    "\r\nUser-Agent: CrossCam\r\nConnection: close\r\n\r\n", 1500);
            String shortReply = reply.length() > 160 ? reply.substring(0, 160) + "..." : reply;
            log.log("GET " + p + " -> " + (shortReply.isEmpty() ? "(no reply)" : shortReply));
        }
    }

    /** Common UDP discovery/control ports used by cheap Wi-Fi cameras. */
    private void probeUdp() {
        int[] udpPorts = {8080, 8000, 8081, 7878, 7099, 6666, 5000, 3333, 2000, 20000, 40611, 8888, 9999};
        for (int p : udpPorts) {
            try (DatagramSocket ds = new DatagramSocket()) {
                net.bindSocket(ds);
                ds.setSoTimeout(500);
                byte[] msg = "hello".getBytes(StandardCharsets.US_ASCII);
                ds.send(new DatagramPacket(msg, msg.length, InetAddress.getByName(host), p));
                byte[] buf = new byte[512];
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                ds.receive(in);
                log.log("[UDP " + p + "] replied: " + printable(buf, in.getLength()));
            } catch (Exception ignored) { }
        }
    }

    private String exchange(int port, String request) {
        return exchange(port, request, 1500);
    }

    private String exchange(int port, String request, int timeoutMs) {
        try (Socket s = net.getSocketFactory().createSocket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            if (request != null) {
                OutputStream o = s.getOutputStream();
                o.write(request.getBytes(StandardCharsets.US_ASCII));
                o.flush();
            }
            InputStream in = s.getInputStream();
            byte[] buf = new byte[1200];
            int total = 0;
            try {
                int n;
                while (total < buf.length && (n = in.read(buf, total, buf.length - total)) > 0) total += n;
            } catch (Exception ignored) { }
            return printable(buf, total);
        } catch (Exception e) {
            return "";
        }
    }

    private static String printable(byte[] b, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            int c = b[i] & 0xFF;
            if (c == '\r') continue;
            if (c == '\n') sb.append(" | ");
            else if (c >= 32 && c < 127) sb.append((char) c);
            else sb.append(String.format("\\x%02x", c));
        }
        return sb.toString().trim();
    }
}
