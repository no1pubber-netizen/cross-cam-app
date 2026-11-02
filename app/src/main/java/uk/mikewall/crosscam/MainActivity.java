package uk.mikewall.crosscam;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String CAMERA_IP = "192.168.1.1";
    private static final int PTP_PORT = 15740;
    private static final String DEFAULT_URL = "rtsp://192.168.1.1/mjpg";

    private VLCVideoLayout videoLayout;
    private android.widget.ImageView frameView;
    private JieliSession jieli;
    private volatile boolean decoding;
    private CheckBox h264Box;
    private LinearLayout overlay;
    private EditText urlBox;
    private CheckBox tcpBox, skipLoginBox;
    private TextView logView;

    private LibVLC libVlc;
    private MediaPlayer player;
    private PtpIpSession session;
    private Network wifi;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("crosscam", MODE_PRIVATE);
        buildUi();
        log("Ready. Connect your phone to the camera's Wi-Fi (ActionCam), then tap Connect.");
    }

    // ---------------- UI ----------------

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        videoLayout = new VLCVideoLayout(this);
        root.addView(videoLayout, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // JPEG frames from the JieLi UDP stream are drawn here, on top of the VLC view.
        frameView = new android.widget.ImageView(this);
        frameView.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        root.addView(frameView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setBackgroundColor(0xAA000000);
        int pad = dp(8);
        overlay.setPadding(pad, pad, pad, pad);

        urlBox = new EditText(this);
        urlBox.setSingleLine(true);
        urlBox.setTextColor(Color.WHITE);
        urlBox.setTextSize(12);
        urlBox.setText(prefs.getString("url2", DEFAULT_URL));
        overlay.addView(urlBox);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        tcpBox = checkBox("RTSP over TCP", prefs.getBoolean("tcp", false));
        skipLoginBox = checkBox("Skip login", true);
        row1.addView(tcpBox);
        row1.addView(skipLoginBox);
        h264Box = checkBox("H.264", false);
        row1.addView(h264Box);
        overlay.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(button("Connect", v -> connectJieli()));
        row2.addView(button("Play URL", v -> connect()));
        row2.addView(button("Stop", v -> stopAll()));
        overlay.addView(row2);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.addView(button("Find stream", v -> findStream()));
        row3.addView(button("Scan ports", v -> scan()));
        row3.addView(button("Copy log", v -> copyLog()));
        row3.addView(button("Clear", v -> logView.setText("")));
        sizeButton = button(sizeLabel(), v -> {
            int q = (prefs.getInt("quality", 1) + 1) % QUALITY_NAMES.length;
            prefs.edit().putInt("quality", q).apply();
            sizeButton.setText(sizeLabel());
        });
        row3.addView(sizeButton);
        overlay.addView(row3);

        logView = new TextView(this);
        logView.setTextColor(0xFF9EFF9E);
        logView.setTextSize(11);
        logView.setMaxLines(22);
        logView.setVerticalScrollBarEnabled(true);
        logView.setMovementMethod(new ScrollingMovementMethod());
        overlay.addView(logView);

        FrameLayout.LayoutParams olp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        root.addView(overlay, olp);

        // Leave room on the right of the control panel for the menu button
        overlay.setPadding(overlay.getPaddingLeft(), overlay.getPaddingTop(), dp(68), overlay.getPaddingBottom());

        // ☰ Menu button (top right): shows/hides the controls and log
        menuButton = new TextView(this);
        menuButton.setText("☰");
        menuButton.setTextColor(Color.WHITE);
        menuButton.setTextSize(24);
        menuButton.setGravity(Gravity.CENTER);
        menuButton.setBackground(oval(0x88000000, Color.WHITE, 2));
        menuButton.setOnClickListener(v -> setOverlayVisible(overlay.getVisibility() != View.VISIBLE));
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP | Gravity.END);
        mlp.setMargins(0, dp(12), dp(12), 0);
        root.addView(menuButton, mlp);

        // Big record button (bottom centre), camera-app style, with a timer above it
        LinearLayout recBox = new LinearLayout(this);
        recBox.setOrientation(LinearLayout.VERTICAL);
        recBox.setGravity(Gravity.CENTER_HORIZONTAL);

        recTime = new TextView(this);
        recTime.setTextColor(Color.WHITE);
        recTime.setTextSize(16);
        recTime.setPadding(dp(12), dp(4), dp(12), dp(4));
        android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
        pill.setColor(0xFFD32F2F);
        pill.setCornerRadius(dp(14));
        recTime.setBackground(pill);
        recTime.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = dp(8);
        recBox.addView(recTime, tlp);

        FrameLayout ring = new FrameLayout(this);
        ring.setBackground(oval(0x33000000, Color.WHITE, 4));
        recDot = new View(this);
        setRecDot(false);
        ring.addView(recDot, new FrameLayout.LayoutParams(dp(62), dp(62), Gravity.CENTER));
        ring.setOnClickListener(v -> toggleRecording());
        recBox.addView(ring, new LinearLayout.LayoutParams(dp(84), dp(84)));

        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        rlp.bottomMargin = dp(36);
        root.addView(recBox, rlp);

        root.setKeepScreenOn(true);
        setContentView(root);
    }

    private void setOverlayVisible(boolean visible) {
        overlay.setVisibility(visible ? View.VISIBLE : View.GONE);
        View decor = getWindow().getDecorView();
        if (visible) {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private CheckBox checkBox(String text, boolean checked) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        c.setTextColor(Color.WHITE);
        c.setChecked(checked);
        return c;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void log(String msg) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.UK).format(new Date()) + "  " + msg + "\n";
        runOnUiThread(() -> {
            logView.append(line);
            CharSequence t = logView.getText();
            if (t.length() > 6000) logView.setText(t.subSequence(t.length() - 4000, t.length()));
            if (logView.getLayout() != null) {
                int scroll = logView.getLayout().getLineTop(logView.getLineCount()) - logView.getHeight();
                logView.scrollTo(0, Math.max(scroll, 0));
            }
        });
    }

    // ---------------- Connection ----------------

    /** JieLi chip cameras: CTP control on TCP 3333, video pushed to the phone over UDP. */
    private void connectJieli() {
        stopAll();
        autoHidden = false;
        final boolean h264 = h264Box.isChecked();
        new Thread(() -> {
            wifi = findWifi();
            if (wifi == null) {
                log("No Wi-Fi network found. Join the camera's Wi-Fi first.");
                return;
            }
            getSystemService(ConnectivityManager.class).bindProcessToNetwork(wifi);
            log("Using Wi-Fi network for camera traffic");
            // VLC listens for RTP video on UDP 6666 (one of the two ways JieLi cameras send it).
            runOnUiThread(() -> playRtp6666(h264));
            jieli = new JieliSession(wifi, CAMERA_IP, h264 ? 1 : 0, 0, 0, 0, !h264,
                    new JieliSession.Listener() {
                        @Override public void log(String msg) { MainActivity.this.log(msg); }
                        @Override public void onJpeg(byte[] jpeg, int len) { showJpeg(jpeg, len); }
                    });
            jieli.start();
            // Progress report after a few seconds
            try { Thread.sleep(6000); } catch (InterruptedException ignored) { }
            JieliSession j = jieli;
            if (j != null) {
                log("After 6s: TCP 2229 bytes " + j.tcpBytes + ", pictures " + j.tcpFrames
                        + "; UDP 2224 packets " + j.udpPackets + ", pictures " + j.udpFrames
                        + (vlcShowing ? "; RTP 6666 video SHOWING" : "; nothing on RTP 6666 yet"));
            }
        }, "jieli-connect").start();
    }

    private volatile boolean vlcShowing;
    private volatile boolean autoHidden;
    private Recorder recorder;
    private Button sizeButton;
    private static final String[] QUALITY_NAMES = {"High", "Medium", "Small"};
    private static final int[] QUALITY_BITRATES = {6_000_000, 3_000_000, 1_500_000};
    private static final int[] QUALITY_MB_PER_MIN = {45, 22, 11};

    private String sizeLabel() {
        int q = prefs.getInt("quality", 1);
        return "Size: " + QUALITY_NAMES[q] + " (~" + QUALITY_MB_PER_MIN[q] + "MB/min)";
    }
    private TextView menuButton, recTime;
    private View recDot;

    /** Red circle when idle; smaller rounded red square while recording. */
    private void setRecDot(boolean recording) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0xFFE53935);
        if (recording) {
            g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            g.setCornerRadius(dp(6));
        } else {
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        }
        recDot.setBackground(g);
        int size = dp(recording ? 30 : 62);
        ViewGroup.LayoutParams lp = recDot.getLayoutParams();
        if (lp != null) { lp.width = size; lp.height = size; recDot.setLayoutParams(lp); }
    }

    private android.graphics.drawable.GradientDrawable oval(int fill, int stroke, int strokeDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(fill);
        g.setStroke(dp(strokeDp), stroke);
        return g;
    }
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable recTick = new Runnable() {
        @Override public void run() {
            if (recorder != null && recorder.isActive()) {
                long s = recorder.elapsedMs() / 1000;
                recTime.setText(String.format(Locale.UK, "● %d:%02d", s / 60, s % 60));
                ui.postDelayed(this, 500);
            }
        }
    };

    private void toggleRecording() {
        if (recorder == null) recorder = new Recorder(this, this::log);
        if (!recorder.isActive()) {
            if (jieli == null) {
                android.widget.Toast.makeText(this, "Tap Connect first", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            recorder.bitrate = QUALITY_BITRATES[prefs.getInt("quality", 1)];
            recorder.start();
            setRecDot(true);
            recTime.setText("● 0:00");
            recTime.setVisibility(View.VISIBLE);
            ui.post(recTick);
            log("Recording started");
        } else {
            stopRecording();
        }
    }

    private void stopRecording() {
        if (recorder == null || !recorder.isActive()) return;
        ui.removeCallbacks(recTick);
        setRecDot(false);
        recTime.setVisibility(View.INVISIBLE);
        final Recorder r = recorder;
        new Thread(() -> {
            String saved = r.stop();
            runOnUiThread(() -> android.widget.Toast.makeText(this,
                    saved != null ? "Saved to " + saved : "Nothing saved", android.widget.Toast.LENGTH_LONG).show());
        }, "rec-stop").start();
    }

    private android.graphics.drawable.GradientDrawable roundBg(int color) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(28));
        g.setStroke(dp(2), Color.WHITE);
        return g;
    }

    private void showJpeg(byte[] jpeg, int len) {
        boolean rec = recorder != null && recorder.isActive();
        if (decoding && !rec) return; // not recording: skip frames while the screen is still busy
        final android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, len);
        if (bmp == null) return;
        if (rec) recorder.onFrame(bmp);
        if (decoding) return; // screen still drawing the previous picture
        decoding = true;
        runOnUiThread(() -> {
            frameView.setImageBitmap(bmp);
            decoding = false;
            if (!autoHidden) {
                // First picture: get the controls out of the way. The ☰ button brings them back.
                autoHidden = true;
                setOverlayVisible(false);
                android.widget.Toast.makeText(this, "Live. Tap ☰ for the menu.",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void playRtp6666(boolean h264) {
        try {
            String sdp = "v=0\r\no=- 0 0 IN IP4 " + CAMERA_IP + "\r\ns=CrossCam\r\nc=IN IP4 0.0.0.0\r\nt=0 0\r\n"
                    + (h264 ? "m=video " + JieliSession.RTP_PORT + " RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n"
                            : "m=video " + JieliSession.RTP_PORT + " RTP/AVP 26\r\na=rtpmap:26 JPEG/90000\r\n");
            java.io.File f = new java.io.File(getCacheDir(), "jieli.sdp");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            fo.write(sdp.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            fo.close();
            ArrayList<String> opts = new ArrayList<>();
            opts.add("--network-caching=300");
            opts.add("--no-audio");
            libVlc = new LibVLC(this, opts);
            player = new MediaPlayer(libVlc);
            player.attachViews(videoLayout, null, false, false);
            vlcShowing = false;
            player.setEventListener(ev -> {
                if (ev.type == MediaPlayer.Event.Vout && ev.getVoutCount() > 0) {
                    vlcShowing = true;
                    log("RTP 6666: video showing");
                } else if (ev.type == MediaPlayer.Event.EncounteredError) {
                    log("RTP 6666 player error");
                }
            });
            Media media = new Media(libVlc, Uri.fromFile(f));
            media.addOption(":network-caching=300");
            player.setMedia(media);
            media.release();
            player.play();
        } catch (Exception e) {
            log("RTP player failed: " + e.getMessage());
        }
    }

    private void connect() {
        stopAll();
        String url = urlBox.getText().toString().trim();
        boolean tcp = tcpBox.isChecked();
        boolean skipLogin = skipLoginBox.isChecked();
        prefs.edit().putString("url2", url).putBoolean("tcp", tcp).apply();

        new Thread(() -> {
            wifi = findWifi();
            if (wifi == null) {
                log("No Wi-Fi network found. Join the camera's Wi-Fi first.");
                return;
            }
            // Send all traffic over the camera Wi-Fi even though it has no internet.
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            cm.bindProcessToNetwork(wifi);
            log("Using Wi-Fi network for camera traffic");

            if (!skipLogin) {
                try {
                    log("Logging in to camera (PTP/IP " + CAMERA_IP + ":" + PTP_PORT + ")...");
                    session = new PtpIpSession(guid(), this::log);
                    session.connect(wifi, CAMERA_IP, PTP_PORT);
                    log("Login done. Waiting 1s for camera to start streaming...");
                    Thread.sleep(1000);
                } catch (Exception e) {
                    log("Login failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    log("Trying the stream anyway...");
                    if (session != null) session.close();
                    session = null;
                }
            }
            runOnUiThread(() -> play(url, tcp));
        }, "connect").start();
    }

    private void scan() {
        stopAll();
        new Thread(() -> {
            wifi = findWifi();
            if (wifi == null) {
                log("No Wi-Fi network found. Join the camera's Wi-Fi first.");
                return;
            }
            getSystemService(ConnectivityManager.class).bindProcessToNetwork(wifi);
            new CameraScanner(wifi, CAMERA_IP, this::log).run();
        }, "scan").start();
    }

    private void findStream() {
        stopAll();
        new Thread(() -> {
            wifi = findWifi();
            if (wifi == null) {
                log("No Wi-Fi network found. Join the camera's Wi-Fi first.");
                return;
            }
            getSystemService(ConnectivityManager.class).bindProcessToNetwork(wifi);
            CameraScanner sc = new CameraScanner(wifi, CAMERA_IP, this::log);
            java.util.List<String> found = sc.findRtspPaths(554);
            sc.probeHttpPaths(8080);
            if (!found.isEmpty()) {
                String url = found.get(0);
                log("Loading " + url + " (login skipped)...");
                runOnUiThread(() -> {
                    urlBox.setText(url);
                    skipLoginBox.setChecked(true);
                    prefs.edit().putString("url2", url).apply();
                    play(url, tcpBox.isChecked());
                });
            }
            log("Done. Tap 'Copy log' and paste it to Claude if there's no picture.");
        }, "find-stream").start();
    }

    private void copyLog() {
        android.content.ClipboardManager cb = getSystemService(android.content.ClipboardManager.class);
        cb.setPrimaryClip(android.content.ClipData.newPlainText("CrossCam log", logView.getText()));
        log("Log copied - paste it into the chat with Claude.");
    }

    private Network findWifi() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
        }
        return null;
    }

    private byte[] guid() {
        String hex = prefs.getString("guid", null);
        byte[] g = new byte[16];
        if (hex != null && hex.length() == 32) {
            for (int i = 0; i < 16; i++) g[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            return g;
        }
        new SecureRandom().nextBytes(g);
        StringBuilder sb = new StringBuilder();
        for (byte b : g) sb.append(String.format("%02x", b));
        prefs.edit().putString("guid", sb.toString()).apply();
        return g;
    }

    private void play(String url, boolean tcp) {
        try {
            ArrayList<String> opts = new ArrayList<>();
            opts.add("--network-caching=300");
            opts.add("--no-audio");
            if (tcp) opts.add("--rtsp-tcp");
            libVlc = new LibVLC(this, opts);
            player = new MediaPlayer(libVlc);
            player.attachViews(videoLayout, null, false, false);
            player.setEventListener(ev -> {
                switch (ev.type) {
                    case MediaPlayer.Event.Opening: log("Stream: opening " + url); break;
                    case MediaPlayer.Event.Playing: log("Stream: PLAYING - tap Hide, then screen record"); break;
                    case MediaPlayer.Event.EncounteredError: log("Stream: error. Try ticking 'RTSP over TCP' and Connect again."); break;
                    case MediaPlayer.Event.EndReached: log("Stream: ended"); break;
                    case MediaPlayer.Event.Stopped: log("Stream: stopped"); break;
                    case MediaPlayer.Event.Vout: if (ev.getVoutCount() > 0) log("Stream: video showing"); break;
                    default: break;
                }
            });
            Media media = new Media(libVlc, Uri.parse(url));
            media.setHWDecoderEnabled(true, false);
            media.addOption(":network-caching=300");
            if (tcp) media.addOption(":rtsp-tcp");
            player.setMedia(media);
            media.release();
            player.play();
        } catch (Exception e) {
            log("Player failed: " + e.getMessage());
        }
    }

    private void stopAll() {
        stopRecording();
        if (jieli != null) { jieli.close(); jieli = null; }
        if (frameView != null) runOnUiThread(() -> frameView.setImageDrawable(null));
        if (player != null) {
            try { player.stop(); player.detachViews(); player.release(); } catch (Exception ignored) { }
            player = null;
        }
        if (libVlc != null) {
            try { libVlc.release(); } catch (Exception ignored) { }
            libVlc = null;
        }
        final PtpIpSession s = session;
        session = null;
        if (s != null) new Thread(s::close, "ptp-close").start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopAll();
    }
}
