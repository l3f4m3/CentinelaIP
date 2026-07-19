package com.fm.centinelaip;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
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
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.ui.PlayerView;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@UnstableApi
public final class MainActivity extends ComponentActivity {
    private static final int SOURCES_REQUEST = 401;
    private static final int NOTIFICATION_REQUEST = 402;
    private static final int CAMERA_REQUEST = 403;
    private static final long INFERENCE_TICK_MS = 240L;
    private static final long EVENT_COOLDOWN_MS = 25_000L;

    private GridLayout sourcesGrid;
    private LinearLayout mainEmptyState;
    private FrameLayout focusOverlay;
    private FrameLayout focusFeedHost;
    private TextView sourceSummary;
    private TextView globalConnectionBadge;
    private TextView aiBadge;
    private TextView modelBadge;
    private TextView schedulerBadge;
    private TextView focusTitle;
    private TextView focusStatus;
    private TextView focusCounter;
    private Button connectAllButton;
    private Button aiButton;
    private Button focusPreviousButton;
    private Button focusNextButton;

    private SourceStore sourceStore;
    private ModelStore modelStore;
    private EventStore eventStore;
    private EventRulesStore eventRulesStore;
    private final List<FeedController> feeds = new ArrayList<>();
    private ProcessCameraProvider cameraProvider;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService inferenceExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean inferenceRunning = new AtomicBoolean(false);
    private final AtomicBoolean modelLoading = new AtomicBoolean(false);
    private volatile YoloDetector detector;
    private volatile boolean modelReady;
    private volatile String loadedModelId = "";
    private boolean aiEnabled = true;
    private boolean activityVisible;
    private boolean allStopped;
    private int roundRobinIndex;
    private FeedController focusedFeed;

