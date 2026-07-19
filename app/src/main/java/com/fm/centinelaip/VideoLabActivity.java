package com.fm.centinelaip;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Banco de pruebas de video local, YOLO y estabilidad del dispositivo. */
@UnstableApi
public final class VideoLabActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final int PICK_VIDEO = 520;
    private static final String PREFS = "ada_video_lab";
    private static final String KEY_URI = "last_video_uri";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean inferenceRunning = new AtomicBoolean(false);

    private TextureView texture;
    private DetectionOverlayView overlay;
    private TextView sourceLabel;
    private TextView statusLabel;
    private TextView diagnosticsLabel;
    private Button playButton;
    private Button aiButton;
    private ExoPlayer player;
    private YoloDetector detector;
    private Uri videoUri;
    private boolean aiEnabled = true;
    private boolean visible;
    private long frameCount;
    private long fpsStart;
    private float fps;

    private final Runnable inferenceTask = new Runnable() {
        @Override public void run() {
            boolean ready = visible && aiEnabled && detector != null && player != null
                    && player.getPlaybackState() == Player.STATE_READY;
            if (ready && inferenceRunning.compareAndSet(false, true)) {
                Bitmap frame = captureFrame(960);
                if (frame == null) inferenceRunning.set(false);
                else analyze(frame);
            }
            if (visible) handler.postDelayed(this, 320L);
        }
    };

    private final Runnable diagnosticsTask = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            DeviceDiagnostics.Snapshot snapshot = DeviceDiagnostics.read(VideoLabActivity.this);
            diagnosticsLabel.setText(String.format(Locale.US, "Render %.1f FPS · %s",
                    fps, snapshot.compactLabel()));
            handler.postDelayed(this, 1_000L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(4, 10, 18));
        getWindow().setNavigationBarColor(Color.BLACK);
        setContentView(createUi());
        createPlayer();
        loadDetector();

        String savedUri = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URI, "");
        if (savedUri != null && !savedUri.isBlank()) {
            videoUri = Uri.parse(savedUri);
            sourceLabel.setText(readableName(videoUri));
            prepareVideo(false);
        }
    }

    private View createUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(14));
        root.setBackgroundColor(Color.rgb(4, 10, 18));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("‹", false);
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(52), dp(48)));
        TextView title = text("ADA · Laboratorio de video", 21, Color.WHITE, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = dp(12);
        header.addView(title, titleParams);
        root.addView(header);

        sourceLabel = text("Selecciona el video de prueba", 13,
                Color.rgb(148, 163, 184), false);
        sourceLabel.setPadding(0, dp(8), 0, dp(8));
        root.addView(sourceLabel);

        FrameLayout videoHost = new FrameLayout(this);
        videoHost.setBackgroundColor(Color.BLACK);
        texture = new TextureView(this);
        texture.setSurfaceTextureListener(this);
        videoHost.addView(texture, matchFrame());
        overlay = new DetectionOverlayView(this, null);
        videoHost.addView(overlay, matchFrame());
        LinearLayout.LayoutParams videoParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(videoHost, videoParams);

        statusLabel = text("SIN VIDEO", 13, Color.rgb(245, 158, 11), true);
        statusLabel.setPadding(0, dp(10), 0, dp(4));
        root.addView(statusLabel);
        diagnosticsLabel = text("Esperando telemetría…", 12,
                Color.rgb(148, 163, 184), false);
        root.addView(diagnosticsLabel);

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(12), 0, 0);
        Button openButton = button("Abrir video", true);
        openButton.setOnClickListener(view -> pickVideo());
        actions.addView(openButton, actionParams(0, 5));
        playButton = button("Reproducir", false);
        playButton.setOnClickListener(view -> togglePlayback());
        actions.addView(playButton, actionParams(5, 5));
        aiButton = button("IA activa", false);
        aiButton.setOnClickListener(view -> toggleAi());
        actions.addView(aiButton, actionParams(5, 0));
        root.addView(actions);

        TextView warning = text("Prueba experimental: no usar para decisiones de conducción.",
                11, Color.rgb(100, 116, 139), false);
        warning.setPadding(0, dp(9), 0, 0);
        root.addView(warning);
        return root;
    }

    private void createPlayer() {
        player = new ExoPlayer.Builder(this).build();
        player.setRepeatMode(Player.REPEAT_MODE_ALL);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) setStatus("CARGANDO VIDEO", Color.rgb(245, 158, 11));
                else if (state == Player.STATE_READY) setStatus("VIDEO LISTO", Color.rgb(34, 211, 238));
                updatePlayButton();
            }

            @Override public void onIsPlayingChanged(boolean playing) {
                setStatus(playing ? "REPRODUCIENDO" : "VIDEO PAUSADO",
                        playing ? Color.rgb(34, 197, 94) : Color.rgb(148, 163, 184));
                updatePlayButton();
            }

            @Override public void onPlayerError(PlaybackException error) {
                setStatus("ERROR DE VIDEO", Color.rgb(239, 68, 68));
                Toast.makeText(VideoLabActivity.this,
                        "No se pudo reproducir este archivo", Toast.LENGTH_LONG).show();
            }
        });
        if (texture.isAvailable()) player.setVideoTextureView(texture);
    }

    private void loadDetector() {
        executor.execute(() -> {
            try {
                detector = new YoloDetector(getApplicationContext(), new ModelStore(this).active());
            } catch (Exception error) {
                handler.post(() -> {
                    aiEnabled = false;
                    aiButton.setText("IA no disponible");
                    aiButton.setEnabled(false);
                });
            }
        });
    }

    private void pickVideo() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_VIDEO);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_VIDEO || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        videoUri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(videoUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_URI, videoUri.toString()).apply();
        sourceLabel.setText(readableName(videoUri));
        prepareVideo(true);
    }

    private void prepareVideo(boolean autoplay) {
        if (videoUri == null || player == null) return;
        overlay.clear();
        player.setMediaItem(MediaItem.fromUri(videoUri));
        player.prepare();
        player.setPlayWhenReady(autoplay);
        setStatus("PREPARANDO VIDEO", Color.rgb(245, 158, 11));
    }

    private void togglePlayback() {
        if (videoUri == null) {
            pickVideo();
            return;
        }
        if (player.isPlaying()) player.pause();
        else player.play();
    }

    private void toggleAi() {
        aiEnabled = !aiEnabled;
        aiButton.setText(aiEnabled ? "IA activa" : "IA pausada");
        if (!aiEnabled) overlay.clear();
    }

    private Bitmap captureFrame(int maxDimension) {
        if (!texture.isAvailable() || texture.getWidth() <= 0 || texture.getHeight() <= 0) return null;
        float scale = Math.min(1f,
                (float) maxDimension / Math.max(texture.getWidth(), texture.getHeight()));
        return texture.getBitmap(Math.max(1, Math.round(texture.getWidth() * scale)),
                Math.max(1, Math.round(texture.getHeight() * scale)));
    }

    private void analyze(Bitmap frame) {
        executor.execute(() -> {
            long start = System.nanoTime();
            try {
                List<Detection> detections = detector.detect(frame, 0.35f);
                long elapsed = (System.nanoTime() - start) / 1_000_000L;
                handler.post(() -> {
                    overlay.setDetections(detections, frame.getWidth(), frame.getHeight());
                    statusLabel.setText((player.isPlaying() ? "REPRODUCIENDO" : "VIDEO PAUSADO")
                            + " · " + detections.size() + " obj · IA " + elapsed + " ms");
                });
            } catch (Exception error) {
                handler.post(() -> setStatus("ERROR DE INFERENCIA", Color.rgb(239, 68, 68)));
            } finally {
                frame.recycle();
                inferenceRunning.set(false);
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        frameCount = 0;
        fpsStart = SystemClock.elapsedRealtime();
        handler.post(inferenceTask);
        handler.post(diagnosticsTask);
    }

    @Override protected void onPause() {
        visible = false;
        handler.removeCallbacks(inferenceTask);
        handler.removeCallbacks(diagnosticsTask);
        if (player != null) player.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.clearVideoTextureView(texture);
            player.release();
        }
        if (detector != null) {
            try { detector.close(); } catch (Exception ignored) { }
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (player != null) player.setVideoTextureView(texture);
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) { }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        if (player != null) player.clearVideoTextureView(texture);
        return true;
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        frameCount++;
        long now = SystemClock.elapsedRealtime();
        long elapsed = now - fpsStart;
        if (elapsed >= 1_000L) {
            fps = frameCount * 1_000f / elapsed;
            frameCount = 0;
            fpsStart = now;
        }
    }

    private void updatePlayButton() {
        playButton.setText(player != null && player.isPlaying() ? "Pausar" : "Reproducir");
    }

    private void setStatus(String text, int color) {
        statusLabel.setText(text);
        statusLabel.setTextColor(color);
    }

    private String readableName(Uri uri) {
        String value = uri == null ? null : uri.getLastPathSegment();
        return value == null || value.isBlank() ? "Video local seleccionado" : value;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return view;
    }

    private Button button(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextColor(primary ? Color.rgb(4, 10, 18) : Color.WHITE);
        button.setBackgroundColor(primary ? Color.rgb(34, 211, 238) : Color.rgb(30, 41, 59));
        return button;
    }

    private FrameLayout.LayoutParams matchFrame() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout.LayoutParams actionParams(int start, int end) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(50), 1f);
        params.leftMargin = dp(start);
        params.rightMargin = dp(end);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
