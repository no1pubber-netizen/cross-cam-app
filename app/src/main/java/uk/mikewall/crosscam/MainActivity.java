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
        overlay.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(button("Connect", v -> connect()));
        row2.addView(button("Default", v -> urlBox.setText(DEFAULT_URL)));
        row2.addView(button("Stop", v -> stopAll()));
        row2.addView(button("Hide", v -> setOverlayVisible(false)));
        overlay.addView(row2);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.addView(button("Find stream", v -> findStream()));
        row3.addView(button("Scan ports", v -> scan()));
        row3.addView(button("Copy log", v -> copyLog()));
        row3.addView(button("Clear", v -> logView.setText("")));
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

        // Tap anywhere on the video to bring the controls back.
        videoLayout.setOnClickListener(v -> setOverlayVisible(true));
        root.setOnClickListener(v -> setOverlayVisible(true));

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
