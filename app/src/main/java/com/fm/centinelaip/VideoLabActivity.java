package com.fm.centinelaip;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaMetadataRetriever;
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
    private static final float DETECTION_THRESHOLD = 0.20f;
    private static final long INFERENCE_INTERVAL_MS = 320L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean inferenceRunning = new AtomicBoolean(false);
    private final Object retrieverLock = new Object();
    private final Object fpsLock = new Object();

    private TextureView texture;
    private DetectionOverlayView overlay;
    private TextView sourceLabel;
    private TextView statusLabel;
    private TextView diagnosticsLabel;
    private Button playButton;
    private Button aiButton;
    private ExoPlayer player;
    private YoloDetector detector;
    private MediaMetadataRetriever retriever;
    private Uri videoUri;
    private boolean aiEnabled = true;
    private boolean visible;
    private volatile boolean retrieverReady;
    private volatile int encodedVideoWidth;
    private volatile int encodedVideoHeight;
    private volatile int displayVideoWidth;
    private volatile int displayVideoHeight;
    private volatile float videoFps;
    private volatile float inferenceFps;
    private long renderedFrames;
    private long renderedWindowStartedNs;
    private long inferenceFrames;
    private long inferenceWindowStartedNs;
    private long lastAnalyzedPositionMs = Long.MIN_VALUE;

    private final Runnable inferenceTask = new Runnable() {
        @Override public void run() {
            boolean ready = visible && aiEnabled && detector != null && retrieverReady
                    && player != null && player.getPlaybackState() == Player.STATE_READY;
            if (ready && inferenceRunning.compareAndSet(false, true)) {
                long positionMs = player.getCurrentPosition();
                if (!player.isPlaying() && positionMs == lastAnalyzedPositionMs) {
                    inferenceRunning.set(false);
                } else {
                    lastAnalyzedPositionMs = positionMs;
                    analyzeAt(positionMs);
                }
            }
            if (visible) handler.postDelayed(this, INFERENCE_INTERVAL_MS);
        }
    };

    private final Runnable diagnosticsTask = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            DeviceDiagnostics.Snapshot snapshot = DeviceDiagnostics.read(VideoLabActivity.this);
            diagnosticsLabel.setText(String.format(Locale.US,
                    "Video %.1f FPS · IA %.1f FPS · %s",
                    videoFps, inferenceFps, snapshot.compactLabel()));
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
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
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
        texture.setOpaque(false);
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

        TextView warning = text(
                "Alta sensibilidad experimental: confirma visualmente cada detección.",
                11, Color.rgb(100, 116, 139), false);
        warning.setPadding(0, dp(9), 0, 0);
        root.addView(warning);
        return root;
    }

    private void createPlayer() {
        player = new ExoPlayer.Builder(this).build();
        player.setRepeatMode(Player.REPEAT_MODE_ALL);
        player.setVideoFrameMetadataListener((presentationTimeUs, releaseTimeNs, format, mediaFormat) ->
                recordRenderedFrame());
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) {
                    setStatus("CARGANDO VIDEO", Color.rgb(245, 158, 11));
                } else if (state == Player.STATE_READY) {
                    setStatus("VIDEO LISTO", Color.rgb(34, 211, 238));
                }
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
                    setStatus("MODELO NO DISPONIBLE", Color.rgb(239, 68, 68));
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
        lastAnalyzedPositionMs = Long.MIN_VALUE;
        retrieverReady = false;
        configureRetriever(videoUri);
        player.setMediaItem(MediaItem.fromUri(videoUri));
        player.prepare();
        player.setPlayWhenReady(autoplay);
        setStatus("PREPARANDO VIDEO", Color.rgb(245, 158, 11));
    }

    private void configureRetriever(Uri uri) {
        executor.execute(() -> {
            MediaMetadataRetriever next = new MediaMetadataRetriever();
            try {
                next.setDataSource(VideoLabActivity.this, uri);
                int width = parseMetadataInt(next,
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                int height = parseMetadataInt(next,
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                int rotation = parseMetadataInt(next,
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if (width <= 0 || height <= 0) {
                    throw new IllegalStateException("El video no informa sus dimensiones");
                }
                int[] oriented = VideoGeometry.orientedSize(width, height, rotation);
                synchronized (retrieverLock) {
                    if (retriever != null) retriever.release();
                    retriever = next;
                    encodedVideoWidth = width;
                    encodedVideoHeight = height;
                    displayVideoWidth = oriented[0];
                    displayVideoHeight = oriented[1];
                    retrieverReady = true;
                }
                handler.post(() -> {
                    sourceLabel.setText(readableName(uri) + " · "
                            + displayVideoWidth + "×" + displayVideoHeight);
                    applyVideoTransform();
                });
            } catch (Exception error) {
                try { next.release(); } catch (Exception ignored) { }
                handler.post(() -> setStatus("NO SE PUDO LEER EL VIDEO",
                        Color.rgb(239, 68, 68)));
            }
        });
    }

    private int parseMetadataInt(MediaMetadataRetriever value, int key) {
        try {
            String text = value.extractMetadata(key);
            return text == null ? 0 : Integer.parseInt(text);
        } catch (Exception ignored) {
            return 0;
        }
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

    private void analyzeAt(long positionMs) {
        executor.execute(() -> {
            long started = System.nanoTime();
            Bitmap frame = null;
            try {
                frame = captureFrameAt(positionMs, 960);
                if (frame == null) throw new IllegalStateException("Fotograma no disponible");
                List<Detection> detections = detector.detect(frame, DETECTION_THRESHOLD);
                long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
                recordInferenceFrame();
                Bitmap analyzedFrame = frame;
                handler.post(() -> {
                    overlay.setDetections(detections,
                            analyzedFrame.getWidth(), analyzedFrame.getHeight());
                    statusLabel.setTextColor(Color.rgb(34, 197, 94));
                    statusLabel.setText((player.isPlaying() ? "REPRODUCIENDO" : "VIDEO PAUSADO")
                            + " · " + detections.size() + " obj · IA " + elapsedMs + " ms"
                            + " · umbral 20%");
                });
            } catch (Exception error) {
                handler.post(() -> setStatus("ERROR DE INFERENCIA · "
                        + error.getClass().getSimpleName(), Color.rgb(239, 68, 68)));
            } finally {
                if (frame != null) frame.recycle();
                inferenceRunning.set(false);
            }
        });
    }

    private Bitmap captureFrameAt(long positionMs, int maxDimension) {
        synchronized (retrieverLock) {
            if (retriever == null || !retrieverReady
                    || encodedVideoWidth <= 0 || encodedVideoHeight <= 0) return null;
            int[] size = VideoGeometry.scaledSize(
                    encodedVideoWidth, encodedVideoHeight, maxDimension);
            return retriever.getScaledFrameAtTime(
                    Math.max(0L, positionMs) * 1_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    size[0], size[1]);
        }
    }

    private void applyVideoTransform() {
        if (texture == null || texture.getWidth() <= 0 || texture.getHeight() <= 0) return;
        float[] scale = VideoGeometry.fitScale(texture.getWidth(), texture.getHeight(),
                displayVideoWidth, displayVideoHeight);
        Matrix matrix = new Matrix();
        matrix.setScale(scale[0], scale[1],
                texture.getWidth() / 2f, texture.getHeight() / 2f);
        texture.setTransform(matrix);
    }

    private void recordRenderedFrame() {
        synchronized (fpsLock) {
            long now = System.nanoTime();
            if (renderedWindowStartedNs == 0L) renderedWindowStartedNs = now;
            renderedFrames++;
            long elapsed = now - renderedWindowStartedNs;
            if (elapsed >= 1_000_000_000L) {
                videoFps = renderedFrames * 1_000_000_000f / elapsed;
                renderedFrames = 0L;
                renderedWindowStartedNs = now;
            }
        }
    }

    private void recordInferenceFrame() {
        synchronized (fpsLock) {
            long now = System.nanoTime();
            if (inferenceWindowStartedNs == 0L) inferenceWindowStartedNs = now;
            inferenceFrames++;
            long elapsed = now - inferenceWindowStartedNs;
            if (elapsed >= 1_000_000_000L) {
                inferenceFps = inferenceFrames * 1_000_000_000f / elapsed;
                inferenceFrames = 0L;
                inferenceWindowStartedNs = now;
            }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        synchronized (fpsLock) {
            renderedFrames = 0L;
            inferenceFrames = 0L;
            renderedWindowStartedNs = 0L;
            inferenceWindowStartedNs = 0L;
            videoFps = 0f;
            inferenceFps = 0f;
        }
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
            player.clearVideoFrameMetadataListener();
            player.clearVideoTextureView(texture);
            player.release();
        }
        if (detector != null) {
            try { detector.close(); } catch (Exception ignored) { }
        }
        synchronized (retrieverLock) {
            if (retriever != null) {
                try { retriever.release(); } catch (Exception ignored) { }
                retriever = null;
            }
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override public void onSurfaceTextureAvailable(
            SurfaceTexture surface, int width, int height) {
        if (player != null) player.setVideoTextureView(texture);
        applyVideoTransform();
    }

    @Override public void onSurfaceTextureSizeChanged(
            SurfaceTexture surface, int width, int height) {
        applyVideoTransform();
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        if (player != null) player.clearVideoTextureView(texture);
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { }

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
        button.setBackgroundColor(primary
                ? Color.rgb(34, 211, 238) : Color.rgb(30, 41, 59));
        return button;
    }

    private FrameLayout.LayoutParams matchFrame() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout.LayoutParams actionParams(int startDp, int endDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(50), 1f);
        params.leftMargin = dp(startDp);
        params.rightMargin = dp(endDp);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
