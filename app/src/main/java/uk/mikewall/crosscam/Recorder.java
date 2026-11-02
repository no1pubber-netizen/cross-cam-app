package uk.mikewall.crosscam;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Records the live pictures to an H.264 MP4 in Movies/CrossCam.
 * Each decoded frame is drawn onto the encoder's input surface; a drain thread feeds the muxer.
 */
public class Recorder {

    public interface Logger { void log(String msg); }

    private final Context ctx;
    private final Logger log;
    private final Object lock = new Object();

    private MediaCodec encoder;
    private Surface input;
    private MediaMuxer muxer;
    private int track = -1;
    private boolean muxerStarted;
    private Thread drainThread;
    private ParcelFileDescriptor pfd;
    private Uri uri;
    private File file;
    private int width, height;
    private volatile boolean active;
    private long startedAt;
    private int framesIn;
    public volatile String savedName;

    public Recorder(Context ctx, Logger log) {
        this.ctx = ctx;
        this.log = log;
    }

    public boolean isActive() { return active; }

    public long elapsedMs() { return active ? System.currentTimeMillis() - startedAt : 0; }

    /** Arms the recorder; the encoder is created on the first frame (to learn its size). */
    public void start() {
        synchronized (lock) {
            if (active) return;
            active = true;
            startedAt = System.currentTimeMillis();
            framesIn = 0;
            savedName = null;
        }
    }

    /** Called for every decoded frame (any thread). */
    public void onFrame(Bitmap bmp) {
        synchronized (lock) {
            if (!active) return;
            try {
                if (encoder == null) open(bmp.getWidth(), bmp.getHeight());
                Canvas c = input.lockHardwareCanvas();
                try {
                    c.drawBitmap(bmp, null, new Rect(0, 0, width, height), null);
                } finally {
                    input.unlockCanvasAndPost(c);
                }
                framesIn++;
            } catch (Exception e) {
                log.log("Recording error: " + e.getMessage());
                stopLocked();
            }
        }
    }

    private void open(int w, int h) throws IOException {
        // Encoders want even sizes
        width = w & ~1;
        height = h & ~1;
        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, width * height >= 1280 * 720 ? 8_000_000 : 4_000_000);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, 25);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        input = encoder.createInputSurface();
        encoder.start();

        String name = "CrossCam_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.UK).format(new Date()) + ".mp4";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CrossCam");
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
            ContentResolver cr = ctx.getContentResolver();
            uri = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (uri == null) throw new IOException("could not create the video file");
            pfd = cr.openFileDescriptor(uri, "rw");
            muxer = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            savedName = "Movies/CrossCam/" + name;
        } else {
            File dir = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "CrossCam");
            dir.mkdirs();
            file = new File(dir, name);
            muxer = new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            savedName = file.getAbsolutePath();
        }
        track = -1;
        muxerStarted = false;
        final MediaCodec enc = encoder;
        final MediaMuxer mux = muxer;
        drainThread = new Thread(() -> drain(enc, mux), "rec-drain");
        drainThread.start();
        log.log("Recording " + width + "x" + height + " to " + savedName);
    }

    private void drain(MediaCodec enc, MediaMuxer mux) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (true) {
                int idx = enc.dequeueOutputBuffer(info, 10_000);
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = mux.addTrack(enc.getOutputFormat());
                    mux.start();
                    muxerStarted = true;
                    continue;
                }
                if (idx < 0) continue;
                ByteBuffer buf = enc.getOutputBuffer(idx);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0 && muxerStarted && buf != null) {
                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);
                    mux.writeSampleData(track, buf, info);
                }
                enc.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        } catch (Exception e) {
            log.log("Recording writer error: " + e.getMessage());
        }
    }

    /** Stops and finalises the file. Returns where it was saved, or null. */
    public String stop() {
        synchronized (lock) {
            return stopLocked();
        }
    }

    private String stopLocked() {
        if (!active) return null;
        active = false;
        String saved = savedName;
        if (encoder == null) {
            log.log("Recording stopped before any picture arrived; nothing saved.");
            return null;
        }
        try { encoder.signalEndOfInputStream(); } catch (Exception ignored) { }
        try { if (drainThread != null) drainThread.join(3000); } catch (InterruptedException ignored) { }
        try { encoder.stop(); } catch (Exception ignored) { }
        encoder.release();
        encoder = null;
        if (input != null) { input.release(); input = null; }
        boolean ok = muxerStarted;
        try { if (muxerStarted) muxer.stop(); } catch (Exception e) { ok = false; }
        try { muxer.release(); } catch (Exception ignored) { }
        muxer = null;
        try { if (pfd != null) pfd.close(); } catch (IOException ignored) { }
        pfd = null;
        if (uri != null) {
            if (ok) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Video.Media.IS_PENDING, 0);
                ctx.getContentResolver().update(uri, v, null, null);
            } else {
                ctx.getContentResolver().delete(uri, null, null);
            }
            uri = null;
        }
        long secs = (System.currentTimeMillis() - startedAt) / 1000;
        log.log(ok ? "Saved " + framesIn + " frames (" + secs + "s) to " + saved : "Recording failed; nothing saved.");
        return ok ? saved : null;
    }
}
