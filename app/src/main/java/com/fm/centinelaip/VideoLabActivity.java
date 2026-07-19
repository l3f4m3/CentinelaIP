package com.fm.centinelaip;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Banco de pruebas instalable para validar video local, inferencia y estabilidad térmica
 * antes de integrar estos elementos al mosaico principal.
 */
@UnstableApi
public final class VideoLabActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final int PICK_VIDEO_REQUEST = 520;
    private static final String PREFS = "ada_video_lab";
    private static final String KEY_URI = "last_video_uri";
    private static final long INFERENCE_INTERVAL_MS = 320L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService inferenceExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean inferenceRunning = new AtomicBoolean(false);

    private TextureView textureView;
    private DetectionOverlayView overlay;
    private TextView sourceText;
    private TextView statusText;
    private TextView diagnosticsText;
    private Button playButton;
    private Button aiButton;
    private ExoPlayer player;
    private YoloDetector detector;
    private Uri selectedUri;
    private boolean aiEnabled = true;
    private boolean visible;
    private long renderedFrames;
    private long fpsWindowStartedMs;
    private float renderedFps;

    private final Runnable inferenceLoop = new Runnable() {
        @Override public void run() {
            if (visible && aiEnabled && detector != null && player != null
                    && player.getPlaybackState() == Player.STATE_READY
                    && inferenceRunning.compareAndSet(false, true)) {
                Bitmap frame = captureFrame(960);
                if (frame == null) inferenceRunning.set(false);
                else analyze(frame);
            }
            if (visible) mainHandler.postDelayed(this, INFERENCE_INTERVAL_MS);
        }
    };

    private final Runnable diagnosticsLoop = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            DeviceDiagnostics.Snapshot snapshot = DeviceDiagnostics.read(VideoLabActivity.this);
            diagnosticsText.setText(String.format(java.util.Locale.US,
                    "Render %.1f FPS · %s", renderedFps, snapshot.compactLabel()));
            mainHandler.postDelayed(this, 1_000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(4, 10, 18));
        getWindow().setNavigationBarColor(Color.BLACK);
        setContentView(buildUi());
        initPlayer();
        initDetector();

        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = preferences.getString(KEY_URI, "");
        if (saved != null && !saved.isBlank()) {
            selectedUri = Uri.parse(saved);
            sourceText.setText(readableName(selectedUri));
            prepareSelectedVideo(false);
        }
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(14));
        root.setBackgroundColor(Color.rgb(4, 10, 18));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button close = button("‹", false);
        close.setOnClickListener(view -> finish());
        header.addView(close, new LinearLayout.LayoutParams(dp(52), dp(48)));

        TextView title = text("ADA · Laboratorio de video", 21, Color.WHITE, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = dp(12);
        header.addView(title, titleParams);
        root.addView(header);

        sourceText = text("Selecciona el video de prueba", 13, Color.rgb(148, 163, 184), false);
        sourceText.setPadding(0, dp(8), 0, dp(8));
        root.addView(sourceText);

        FrameLayout videoHost = new FrameLayout(this);
        videoHost.setBackgroundColor(Color.BLACK);
        textureView = new TextureView(this);
        textureView.setSurfaceTextureListener(this);
        videoHost.addView(textureView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay = new DetectionOverlayView(this, null);
        videoHost.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams videoParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        videoParams.topMargin = dp(4);
        root.addView(videoHost, videoParams);

        statusText = text("SIN VIDEO", 13, Color.rgb(245, 158, 11), true);
        statusText.setPadding(0, dp(10), 0, dp(4));
        root.addView(statusText);
        diagnosticsText = text("Esperando telemetría…", 12, Color.rgb(148, 163, 184), false);
        root.addView(diagnosticsText);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(12), 0, 0);

        Button select = button("Abrir video", true);
        select.setOnClickListener(view -> pickVideo());
        actions.addView(select, weightedButtonParams(1f, 0, 5));

        playButton = button("Reproducir", false);
        playButton.setOnClickListener(view -> togglePlayback());
        actions.addView(playButton, weightedButtonParams(1f, 5, 5));

        aiButton = button("IA activa", false);
        aiButton.setOnClickListener(view -> toggleAi());
        actions.addView(aiButton, weightedButtonParams(1f, 5, 0));
        root.addView(actions);

        TextView warning = text(
                "Prueba experimental. Las cajas no representan distancia física ni autorizan decisiones de conducción.",
                11, Color.rgb(100, 116, 139), false);
        warning.setPadding(0, dp(10), 0, 0);
        root.addView(warning);
        return root;
    }

    private void initPlayer() {
        player = new ExoPlayer.Builder(this).build();
        player.setRepeatMode(Player.REPEAT_MODE_ALL);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) setStatus("CARGANDO VIDEO", Color.rgb(245, 158, 11));
                else if (state == Player.STATE_READY) {
                    setStatus("VIDEO LISTO", Color.rgb(34, 211, 238));
                    updatePlayButton();
                } else if (state == Player.STATE_ENDED) updatePlayButton();
            }

            @Override public void onIsPlayingChanged(boolean isPlaying) {
                setStatus(isPlaying ? "REPRODUCIENDO" : "VIDEO PAUSADO",
                        isPlaying ? Color.rgb(34, 197, 94) : Color.rgb(148, 163, 184));
                updatePlayButton();
            }

            @Override public void onPlayerError(androidx.media3.common.PlaybackException error) {
                setStatus("ERROR DE VIDEO", Color.rgb(239, 68, 68));
                Toast.makeText(VideoLabActivity.this,
                        "No se pudo reproducir este archivo", Toast.LENGTH_LONG).show();
            }
        });
        if (textureView != null && textureView.isAvailable()) player.setVideoTextureView(textureView);
    }

    private void initDetector() {
        inferenceExecutor.execute(() -> {
            try {
                YoloDetector value = new YoloDetector(getApplicationContext(), new ModelStore(this).active());
                detector = value;
                mainHandler.post(() -> aiButton.setText("IA activa"));
            } catch (Exception error) {
                mainHandler.post(() -> {
                    aiEnabled = false;
                    aiButton.setText("IA no disponible");
                    aiButton.setEnabled(false);
                    Toast.makeText(this, "No se pudo cargar el modelo activo", Toast.LENGTH_LONG).show();
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
        startActivityForResult(intent, PICK_VIDEO_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_VIDEO_REQUEST || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        selectedUri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(selectedUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_URI, selectedUri.toString()).apply();
        sourceText.setText(readableName(selectedUri));
        prepareSelectedVideo(true);
    }

    private void prepareSelectedVideo(boolean autoplay) {
        if (selectedUri == null || player == null) return;
        overlay.clear();
        player.setMediaItem(MediaItem.fromUri(selectedUri));
        player.prepare();
        player.setPlayWhenReady(autoplay);
        setStatus("PREPARANDO VIDEO", Color.rgb(245, 158, 11));
    }

    private void togglePlayback() {
        if (selectedUri == null) {
            pickVideo();
            return;
        }
        if (player.isPlaying()) player.pause();
        else {
            if (player.getPlaybackState() == Player.STATE_IDLE) prepareSelectedVideo(true);
            player.play();
        }
    }

    private void toggleAi() {
        aiEnabled = !aiEnabled;
        aiButton.setText(aiEnabled ? "IA activa" : "IA pausada");
        if (!aiEnabled) overlay.clear();
    }

    private Bitmap captureFrame(int maxDimension) {
        if (!textureView.isAvailable() || textureView.getWidth() <= 0 || textureView.getHeight() <= 0) {
            return null;
        }
        float scale = Math.min(1f,
                (float) maxDimension / Math.max(textureView.getWidth(), textureView.getHeight()));
        return textureView.getBitmap(
                Math.max(1, Math.round(textureView.getWidth() * scale)),
                Math.max(1, Math.round(textureView.getHeight() * scale)));
    }

    private void analyze(Bitmap frame) {
        inferenceExecutor.execute(() -> {
            long started = System.nanoTime();
            try {
                List<Detection> detections = detector.detect(frame, 0.35f);
                long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
                mainHandler.post(() -> {
                    overlay.setDetections(detections, frame.getWidth(), frame.getHeight());
                    statusText.setText((player != null && player.isPlaying() ? "REPRODUCIENDO" : "VIDEO PAUSADO")
                            + " · " + detections.size() + " obj · IA " + elapsedMs + " ms");
                });
            } catch (Exception error) {
                mainHandler.post(() -> setStatus("ERROR DE INFERENCIA", Color.rgb(239, 68, 68)));
            } finally {
                frame.recycle();
                inferenceRunning.set(false);
            }
        });
    }

    private void updatePlayButton() {
        playButton.setText(player != null && player.isPlaying() ? "Pausar" : "Reproducir");
    }

    private void setStatus(String value, int color) {
        statusText.setText(value);
        statusText.setTextColor(color);
    }

    private String readableName(Uri uri) {
        String value = uri == null ? "" : uri.getLastPathSegment();
        return value == null || value.isBlank() ? "Video local seleccionado" : value;
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        fpsWindowStartedMs = android.os.SystemClock.elapsedRealtime();
        renderedFrames = 0L;
        mainHandler.removeCallbacks(inferenceLoop);
        mainHandler.removeCallbacks(diagnosticsLoop);
        mainHandler.post(inferenceLoop);
        mainHandler.post(diagnosticsLoop);
    }

    @Override protected void onPause() {
        visible = false;
        mainHandler.removeCallbacks(inferenceLoop);
        mainHandler.removeCallbacks(diagnosticsLoop);
        if (player != null) player.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.clearVideoTextureView(textureView);
            player.release();
            player = null;
        }
        if (detector != null) {
            try { detector.close(); } catch (Exception ignored) { }
            detector = null;
        }
        inferenceExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (player != null) player.setVideoTextureView(textureView);
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) { }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        if (player != null) player.clearVideoTextureView(textureView);
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        renderedFrames++;
        long now = android.os.SystemClock.elapsedRealtime();
        long elapsed = now - fpsWindowStartedMs;
        if (elapsed >= 1_000L) {
            renderedFps = renderedFrames * 1_000f / elapsed;
            renderedFrames = 0L;
            fpsWindowStartedMs = now;
        }
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return view;
    }

    private Button button(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextAllCaps(false);
        button.setTextColor(primary ? Color.rgb(4, 10, 18) : Color.WHITE);
        button.setBackgroundColor(primary ? Color.rgb(34, 211, 238) : Color.rgb(30, 41, 59));
        return button;
    }

    private LinearLayout.LayoutParams weightedButtonParams(float weight, int startDp, int endDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(50), weight);
        params.leftMargin = dp(startDp);
        params.rightMargin = dp(endDp);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