    private final Runnable inferenceLoop = new Runnable() {
        @Override public void run() {
            if (activityVisible && aiEnabled && modelReady
                    && inferenceRunning.compareAndSet(false, true)) {
                FeedController target = nextInferenceFeed();
                if (target == null) {
                    inferenceRunning.set(false);
                } else {
                    Bitmap frame = target.captureFrame(960);
                    if (frame == null) inferenceRunning.set(false);
                    else analyze(target, frame);
                }
            }
            if (activityVisible) mainHandler.postDelayed(this, INFERENCE_TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        sourceStore = new SourceStore(this);
        modelStore = new ModelStore(this);
        eventStore = new EventStore(this);
        eventRulesStore = new EventRulesStore(this);
        NotificationHelper.createChannel(this);
        requestNotificationPermission();

        findViewById(R.id.emptySourcesButton).setOnClickListener(view -> openSources());
        findViewById(R.id.sourcesButton).setOnClickListener(view -> openSources());
        findViewById(R.id.eventsButton).setOnClickListener(view ->
                startActivity(new Intent(this, EventsActivity.class)));
        connectAllButton.setOnClickListener(view -> toggleAll());
        aiButton.setOnClickListener(view -> toggleAi());
        findViewById(R.id.focusExitButton).setOnClickListener(view -> exitFocusMode());
        focusPreviousButton.setOnClickListener(view -> switchFocusedFeed(-1));
        focusNextButton.setOnClickListener(view -> switchFocusedFeed(1));

        initDetector();
        rebuildMosaic();
    }

    private void bindViews() {
        sourcesGrid = findViewById(R.id.sourcesGrid);
        mainEmptyState = findViewById(R.id.mainEmptyState);
        focusOverlay = findViewById(R.id.focusOverlay);
        focusFeedHost = findViewById(R.id.focusFeedHost);
        sourceSummary = findViewById(R.id.sourceSummary);
        globalConnectionBadge = findViewById(R.id.globalConnectionBadge);
        aiBadge = findViewById(R.id.aiBadge);
        modelBadge = findViewById(R.id.modelBadge);
        schedulerBadge = findViewById(R.id.schedulerBadge);
        focusTitle = findViewById(R.id.focusTitle);
        focusStatus = findViewById(R.id.focusStatus);
        focusCounter = findViewById(R.id.focusCounter);
        connectAllButton = findViewById(R.id.connectAllButton);
        aiButton = findViewById(R.id.aiButton);
        focusPreviousButton = findViewById(R.id.focusPreviousButton);
        focusNextButton = findViewById(R.id.focusNextButton);
    }

    private void rebuildMosaic() {
        exitFocusMode();
        releaseFeeds();
        sourcesGrid.removeAllViews();
        List<CameraConfig> configured = sourceStore.loadAll();
        List<CameraConfig> enabled = new ArrayList<>();
        for (CameraConfig source : configured) if (source.enabled) enabled.add(source);

        updateMosaicColumns();
        for (CameraConfig source : enabled) {
            FeedController feed = new FeedController(source);
            feeds.add(feed);
            sourcesGrid.addView(feed.root, newMosaicParams());
        }
        mainEmptyState.setVisibility(feeds.isEmpty() ? View.VISIBLE : View.GONE);
        sourceSummary.setText(feeds.isEmpty() ? "Mosaico sin fuentes"
                : feeds.size() == 1 ? "1 fuente · pantalla completa"
                : feeds.size() + " fuentes simultáneas · doble toque para ampliar");
        updateGlobalStatus();
        if (!feeds.isEmpty() && !allStopped && activityVisible) startAll();
        if (feeds.size() == 1) {
            FeedController onlyFeed = feeds.get(0);
            mainHandler.post(() -> {
                if (feeds.size() == 1 && feeds.contains(onlyFeed)) enterFocusMode(onlyFeed);
            });
        }
    }

    private void updateMosaicColumns() {
        int columns = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE
                || getResources().getConfiguration().smallestScreenWidthDp >= 600 ? 2 : 1;
        sourcesGrid.setColumnCount(columns);
        for (FeedController feed : feeds) {
            if (feed.root.getParent() == sourcesGrid) feed.root.setLayoutParams(newMosaicParams());
        }
    }

    private GridLayout.LayoutParams newMosaicParams() {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(5), dp(5), dp(5), dp(5));
        return params;
    }

    private void enterFocusMode(FeedController feed) {
        if (feed == null || !feeds.contains(feed) || focusedFeed == feed) return;
        if (focusedFeed != null) restoreFocusedFeedToGrid();
        focusedFeed = feed;
        ViewGroup parent = (ViewGroup) feed.root.getParent();
        if (parent != null) parent.removeView(feed.root);
        feed.setFocusedStyle(true);
        focusFeedHost.removeAllViews();
        focusFeedHost.addView(feed.root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        focusOverlay.setVisibility(View.VISIBLE);
        updateFocusLabels();
        setImmersiveMode(true);
    }

    private void exitFocusMode() {
        if (focusedFeed == null) return;
        restoreFocusedFeedToGrid();
        focusedFeed = null;
        focusOverlay.setVisibility(View.GONE);
        setImmersiveMode(false);
        updateMosaicColumns();
    }

    private void restoreFocusedFeedToGrid() {
        FeedController feed = focusedFeed;
        if (feed == null) return;
        ViewGroup parent = (ViewGroup) feed.root.getParent();
        if (parent != null) parent.removeView(feed.root);
        feed.setFocusedStyle(false);
        int insertAt = 0;
        int targetPosition = feeds.indexOf(feed);
        for (int index = 0; index < targetPosition; index++) {
            if (feeds.get(index).root.getParent() == sourcesGrid) insertAt++;
        }
        sourcesGrid.addView(feed.root, Math.min(insertAt, sourcesGrid.getChildCount()),
                newMosaicParams());
    }

    private void switchFocusedFeed(int direction) {
        if (focusedFeed == null || feeds.size() < 2) return;
        int current = feeds.indexOf(focusedFeed);
        int next = (current + direction + feeds.size()) % feeds.size();
        enterFocusMode(feeds.get(next));
    }

    private void updateFocusLabels() {
        if (focusedFeed == null) return;
        int index = feeds.indexOf(focusedFeed);
        focusTitle.setText(focusedFeed.config.name);
        focusCounter.setText((index + 1) + " / " + feeds.size());
        String value;
        int color;
        if (focusedFeed.state == FeedController.READY) {
            value = "● EN VIVO";
            color = R.color.primary;
        } else if (focusedFeed.state == FeedController.CONNECTING) {
            value = "● CONECTANDO";
            color = R.color.warning;
        } else if (focusedFeed.state == FeedController.ERROR) {
            value = "● ERROR";
            color = R.color.danger;
        } else {
            value = "● DETENIDA";
            color = R.color.text_secondary;
        }
        focusStatus.setText(value);
        focusStatus.setTextColor(getColor(color));
        boolean multiple = feeds.size() > 1;
        focusPreviousButton.setVisibility(multiple ? View.VISIBLE : View.INVISIBLE);
        focusNextButton.setVisibility(multiple ? View.VISIBLE : View.INVISIBLE);
        ((TextView) findViewById(R.id.focusHint)).setText(multiple
                ? "Desliza para cambiar\nDoble toque para volver"
                : "Doble toque para mostrar controles");
    }

    private void setImmersiveMode(boolean enabled) {
        View decor = getWindow().getDecorView();
        if (enabled) {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private void initDetector() {
        ModelSpec target = modelStore.active();
        if (modelReady && target.id.equals(loadedModelId)) return;
        if (!modelLoading.compareAndSet(false, true)) return;
        modelReady = false;
        modelBadge.setText("Cargando " + target.name + "…");
        inferenceExecutor.execute(() -> {
            try {
                YoloDetector next = new YoloDetector(getApplicationContext(), target);
                YoloDetector previous = detector;
                detector = next;
                loadedModelId = target.id;
                modelReady = true;
                if (previous != null) {
                    try { previous.close(); } catch (Exception ignored) { }
                }
                mainHandler.post(() -> {
                    aiBadge.setText(aiEnabled ? "IA ACTIVA" : "IA PAUSADA");
                    aiBadge.setTextColor(getColor(aiEnabled ? R.color.primary : R.color.text_secondary));
                    modelBadge.setText(target.name + " · listo");
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    aiBadge.setText("IA NO DISPONIBLE");
                    aiBadge.setTextColor(getColor(R.color.danger));
                    modelBadge.setText(error.getClass().getSimpleName());
                    Toast.makeText(this, "Falló la carga del modelo activo", Toast.LENGTH_LONG).show();
                });
            } finally {
                modelLoading.set(false);
            }
        });
    }

    private void toggleAll() {
        boolean active = false;
        for (FeedController feed : feeds) if (feed.isActive()) active = true;
        if (active) {
            allStopped = true;
            for (FeedController feed : feeds) feed.stop(true);
        } else {
            allStopped = false;
            startAll();
        }
        updateGlobalStatus();
    }

    private void startAll() {
        allStopped = false;
        for (FeedController feed : feeds) feed.start();
        updateGlobalStatus();
    }

    private void toggleAi() {
        aiEnabled = !aiEnabled;
        aiButton.setText(aiEnabled ? "◇\nIA activa" : "◇\nIA pausada");
        aiBadge.setText(aiEnabled ? (modelReady ? "IA ACTIVA" : "IA PREPARANDO") : "IA PAUSADA");
        aiBadge.setTextColor(getColor(aiEnabled ? (modelReady ? R.color.primary : R.color.warning)
                : R.color.text_secondary));
        if (!aiEnabled) for (FeedController feed : feeds) feed.overlay.clear();
    }

    private FeedController nextInferenceFeed() {
        if (feeds.isEmpty()) return null;
        for (int offset = 0; offset < feeds.size(); offset++) {
            int index = (roundRobinIndex + offset) % feeds.size();
            FeedController candidate = feeds.get(index);
            if (candidate.config.detectEnabled && candidate.isReady()) {
                roundRobinIndex = (index + 1) % feeds.size();
                return candidate;
            }
        }
        return null;
    }

    private void analyze(FeedController feed, Bitmap frame) {
        inferenceExecutor.execute(() -> {
            long started = System.nanoTime();
            try {
                List<Detection> detections = detector.detect(frame, feed.config.confidence);
                List<Detection> visible = eventRulesStore.filterForDisplay(detections);
                long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
                mainHandler.post(() -> {
                    if (!feeds.contains(feed)) return;
                    feed.overlay.setDetections(visible, frame.getWidth(), frame.getHeight());
                    feed.inferenceText.setText(visible.size() + " obj · " + elapsedMs + " ms");
                });
                maybeSaveEvent(feed, frame, visible);
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (feeds.contains(feed)) feed.inferenceText.setText("Error de inferencia");
                });
            } finally {
                frame.recycle();
                inferenceRunning.set(false);
            }
        });
    }

    private void maybeSaveEvent(FeedController feed, Bitmap frame, List<Detection> detections) {
        List<Detection> selected = eventRulesStore.filterForEvent(detections);
        if (selected.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - feed.lastEventAt < EVENT_COOLDOWN_MS) return;
        feed.lastEventAt = now;
        try {
            EventRecord event = eventStore.save(frame, selected, feed.config.name);
            if (feed.config.personAlerts && eventRulesStore.shouldNotify(selected)) {
                NotificationHelper.notifyEvent(this, event);
            }
        } catch (Exception ignored) { }
    }

    private void captureToGallery(FeedController feed) {
        Bitmap frame = feed.captureFrame(1600);
        if (frame == null) {
            Toast.makeText(this, "No hay un fotograma disponible en " + feed.config.name,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        inferenceExecutor.execute(() -> {
            try {
                String name = "Centinela_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                        .format(new Date()) + ".jpg";
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/CentinelaIP");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                ContentResolver resolver = getContentResolver();
                Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("No se pudo crear la imagen");
                try (OutputStream output = resolver.openOutputStream(uri)) {
                    if (output == null || !frame.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                        throw new IllegalStateException("No se pudo guardar la imagen");
                    }
                }
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
                mainHandler.post(() -> Toast.makeText(this,
                        "Captura de " + feed.config.name + " guardada", Toast.LENGTH_LONG).show());
            } catch (Exception error) {
                mainHandler.post(() -> Toast.makeText(this,
                        "No fue posible guardar la captura", Toast.LENGTH_LONG).show());
            } finally {
                frame.recycle();
            }
        });
    }

    private Uri authenticatedUri(CameraConfig config) {
        Uri source = Uri.parse(config.rtspUrl);
        if (config.username.isEmpty()) return source;
        String host = source.getHost();
        if (host == null) return source;
        if (host.contains(":")) host = "[" + host + "]";
        String credentials = Uri.encode(config.username) + ":" + Uri.encode(config.password);
        String authority = credentials + "@" + host
                + (source.getPort() >= 0 ? ":" + source.getPort() : "");
        return source.buildUpon().encodedAuthority(authority).build();
    }

    private void updateGlobalStatus() {
        int ready = 0;
        int connecting = 0;
        int errors = 0;
        int active = 0;
        int aiSources = 0;
        for (FeedController feed : feeds) {
            if (feed.state == FeedController.READY) ready++;
            if (feed.state == FeedController.CONNECTING) connecting++;
            if (feed.state == FeedController.ERROR) errors++;
            if (feed.isActive()) active++;
            if (feed.config.detectEnabled) aiSources++;
        }
        schedulerBadge.setText(aiSources + (aiSources == 1 ? " fuente con IA" : " fuentes con IA"));
        if (feeds.isEmpty()) {
            globalConnectionBadge.setText("● SIN FUENTES");
            globalConnectionBadge.setTextColor(getColor(R.color.text_secondary));
        } else if (ready > 0) {
            globalConnectionBadge.setText("● " + ready + " EN VIVO");
            globalConnectionBadge.setTextColor(getColor(R.color.primary));
        } else if (connecting > 0) {
            globalConnectionBadge.setText("● CONECTANDO " + connecting);
            globalConnectionBadge.setTextColor(getColor(R.color.warning));
        } else if (errors > 0) {
            globalConnectionBadge.setText("● " + errors + " CON ERROR");
            globalConnectionBadge.setTextColor(getColor(R.color.danger));
        } else {
            globalConnectionBadge.setText("● DETENIDAS");
            globalConnectionBadge.setTextColor(getColor(R.color.text_secondary));
        }
        connectAllButton.setText(active > 0 ? "■\nDetener todas" : "▶\nConectar todas");
        if (ready > 0) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateFocusLabels();
    }

    private String readablePlaybackError(PlaybackException error) {
        int code = error.errorCode;
        if (code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                || code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) {
            return "La cámara no responde en esta red";
        }
        if (code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                || code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED) {
            return "Flujo incompatible; usa H.264 o el subflujo";
        }
        return "Revisa la ruta RTSP y las credenciales";
    }

    private void openSources() {
        startActivityForResult(new Intent(this, SourcesActivity.class), SOURCES_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SOURCES_REQUEST) {
            allStopped = false;
            rebuildMosaic();
            initDetector();
        }
    }

    private void requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != CAMERA_REQUEST) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            for (FeedController feed : feeds) {
                if (feed.config.usesPhoneCamera()) feed.onCameraPermissionGranted();
            }
        } else {
            for (FeedController feed : feeds) {
                if (feed.config.usesPhoneCamera()) feed.showError("Permiso de cámara no concedido");
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityVisible = true;
        initDetector();
        for (FeedController feed : feeds) feed.resume();
        if (!allStopped) startAll();
        mainHandler.removeCallbacks(inferenceLoop);
        mainHandler.post(inferenceLoop);
    }

    @Override
    protected void onPause() {
        activityVisible = false;
        mainHandler.removeCallbacks(inferenceLoop);
        for (FeedController feed : feeds) feed.pause();
        super.onPause();
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (focusedFeed == null) updateMosaicColumns();
        else {
            updateFocusLabels();
            setImmersiveMode(true);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && focusedFeed != null) setImmersiveMode(true);
    }

    @Override
    public void onBackPressed() {
        if (focusedFeed != null) exitFocusMode();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        releaseFeeds();
        inferenceExecutor.shutdownNow();
        YoloDetector value = detector;
        if (value != null) {
            try { value.close(); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    private void releaseFeeds() {
        for (FeedController feed : new ArrayList<>(feeds)) feed.release();
        feeds.clear();
        if (cameraProvider != null) cameraProvider.unbindAll();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class FeedController {
        static final int IDLE = 0;
        static final int CONNECTING = 1;
        static final int READY = 2;
        static final int ERROR = 3;

        final CameraConfig config;
        final View root;
        final View header;
        final View actions;
        final FrameLayout videoContainer;
        final PlayerView playerView;
        final PreviewView phonePreview;
        final DetectionOverlayView overlay;
        final TextView statusText;
        final TextView messageText;
        final TextView inferenceText;
        final Button connectButton;
        ExoPlayer player;
        int state = IDLE;
        int reconnectAttempts;
        boolean userStopped;
        boolean released;
        boolean phoneActive;
        long lastEventAt;

        FeedController(CameraConfig config) {
            this.config = config;
            root = LayoutInflater.from(MainActivity.this).inflate(R.layout.view_source_tile, sourcesGrid, false);
            ((TextView) root.findViewById(R.id.tileName)).setText(config.name);
            ((TextView) root.findViewById(R.id.tileSource)).setText(config.shortDescription());
            header = root.findViewById(R.id.tileHeader);
            actions = root.findViewById(R.id.tileActions);
            videoContainer = root.findViewById(R.id.tileVideoContainer);
            statusText = root.findViewById(R.id.tileStatus);
            messageText = root.findViewById(R.id.tileMessage);
            inferenceText = root.findViewById(R.id.tileInference);
            playerView = root.findViewById(R.id.tilePlayerView);
            phonePreview = root.findViewById(R.id.tilePhonePreview);
            overlay = root.findViewById(R.id.tileOverlay);
            connectButton = root.findViewById(R.id.tileConnectButton);
            connectButton.setOnClickListener(view -> {
                if (isActive()) stop(true);
                else {
                    allStopped = false;
                    start();
                }
            });
            root.findViewById(R.id.tileCaptureButton).setOnClickListener(view -> captureToGallery(this));
            GestureDetector gestures = new GestureDetector(MainActivity.this,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override public boolean onDown(MotionEvent event) {
                            return true;
                        }

                        @Override public boolean onDoubleTap(MotionEvent event) {
                            if (focusedFeed == FeedController.this) exitFocusMode();
                            else enterFocusMode(FeedController.this);
                            return true;
                        }

                        @Override public boolean onFling(MotionEvent first, MotionEvent second,
                                                         float velocityX, float velocityY) {
                            if (focusedFeed != FeedController.this || feeds.size() < 2
                                    || first == null || second == null) return false;
                            float deltaX = second.getX() - first.getX();
                            float deltaY = second.getY() - first.getY();
                            if (Math.abs(deltaX) < dp(70) || Math.abs(deltaX) <= Math.abs(deltaY)
                                    || Math.abs(velocityX) < 350f) return false;
                            switchFocusedFeed(deltaX < 0 ? 1 : -1);
                            return true;
                        }
                    });
            View.OnTouchListener touchListener = (view, event) -> gestures.onTouchEvent(event);
            videoContainer.setOnTouchListener(touchListener);
            overlay.setOnTouchListener(touchListener);
            messageText.setOnTouchListener(touchListener);
            if (!config.detectEnabled) inferenceText.setText("YOLO desactivado");
            showIdle(config.isConfigured() ? "Lista para conectar"
                    : "UUID guardado. Pulsa Fuentes y ejecuta Detectar cámara.");
        }

        void setFocusedStyle(boolean focused) {
            header.setVisibility(focused ? View.GONE : View.VISIBLE);
            actions.setVisibility(focused ? View.GONE : View.VISIBLE);
            root.setPadding(focused ? 0 : dp(8), focused ? 0 : dp(8),
                    focused ? 0 : dp(8), focused ? 0 : dp(8));
            if (focused) {
                root.setBackgroundColor(getColor(android.R.color.black));
                videoContainer.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            } else {
                root.setBackgroundResource(R.drawable.bg_card);
                videoContainer.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(210)));
            }
        }

        void start() {
            if (released || state == CONNECTING || state == READY) return;
            userStopped = false;
            reconnectAttempts = 0;
            overlay.clear();
            if (!config.isConfigured()) {
                showError(config.usesHcam()
                        ? "No hay flujo local. Conecta al Wi‑Fi HCAM y pulsa Detectar cámara."
                        : "La fuente no está configurada");
                return;
            }
            if (config.usesPhoneCamera()) startPhone();
            else startRtsp();
        }

        void onCameraPermissionGranted() {
            if (released || userStopped) return;
            // La solicitud dejó la fuente en CONNECTING; vuelve a IDLE para que
            // start() no descarte el arranque cuando Android entrega el permiso.
            state = IDLE;
            start();
        }

        private void startRtsp() {
            phonePreview.setVisibility(View.GONE);
            playerView.setVisibility(View.VISIBLE);
            showConnecting("Abriendo " + (config.forceTcp ? "RTSP/TCP" : "RTSP/UDP") + "…");
            try {
                if (player == null) {
                    player = new ExoPlayer.Builder(MainActivity.this).build();
                    playerView.setPlayer(player);
                    player.addListener(new Player.Listener() {
                        @Override public void onPlaybackStateChanged(int playbackState) {
                            if (released) return;
                            if (playbackState == Player.STATE_BUFFERING) showConnecting("Negociando video…");
                            else if (playbackState == Player.STATE_READY) showReady();
                            else if (playbackState == Player.STATE_ENDED && !userStopped) scheduleReconnect();
                        }

                        @Override public void onPlayerError(PlaybackException error) {
                            if (released) return;
                            showError(readablePlaybackError(error));
                            scheduleReconnect();
                        }
                    });
                }
                RtspMediaSource.Factory sourceFactory = new RtspMediaSource.Factory()
                        .setForceUseRtpTcp(config.forceTcp)
                        .setTimeoutMs(10_000);
                player.setMediaSource(sourceFactory.createMediaSource(
                        MediaItem.fromUri(authenticatedUri(config))));
                player.prepare();
                player.play();
            } catch (Exception error) {
                showError("URL RTSP inválida");
            }
        }

        private void startPhone() {
            if (player != null) {
                player.stop();
                player.clearMediaItems();
            }
            playerView.setVisibility(View.GONE);
            phonePreview.setVisibility(View.VISIBLE);
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                showConnecting("Esperando permiso para el lente…");
                requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
                return;
            }
            showConnecting("Abriendo lente " + (config.frontCamera ? "frontal" : "trasero") + "…");
            ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(MainActivity.this);
            future.addListener(() -> {
                try {
                    if (released || userStopped) return;
                    ProcessCameraProvider provider = future.get();
                    cameraProvider = provider;
                    provider.unbindAll();
                    Preview preview = new Preview.Builder().build();
                    preview.setSurfaceProvider(phonePreview.getSurfaceProvider());
                    CameraSelector selector = config.frontCamera
                            ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
                    provider.bindToLifecycle(MainActivity.this, selector, preview);
                    phoneActive = true;
                    showReady();
                } catch (Exception error) {
                    phoneActive = false;
                    showError("El lente seleccionado no está disponible");
                }
            }, ContextCompat.getMainExecutor(MainActivity.this));
        }

        void stop(boolean byUser) {
            userStopped = byUser;
            state = IDLE;
            phoneActive = false;
            if (config.usesPhoneCamera() && cameraProvider != null) cameraProvider.unbindAll();
            if (player != null) {
                player.stop();
                player.clearMediaItems();
            }
            overlay.clear();
            showIdle("Fuente detenida");
        }

        void pause() {
            if (player != null && player.isPlaying()) player.pause();
        }

        void resume() {
            if (!userStopped && player != null && player.getMediaItemCount() > 0) player.play();
        }

        void release() {
            released = true;
            userStopped = true;
            if (player != null) {
                playerView.setPlayer(null);
                player.release();
                player = null;
            }
            phoneActive = false;
        }

        boolean isReady() {
            return state == READY && (config.usesPhoneCamera()
                    ? phoneActive : player != null && player.getPlaybackState() == Player.STATE_READY);
        }

        boolean isActive() {
            return state == CONNECTING || state == READY;
        }

        Bitmap captureFrame(int maxDimension) {
            if (!isReady()) return null;
            if (config.usesPhoneCamera()) {
                if (phonePreview.getWidth() <= 0 || phonePreview.getHeight() <= 0) return null;
                Bitmap frame = phonePreview.getBitmap();
                return resize(frame, maxDimension);
            }
            View surface = playerView.getVideoSurfaceView();
            if (!(surface instanceof TextureView)) return null;
            TextureView texture = (TextureView) surface;
            if (!texture.isAvailable() || texture.getWidth() <= 0 || texture.getHeight() <= 0) return null;
            float scale = Math.min(1f,
                    (float) maxDimension / Math.max(texture.getWidth(), texture.getHeight()));
            return texture.getBitmap(Math.max(1, Math.round(texture.getWidth() * scale)),
                    Math.max(1, Math.round(texture.getHeight() * scale)));
        }

        private Bitmap resize(Bitmap frame, int maxDimension) {
            if (frame == null) return null;
            int largest = Math.max(frame.getWidth(), frame.getHeight());
            if (largest <= maxDimension) return frame;
            float scale = (float) maxDimension / largest;
            Bitmap resized = Bitmap.createScaledBitmap(frame,
                    Math.max(1, Math.round(frame.getWidth() * scale)),
                    Math.max(1, Math.round(frame.getHeight() * scale)), true);
            if (resized != frame) frame.recycle();
            return resized;
        }

        private void showIdle(String message) {
            state = IDLE;
            statusText.setText("● DETENIDA");
            statusText.setTextColor(getColor(R.color.text_secondary));
            messageText.setText(message);
            messageText.setVisibility(View.VISIBLE);
            connectButton.setText("Conectar");
            updateGlobalStatus();
        }

        private void showConnecting(String message) {
            state = CONNECTING;
            statusText.setText("● CONECTANDO");
            statusText.setTextColor(getColor(R.color.warning));
            messageText.setText(message);
            messageText.setVisibility(View.VISIBLE);
            connectButton.setText("Detener");
            updateGlobalStatus();
        }

        private void showReady() {
            state = READY;
            reconnectAttempts = 0;
            statusText.setText("● EN VIVO");
            statusText.setTextColor(getColor(R.color.primary));
            messageText.setVisibility(View.GONE);
            connectButton.setText("Detener");
            updateGlobalStatus();
        }

        private void showError(String message) {
            state = ERROR;
            statusText.setText("● ERROR");
            statusText.setTextColor(getColor(R.color.danger));
            messageText.setText(message);
            messageText.setVisibility(View.VISIBLE);
            connectButton.setText("Reintentar");
            updateGlobalStatus();
        }

        private void scheduleReconnect() {
            if (config.usesPhoneCamera() || userStopped || released
                    || !activityVisible || reconnectAttempts >= 3) return;
            reconnectAttempts++;
            int attempt = reconnectAttempts;
            mainHandler.postDelayed(() -> {
                if (released || userStopped || !activityVisible || !feeds.contains(this)) return;
                showConnecting("Reconexión " + attempt + " de 3…");
                try {
                    if (player != null) {
                        player.prepare();
                        player.play();
                    }
                } catch (Exception ignored) { }
            }, 4_000L * attempt);
        }
    }
}
