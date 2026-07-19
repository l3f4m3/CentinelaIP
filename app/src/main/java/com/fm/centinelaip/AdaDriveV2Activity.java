package com.fm.centinelaip;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Modo de conducción experimental con cámara/video, YOLO, GNSS, IMU y calibración.
 * No es un ADAS homologado y no debe usarse para tomar decisiones de conducción.
 */
@UnstableApi
public final class AdaDriveV2Activity extends ComponentActivity
        implements TextureView.SurfaceTextureListener {

    private static final int CAMERA_PERMISSION = 730;
    private static final int LOCATION_PERMISSION = 731;
    private static final int PICK_VIDEO = 732;
    private static final float DETECTION_THRESHOLD = 0.20f;
    private static final String PREFS = "ada_drive_v2";
    private static final String KEY_VIDEO_URI = "video_uri";

    private enum SourceMode { CAMERA, VIDEO }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService inferenceExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean inferenceRunning = new AtomicBoolean(false);
    private final Object retrieverLock = new Object();
    private final Object fpsLock = new Object();

    private PreviewView cameraPreview;
    private TextureView videoTexture;
    private DetectionOverlayView detectionOverlay;
    private DriveHudView driveHud;
    private TextView sourceLabel;
    private TextView statusLabel;
    private TextView diagnosticsLabel;
    private TextView motionLabel;
    private TextView speedLabel;
    private Button cameraButton;
    private Button videoButton;
    private Button calibrateButton;
    private Button aiButton;

    private ProcessCameraProvider cameraProvider;
    private ExoPlayer player;
    private MediaMetadataRetriever retriever;
    private YoloDetector detector;
    private DriveSensors driveSensors;
    private DriveSensors.Snapshot sensorSnapshot;
    private Uri videoUri;
    private SourceMode sourceMode = SourceMode.CAMERA;

    private boolean visible;
    private boolean aiEnabled = true;
    private boolean cameraReady;
    private boolean retrieverReady;
    private boolean locationPermissionRequested;
    private int videoDisplayWidth;
    private int videoDisplayHeight;
    private int currentThermalStatus;
    private long lastInferenceLatencyMs;
    private long lastAnalyzedPositionMs = Long.MIN_VALUE;
    private long renderedFrames;
    private long renderedWindowStartedNs;
    private long inferenceFrames;
    private long inferenceWindowStartedNs;
    private float sourceFps;
    private float inferenceFps;

    private final Runnable inferenceTask = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            requestInference();
            long interval = AdaptiveInferencePolicy.nextIntervalMs(
                    lastInferenceLatencyMs, currentThermalStatus);
            handler.postDelayed(this, interval);
        }
    };

    private final Runnable diagnosticsTask = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            DeviceDiagnostics.Snapshot snapshot = DeviceDiagnostics.read(AdaDriveV2Activity.this);
            currentThermalStatus = snapshot.thermalStatus;
            String sourceMetric = sourceMode == SourceMode.VIDEO
                    ? String.format(Locale.US, "Video %.1f FPS", sourceFps)
                    : cameraReady ? "Cámara en vivo" : "Cámara preparando";
            diagnosticsLabel.setText(String.format(Locale.US,
                    "%s · IA %.1f FPS · %s",
                    sourceMetric, inferenceFps, snapshot.compactLabel()));
            handler.postDelayed(this, 1_000L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(3, 8, 15));
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(createUi());
        driveSensors = new DriveSensors(this, this::onSensorSnapshot);
        createPlayer();
        loadDetector();
        restoreVideoUri();
        switchToCamera();
    }

    private View createUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(3, 8, 15));
        root.setPadding(dp(12), dp(8), dp(12), dp(10));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("‹", false);
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(44)));

        LinearLayout titleGroup = new LinearLayout(this);
        titleGroup.setOrientation(LinearLayout.VERTICAL);
        titleGroup.addView(text("ADA Drive", 20, Color.WHITE, true));
        sourceLabel = text("Cámara trasera", 11, Color.rgb(148, 163, 184), false);
        titleGroup.addView(sourceLabel);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = dp(10);
        header.addView(titleGroup, titleParams);

        speedLabel = text("-- km/h", 20, Color.rgb(34, 211, 238), true);
        header.addView(speedLabel);
        root.addView(header);

        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        cameraPreview = new PreviewView(this);
        cameraPreview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        cameraPreview.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        stage.addView(cameraPreview, matchFrame());

        videoTexture = new TextureView(this);
        videoTexture.setOpaque(false);
        videoTexture.setSurfaceTextureListener(this);
        videoTexture.setVisibility(View.GONE);
        stage.addView(videoTexture, matchFrame());

        driveHud = new DriveHudView(this, null);
        stage.addView(driveHud, matchFrame());
        detectionOverlay = new DetectionOverlayView(this, null);
        stage.addView(detectionOverlay, matchFrame());

        LinearLayout.LayoutParams stageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        stageParams.topMargin = dp(7);
        root.addView(stage, stageParams);

        statusLabel = text("PREPARANDO", 13, Color.rgb(245, 158, 11), true);
        statusLabel.setPadding(0, dp(7), 0, dp(2));
        root.addView(statusLabel);
        diagnosticsLabel = text("Esperando telemetría…", 11,
                Color.rgb(148, 163, 184), false);
        root.addView(diagnosticsLabel);
        motionLabel = text("IMU esperando · GPS esperando · montaje sin calibrar", 11,
                Color.rgb(148, 163, 184), false);
        root.addView(motionLabel);

        LinearLayout controls = new LinearLayout(this);
        controls.setPadding(0, dp(8), 0, 0);
        cameraButton = button("Cámara", true);
        cameraButton.setOnClickListener(view -> switchToCamera());
        controls.addView(cameraButton, weightedParams(1f, 0, 3));
        videoButton = button("Video", false);
        videoButton.setOnClickListener(view -> switchToVideo());
        controls.addView(videoButton, weightedParams(1f, 3, 3));
        calibrateButton = button("Calibrar", false);
        calibrateButton.setOnClickListener(view -> calibrateMount());
        calibrateButton.setOnLongClickListener(view -> {
            driveSensors.clearCalibration();
            Toast.makeText(this, "Calibración eliminada", Toast.LENGTH_SHORT).show();
            return true;
        });
        controls.addView(calibrateButton, weightedParams(1f, 3, 3));
        aiButton = button("IA activa", false);
        aiButton.setOnClickListener(view -> toggleAi());
        controls.addView(aiButton, weightedParams(1f, 3, 0));
        root.addView(controls);

        TextView disclaimer = text(
                "Velocidad GNSS e inclinación IMU experimentales · mantén pulsado Calibrar para reiniciar.",
                10, Color.rgb(100, 116, 139), false);
        disclaimer.setPadding(0, dp(6), 0, 0);
        root.addView(disclaimer);
        return root;
    }

    private void createPlayer() {
        player = new ExoPlayer.Builder(this).build();
        player.setRepeatMode(Player.REPEAT_MODE_ALL);
        player.setVideoFrameMetadataListener((presentationTimeUs, releaseTimeNs, format, mediaFormat) ->
                recordRenderedFrame());
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (sourceMode != SourceMode.VIDEO) return;
                if (state == Player.STATE_BUFFERING) {
                    setStatus("CARGANDO VIDEO", Color.rgb(245, 158, 11));
                } else if (state == Player.STATE_READY) {
                    setStatus("VIDEO LISTO", Color.rgb(34, 211, 238));
                }
            }

            @Override public void onIsPlayingChanged(boolean playing) {
                if (sourceMode == SourceMode.VIDEO) {
                    setStatus(playing ? "VIDEO EN REPRODUCCIÓN" : "VIDEO PAUSADO",
                            playing ? Color.rgb(34, 197, 94) : Color.rgb(148, 163, 184));
                }
            }

            @Override public void onVideoSizeChanged(VideoSize size) {
                if (size.width <= 0 || size.height <= 0) return;
                float ratio = size.pixelWidthHeightRatio <= 0f ? 1f : size.pixelWidthHeightRatio;
                videoDisplayWidth = Math.max(1, Math.round(size.width * ratio));
                videoDisplayHeight = size.height;
                applyVideoTransform();
            }

            @Override public void onPlayerError(PlaybackException error) {
                if (sourceMode == SourceMode.VIDEO) {
                    setStatus("ERROR DE VIDEO", Color.rgb(239, 68, 68));
                    Toast.makeText(AdaDriveV2Activity.this,
                            "No se pudo reproducir el video", Toast.LENGTH_LONG).show();
                }
            }
        });
        if (videoTexture.isAvailable()) player.setVideoTextureView(videoTexture);
    }

    private void loadDetector() {
        inferenceExecutor.execute(() -> {
            try {
                detector = new YoloDetector(getApplicationContext(), new ModelStore(this).active());
                handler.post(() -> setStatus("MODELO LISTO", Color.rgb(34, 211, 238)));
            } catch (Exception error) {
                handler.post(() -> {
                    aiEnabled = false;
                    aiButton.setEnabled(false);
                    aiButton.setText("IA no disponible");
                    setStatus("MODELO NO DISPONIBLE", Color.rgb(239, 68, 68));
                });
            }
        });
    }

    private void restoreVideoUri() {
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_VIDEO_URI, "");
        if (saved != null && !saved.isBlank()) videoUri = Uri.parse(saved);
    }

    private void switchToCamera() {
        sourceMode = SourceMode.CAMERA;
        sourceLabel.setText("Cámara trasera · YOLO local");
        detectionOverlay.clear();
        cameraPreview.setVisibility(View.VISIBLE);
        videoTexture.setVisibility(View.GONE);
        if (player != null) player.pause();
        updateSourceButtons();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            setStatus("ESPERANDO PERMISO DE CÁMARA", Color.rgb(245, 158, 11));
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
            return;
        }
        bindCamera();
    }

    private void bindCamera() {
        cameraReady = false;
        setStatus("ABRIENDO CÁMARA", Color.rgb(245, 158, 11));
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                cameraProvider.unbindAll();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());
                cameraProvider.bindToLifecycle(this,
                        CameraSelector.DEFAULT_BACK_CAMERA, preview);
                cameraReady = true;
                setStatus("CÁMARA EN VIVO", Color.rgb(34, 197, 94));
            } catch (Exception error) {
                cameraReady = false;
                setStatus("CÁMARA NO DISPONIBLE", Color.rgb(239, 68, 68));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void switchToVideo() {
        sourceMode = SourceMode.VIDEO;
        cameraReady = false;
        if (cameraProvider != null) cameraProvider.unbindAll();
        cameraPreview.setVisibility(View.GONE);
        videoTexture.setVisibility(View.VISIBLE);
        detectionOverlay.clear();
        updateSourceButtons();
        if (videoUri == null) pickVideo();
        else prepareVideo();
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
                || data == null || data.getData() == null) {
            if (sourceMode == SourceMode.VIDEO && videoUri == null) switchToCamera();
            return;
        }
        videoUri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(videoUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_VIDEO_URI, videoUri.toString()).apply();
        prepareVideo();
    }

    private void prepareVideo() {
        if (videoUri == null || player == null) return;
        sourceLabel.setText("Video local · " + readableName(videoUri));
        retrieverReady = false;
        lastAnalyzedPositionMs = Long.MIN_VALUE;
        configureRetriever(videoUri);
        player.setMediaItem(MediaItem.fromUri(videoUri));
        player.prepare();
        player.play();
        setStatus("PREPARANDO VIDEO", Color.rgb(245, 158, 11));
    }

    private void configureRetriever(Uri uri) {
        inferenceExecutor.execute(() -> {
            MediaMetadataRetriever next = new MediaMetadataRetriever();
            try {
                next.setDataSource(AdaDriveV2Activity.this, uri);
                synchronized (retrieverLock) {
                    if (retriever != null) retriever.release();
                    retriever = next;
                    retrieverReady = true;
                }
            } catch (Exception error) {
                try { next.release(); } catch (Exception ignored) { }
                handler.post(() -> setStatus("VIDEO NO LEGIBLE", Color.rgb(239, 68, 68)));
            }
        });
    }

    private void requestInference() {
        if (!aiEnabled || detector == null || inferenceRunning.get()) return;
        if (sourceMode == SourceMode.CAMERA) {
            if (!cameraReady || !inferenceRunning.compareAndSet(false, true)) return;
            Bitmap frame = captureCameraFrame(960);
            if (frame == null) {
                inferenceRunning.set(false);
                return;
            }
            analyzeCameraFrame(frame);
            return;
        }

        if (player == null || !retrieverReady
                || player.getPlaybackState() != Player.STATE_READY
                || !inferenceRunning.compareAndSet(false, true)) return;
        long positionMs = player.getCurrentPosition();
        if (!player.isPlaying() && positionMs == lastAnalyzedPositionMs) {
            inferenceRunning.set(false);
            return;
        }
        lastAnalyzedPositionMs = positionMs;
        inferenceExecutor.execute(() -> analyzeVideoPosition(positionMs));
    }

    private Bitmap captureCameraFrame(int maxDimension) {
        Bitmap frame = cameraPreview.getBitmap();
        return resize(frame, maxDimension);
    }

    private void analyzeCameraFrame(Bitmap frame) {
        inferenceExecutor.execute(() -> {
            try {
                detectAndPublish(frame);
            } catch (Exception error) {
                handler.post(() -> setStatus("ERROR DE INFERENCIA", Color.rgb(239, 68, 68)));
            } finally {
                frame.recycle();
                inferenceRunning.set(false);
            }
        });
    }

    private void analyzeVideoPosition(long positionMs) {
        Bitmap frame = null;
        try {
            synchronized (retrieverLock) {
                if (retriever != null) {
                    frame = retriever.getFrameAtTime(positionMs * 1_000L,
                            MediaMetadataRetriever.OPTION_CLOSEST);
                }
            }
            frame = resize(frame, 960);
            if (frame == null) throw new IllegalStateException("Fotograma no disponible");
            detectAndPublish(frame);
        } catch (Exception error) {
            handler.post(() -> setStatus("ERROR DE INFERENCIA", Color.rgb(239, 68, 68)));
        } finally {
            if (frame != null) frame.recycle();
            inferenceRunning.set(false);
        }
    }

    private Bitmap resize(Bitmap frame, int maxDimension) {
        if (frame == null) return null;
        int[] size = VideoGeometry.scaledSize(frame.getWidth(), frame.getHeight(), maxDimension);
        if (size[0] == frame.getWidth() && size[1] == frame.getHeight()) return frame;
        Bitmap resized = Bitmap.createScaledBitmap(frame, size[0], size[1], true);
        frame.recycle();
        return resized;
    }

    private void detectAndPublish(Bitmap frame) throws Exception {
        long started = System.nanoTime();
        List<Detection> detections = detector.detect(frame, DETECTION_THRESHOLD);
        lastInferenceLatencyMs = (System.nanoTime() - started) / 1_000_000L;
        recordInferenceFrame();
        int width = frame.getWidth();
        int height = frame.getHeight();
        long nextInterval = AdaptiveInferencePolicy.nextIntervalMs(
                lastInferenceLatencyMs, currentThermalStatus);
        SourceMode analyzedSource = sourceMode;
        handler.post(() -> {
            detectionOverlay.setDetections(detections, width, height);
            statusLabel.setTextColor(Color.rgb(34, 197, 94));
            statusLabel.setText(String.format(Locale.US,
                    "%s · %d obj · IA %d ms · siguiente %d ms",
                    analyzedSource == SourceMode.CAMERA ? "CÁMARA" : "VIDEO",
                    detections.size(), lastInferenceLatencyMs, nextInterval));
        });
    }

    private void onSensorSnapshot(DriveSensors.Snapshot snapshot) {
        sensorSnapshot = snapshot;
        handler.post(() -> renderSensorSnapshot(snapshot));
    }

    private void renderSensorSnapshot(DriveSensors.Snapshot snapshot) {
        if (snapshot == null) return;
        if (snapshot.locationAvailable && Float.isFinite(snapshot.speedKmh)) {
            speedLabel.setText(String.format(Locale.US, "%.0f km/h", snapshot.speedKmh));
        } else {
            speedLabel.setText("-- km/h");
        }
        driveHud.setAttitude(snapshot.pitchDegrees, snapshot.rollDegrees, snapshot.calibrated);
        String imu = snapshot.motionAvailable
                ? String.format(Locale.US, "IMU P %.1f° R %.1f° H %.0f°",
                snapshot.pitchDegrees, snapshot.rollDegrees, snapshot.headingDegrees)
                : "IMU no disponible";
        String gps = snapshot.locationAvailable
                ? Float.isFinite(snapshot.locationAccuracyMeters)
                ? String.format(Locale.US, "GPS ±%.0f m", snapshot.locationAccuracyMeters)
                : "GPS activo"
                : "GPS esperando";
        motionLabel.setText(imu + " · " + gps + " · "
                + (snapshot.calibrated ? "montaje calibrado" : "montaje sin calibrar"));
        calibrateButton.setText(snapshot.calibrated ? "Recalibrar" : "Calibrar");
    }

    private void calibrateMount() {
        if (driveSensors.calibrateCurrentMount()) {
            Toast.makeText(this,
                    "Montaje calibrado. Mantén el teléfono fijo en su soporte.",
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "La IMU aún no entrega orientación", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestLocationPermissionIfNeeded() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            driveSensors.startLocationIfPermitted();
            return;
        }
        if (locationPermissionRequested) return;
        locationPermissionRequested = true;
        requestPermissions(new String[]{
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
        }, LOCATION_PERMISSION);
    }

    private void toggleAi() {
        aiEnabled = !aiEnabled;
        aiButton.setText(aiEnabled ? "IA activa" : "IA pausada");
        driveHud.setActive(aiEnabled);
        if (!aiEnabled) detectionOverlay.clear();
    }

    private void updateSourceButtons() {
        boolean camera = sourceMode == SourceMode.CAMERA;
        styleButton(cameraButton, camera);
        styleButton(videoButton, !camera);
    }

    private void styleButton(Button button, boolean selected) {
        button.setBackgroundColor(selected
                ? Color.rgb(34, 211, 238) : Color.rgb(30, 41, 59));
        button.setTextColor(selected ? Color.rgb(3, 8, 15) : Color.WHITE);
    }

    private void applyVideoTransform() {
        if (videoTexture == null || videoDisplayWidth <= 0 || videoDisplayHeight <= 0
                || videoTexture.getWidth() <= 0 || videoTexture.getHeight() <= 0) return;
        float[] scale = VideoGeometry.fitScale(videoTexture.getWidth(), videoTexture.getHeight(),
                videoDisplayWidth, videoDisplayHeight);
        Matrix matrix = new Matrix();
        matrix.setScale(scale[0], scale[1],
                videoTexture.getWidth() / 2f, videoTexture.getHeight() / 2f);
        videoTexture.setTransform(matrix);
    }

    private void recordRenderedFrame() {
        synchronized (fpsLock) {
            long now = System.nanoTime();
            if (renderedWindowStartedNs == 0L) renderedWindowStartedNs = now;
            renderedFrames++;
            long elapsed = now - renderedWindowStartedNs;
            if (elapsed >= 1_000_000_000L) {
                sourceFps = renderedFrames * 1_000_000_000f / elapsed;
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

    @Override public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == CAMERA_PERMISSION) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) bindCamera();
            else setStatus("PERMISO DE CÁMARA DENEGADO", Color.rgb(239, 68, 68));
            return;
        }
        if (requestCode == LOCATION_PERMISSION) {
            driveSensors.startLocationIfPermitted();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        driveSensors.start();
        requestLocationPermissionIfNeeded();
        handler.removeCallbacks(inferenceTask);
        handler.removeCallbacks(diagnosticsTask);
        handler.post(inferenceTask);
        handler.post(diagnosticsTask);
        if (sourceMode == SourceMode.CAMERA
                && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            bindCamera();
        } else if (sourceMode == SourceMode.VIDEO && player != null && videoUri != null) {
            player.play();
        }
    }

    @Override protected void onPause() {
        visible = false;
        handler.removeCallbacks(inferenceTask);
        handler.removeCallbacks(diagnosticsTask);
        driveSensors.stop();
        if (player != null) player.pause();
        if (cameraProvider != null) cameraProvider.unbindAll();
        cameraReady = false;
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        driveSensors.stop();
        if (cameraProvider != null) cameraProvider.unbindAll();
        if (player != null) {
            player.clearVideoTextureView(videoTexture);
            player.release();
        }
        synchronized (retrieverLock) {
            if (retriever != null) {
                try { retriever.release(); } catch (Exception ignored) { }
                retriever = null;
            }
        }
        if (detector != null) {
            try { detector.close(); } catch (Exception ignored) { }
        }
        inferenceExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override public void onSurfaceTextureAvailable(
            SurfaceTexture surface, int width, int height) {
        if (player != null) player.setVideoTextureView(videoTexture);
        applyVideoTransform();
    }

    @Override public void onSurfaceTextureSizeChanged(
            SurfaceTexture surface, int width, int height) {
        applyVideoTransform();
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        if (player != null) player.clearVideoTextureView(videoTexture);
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { }

    private void setStatus(String value, int color) {
        statusLabel.setText(value);
        statusLabel.setTextColor(color);
    }

    private String readableName(Uri uri) {
        String value = uri == null ? null : uri.getLastPathSegment();
        return value == null || value.isBlank() ? "seleccionado" : value;
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
        button.setTextColor(primary ? Color.rgb(3, 8, 15) : Color.WHITE);
        button.setBackgroundColor(primary
                ? Color.rgb(34, 211, 238) : Color.rgb(30, 41, 59));
        return button;
    }

    private FrameLayout.LayoutParams matchFrame() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout.LayoutParams weightedParams(float weight, int start, int end) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), weight);
        params.leftMargin = dp(start);
        params.rightMargin = dp(end);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
