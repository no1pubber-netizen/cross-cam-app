package uk.mikewall.crosscam;

import android.net.Network;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Minimal PTP/IP client (port 15740) - the "log in" step the original iSmart DV app
 * performs before the camera will serve its RTSP live view.
 */
public class PtpIpSession implements Closeable {

    public interface Logger { void log(String msg); }

    // PTP/IP packet types
    private static final int INIT_COMMAND_REQUEST = 1;
    private static final int INIT_COMMAND_ACK = 2;
    private static final int INIT_EVENT_REQUEST = 3;
    private static final int INIT_EVENT_ACK = 4;
    private static final int INIT_FAIL = 5;
    private static final int OPERATION_REQUEST = 6;
    private static final int OPERATION_RESPONSE = 7;
    private static final int EVENT = 8;
    private static final int START_DATA = 9;
    private static final int DATA = 10;
    private static final int END_DATA = 12;
    private static final int PROBE_REQUEST = 13;
    private static final int PROBE_RESPONSE = 14;

    public static final int OP_GET_DEVICE_INFO = 0x1001;
    public static final int OP_OPEN_SESSION = 0x1002;
    public static final int OP_CLOSE_SESSION = 0x1003;

    private final Logger log;
    private final byte[] guid;
    private Socket cmd, evt;
    private DataInputStream cmdIn, evtIn;
    private OutputStream cmdOut, evtOut;
    private int transactionId = 0;
    private volatile boolean open = false;
    private Thread eventThread, keepAliveThread;

    public PtpIpSession(byte[] guid, Logger log) {
        this.guid = guid;
        this.log = log;
    }

    public void connect(Network network, String host, int port) throws IOException {
        // Command channel
        cmd = makeSocket(network, host, port);
        cmdIn = new DataInputStream(cmd.getInputStream());
        cmdOut = cmd.getOutputStream();

        byte[] name = "CrossCam\0".getBytes(StandardCharsets.UTF_16LE);
        ByteBuffer b = packet(INIT_COMMAND_REQUEST, 16 + name.length + 4);
        b.put(guid).put(name).putInt(0x00010000);
        send(cmdOut, b);
        log.log("Sent Init_Command_Request");

        Packet p = read(cmdIn);
        if (p.type == INIT_FAIL) {
            throw new IOException("Camera refused connection (Init_Fail, reason " + (p.body.length >= 4 ? p.buf().getInt() : -1) + ")");
        }
        if (p.type != INIT_COMMAND_ACK) throw new IOException("Unexpected reply type " + p.type);
        int connNumber = p.buf().getInt();
        String camName = readUtf16(p.body, 4 + 16);
        log.log("Camera accepted: connection #" + connNumber + (camName.isEmpty() ? "" : " (" + camName + ")"));

        // Event channel
        evt = makeSocket(network, host, port);
        evtIn = new DataInputStream(evt.getInputStream());
        evtOut = evt.getOutputStream();
        ByteBuffer e = packet(INIT_EVENT_REQUEST, 4);
        e.putInt(connNumber);
        send(evtOut, e);
        Packet ep = read(evtIn);
        if (ep.type != INIT_EVENT_ACK) throw new IOException("Event channel refused (type " + ep.type + ")");
        log.log("Event channel open");
        evt.setSoTimeout(0);
        open = true;
        startEventThread();

        // Open a session (transaction id 0 per PTP spec), then read device info.
        transactionId = 0;
        int rc = operation(OP_OPEN_SESSION, 1);
        log.log(String.format(Locale.UK, "OpenSession -> 0x%04X%s", rc,
                rc == 0x2001 ? " (OK)" : rc == 0x201E ? " (already open, OK)" : ""));
        rc = operation(OP_GET_DEVICE_INFO);
        log.log(String.format(Locale.UK, "GetDeviceInfo -> 0x%04X", rc));
        startKeepAlive();
    }

    private Socket makeSocket(Network network, String host, int port) throws IOException {
        Socket s = network != null ? network.getSocketFactory().createSocket() : new Socket();
        s.setTcpNoDelay(true);
        s.setSoTimeout(6000);
        s.connect(new InetSocketAddress(host, port), 6000);
        return s;
    }

    /** Runs an operation with no outgoing data; any incoming data is read and discarded. */
    public synchronized int operation(int opcode, int... params) throws IOException {
        int tid = transactionId;
        transactionId++;
        ByteBuffer b = packet(OPERATION_REQUEST, 4 + 2 + 4 + 4 * params.length);
        b.putInt(1).putShort((short) opcode).putInt(tid);
        for (int prm : params) b.putInt(prm);
        send(cmdOut, b);
        while (true) {
            Packet p = read(cmdIn);
            switch (p.type) {
                case OPERATION_RESPONSE:
                    return p.buf().getShort() & 0xFFFF;
                case START_DATA:
                case DATA:
                case END_DATA:
                    break; // discard data phase
                default:
                    log.log("Ignored packet type " + p.type);
            }
        }
    }

    private void startEventThread() {
        eventThread = new Thread(() -> {
            try {
                while (open) {
                    Packet p = read(evtIn);
                    if (p.type == PROBE_REQUEST) {
                        send(evtOut, packet(PROBE_RESPONSE, 0));
                    } else if (p.type == EVENT) {
                        ByteBuffer bb = p.buf();
                        int code = bb.getShort() & 0xFFFF;
                        log.log(String.format(Locale.UK, "Camera event 0x%04X", code));
                    }
                }
            } catch (IOException ex) {
                if (open) log.log("Event channel closed: " + ex.getMessage());
            }
        }, "ptpip-events");
        eventThread.setDaemon(true);
        eventThread.start();
    }

    private void startKeepAlive() {
        keepAliveThread = new Thread(() -> {
            while (open) {
                try {
                    Thread.sleep(8000);
                    if (!open) break;
                    operation(OP_GET_DEVICE_INFO);
                } catch (InterruptedException ie) {
                    break;
                } catch (IOException ex) {
                    if (open) log.log("Keep-alive failed: " + ex.getMessage());
                    break;
                }
            }
        }, "ptpip-keepalive");
        keepAliveThread.setDaemon(true);
        keepAliveThread.start();
    }

    public boolean isOpen() { return open; }

    @Override
    public void close() {
        boolean wasOpen = open;
        open = false;
        if (wasOpen && cmd != null) {
            try { operation(OP_CLOSE_SESSION); } catch (Exception ignored) { }
        }
        if (keepAliveThread != null) keepAliveThread.interrupt();
        try { if (evt != null) evt.close(); } catch (IOException ignored) { }
        try { if (cmd != null) cmd.close(); } catch (IOException ignored) { }
    }

    // ---- packet helpers ----

    private static ByteBuffer packet(int type, int payloadLen) {
        ByteBuffer b = ByteBuffer.allocate(8 + payloadLen).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(8 + payloadLen).putInt(type);
        return b;
    }

    private static synchronized void send(OutputStream out, ByteBuffer b) throws IOException {
        out.write(b.array(), 0, b.position());
        out.flush();
    }

    private static class Packet {
        int type;
        byte[] body;
        ByteBuffer buf() { return ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN); }
    }

    private static Packet read(DataInputStream in) throws IOException {
        byte[] hdr = new byte[8];
        in.readFully(hdr);
        ByteBuffer h = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
        int len = h.getInt();
        int type = h.getInt();
        if (len < 8 || len > 64 * 1024 * 1024) throw new IOException("Bad packet length " + len);
        Packet p = new Packet();
        p.type = type;
        p.body = new byte[len - 8];
        in.readFully(p.body);
        return p;
    }

    private static String readUtf16(byte[] data, int offset) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (int i = offset; i + 1 < data.length; i += 2) {
            if (data[i] == 0 && data[i + 1] == 0) break;
            o.write(data[i]);
            o.write(data[i + 1]);
        }
        return new String(o.toByteArray(), StandardCharsets.UTF_16LE);
    }
}
