package com.fm.centinelaip;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TrainingActivity extends Activity {
    private EditText endpointInput, tokenInput, epochsInput, acceptInput, rejectInput;
    private Switch autoCuration, purgeRejected;
    private TextView datasetText, statusText;
    private ProgressBar progress;
    private Button downloadButton;
    private DatasetStore dataset;
    private ModelStore models;
    private TrainingConfigStore configs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private String activeJob = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_training);
        dataset = new DatasetStore(this);
        models = new ModelStore(this);
        configs = new TrainingConfigStore(this);
        bind();
        loadConfig();
        updateStats();
        findViewById(R.id.backButton).setOnClickListener(view -> finish());
        findViewById(R.id.testButton).setOnClickListener(view -> testServer());
        findViewById(R.id.startButton).setOnClickListener(view -> startTraining());
        downloadButton.setOnClickListener(view -> downloadModel());
        if (!activeJob.isEmpty()) pollNow();
    }

    private void bind() {
        endpointInput = findViewById(R.id.endpointInput);
        tokenInput = findViewById(R.id.tokenInput);
        epochsInput = findViewById(R.id.epochsInput);
        acceptInput = findViewById(R.id.acceptInput);
        rejectInput = findViewById(R.id.rejectInput);
        autoCuration = findViewById(R.id.autoCurationSwitch);
        purgeRejected = findViewById(R.id.purgeRejectedSwitch);
        datasetText = findViewById(R.id.datasetText);
        statusText = findViewById(R.id.statusText);
        progress = findViewById(R.id.trainingProgress);
        downloadButton = findViewById(R.id.downloadButton);
    }

    private void loadConfig() {
        TrainingConfig value = configs.load();
        endpointInput.setText(value.endpoint);
        tokenInput.setText(value.token);
        epochsInput.setText(Integer.toString(value.epochs));
        autoCuration.setChecked(value.autoCuration);
        purgeRejected.setChecked(value.purgeRejected);
        acceptInput.setText(String.format(Locale.US, "%.0f", value.acceptAt * 100));
        rejectInput.setText(String.format(Locale.US, "%.0f", value.rejectBelow * 100));
        activeJob = value.jobId;
    }

    private TrainingConfig readConfig(String jobId) {
        int epochs = Integer.parseInt(epochsInput.getText().toString());
        float accept = Float.parseFloat(acceptInput.getText().toString().replace(',', '.')) / 100f;
        float reject = Float.parseFloat(rejectInput.getText().toString().replace(',', '.')) / 100f;
        TrainingConfig value = new TrainingConfig(
                endpointInput.getText().toString(), tokenInput.getText().toString(), epochs,
                autoCuration.isChecked(), purgeRejected.isChecked(), accept, reject, jobId);
        configs.save(value);
        return value;
    }

    private void updateStats() {
        DatasetStore.DatasetStats stats = dataset.stats();
        datasetText.setText(stats.samples + " muestras · " + stats.ready + " cajas listas · "
                + stats.review + " por revisar · " + stats.classes + " clases");
    }

    private void testServer() {
        if (!busy.compareAndSet(false, true)) return;
        TrainingConfig config;
        try { config = readConfig(activeJob); }
        catch (Exception error) { busy.set(false); showError("Revisa URL y parámetros"); return; }
        statusText.setText("Comprobando servidor…");
        io.execute(() -> {
            try {
                String service = new TrainingClient(config.endpoint, config.token).health();
                runOnUiThread(() -> statusText.setText("Servidor disponible · " + service));
            } catch (Exception error) {
                runOnUiThread(() -> showError(error.getMessage()));
            } finally { busy.set(false); }
        });
    }

    private void startTraining() {
        DatasetStore.DatasetStats stats = dataset.stats();
        if (stats.ready == 0) { showError("No hay anotaciones aceptadas"); return; }
        if (!busy.compareAndSet(false, true)) return;
        TrainingConfig config;
        try { config = readConfig(""); }
        catch (Exception error) { busy.set(false); showError("Revisa URL, épocas y umbrales"); return; }
        statusText.setText("Preparando y comprimiendo dataset…");
        progress.setProgress(0);
        io.execute(() -> {
            File upload = new File(getCacheDir(), "training-dataset.zip");
            try (FileOutputStream output = new FileOutputStream(upload)) {
                dataset.exportYolo(output);
                runOnUiThread(() -> statusText.setText("Enviando dataset al servidor…"));
                String id = new TrainingClient(config.endpoint, config.token).start(upload, config.epochs);
                activeJob = id;
                configs.setJobId(id);
                runOnUiThread(() -> {
                    statusText.setText("Trabajo " + id + " iniciado");
                    busy.set(false);
                    pollNow();
                });
            } catch (Exception error) {
                busy.set(false);
                runOnUiThread(() -> showError(error.getMessage()));
            }
        });
    }

    private void pollNow() {
        if (activeJob.isEmpty() || !busy.compareAndSet(false, true)) return;
        TrainingConfig config;
        try { config = readConfig(activeJob); }
        catch (Exception error) { busy.set(false); return; }
        String job = activeJob;
        io.execute(() -> {
            try {
                TrainingClient.JobStatus value = new TrainingClient(config.endpoint, config.token).status(job);
                runOnUiThread(() -> {
                    progress.setProgress(Math.round(value.progress * 1000));
                    statusText.setText(value.status.toUpperCase(Locale.ROOT) + " · "
                            + Math.round(value.progress * 100) + "%\n" + value.message);
                    downloadButton.setEnabled(value.ready());
                    busy.set(false);
                    if (!value.terminal()) handler.postDelayed(this::pollNow, 5_000L);
                });
            } catch (Exception error) {
                busy.set(false);
                runOnUiThread(() -> {
                    statusText.setText("No se pudo consultar el trabajo\n" + error.getMessage());
                    handler.postDelayed(this::pollNow, 10_000L);
                });
            }
        });
    }

    private void downloadModel() {
        if (activeJob.isEmpty() || !busy.compareAndSet(false, true)) return;
        TrainingConfig config;
        try { config = readConfig(activeJob); }
        catch (Exception error) { busy.set(false); return; }
        String job = activeJob;
        statusText.setText("Descargando y validando el mejor modelo…");
        io.execute(() -> {
            File packageFile = new File(getCacheDir(), "trained-model.zip");
            try {
                TrainingClient client = new TrainingClient(config.endpoint, config.token);
                client.download(job, packageFile);
                ModelSpec imported;
                try (FileInputStream input = new FileInputStream(packageFile)) {
                    imported = models.importPackage(input);
                }
                if (config.autoCuration) {
                    runOnUiThread(() -> statusText.setText("Modelo activado. Reevaluando anotaciones…"));
                    try (YoloDetector detector = new YoloDetector(getApplicationContext(), imported)) {
                        dataset.rescore(detector, config.acceptAt, config.rejectBelow,
                                (done, total) -> runOnUiThread(() -> {
                                    progress.setProgress(total == 0 ? 0 : done * 1000 / total);
                                    statusText.setText("Curando dataset · " + done + "/" + total);
                                }));
                    }
                    if (config.purgeRejected) dataset.purgeRejected();
                }
                activeJob = "";
                configs.setJobId("");
                runOnUiThread(() -> {
                    busy.set(false);
                    downloadButton.setEnabled(false);
                    updateStats();
                    statusText.setText(imported.name + " validado y activado\n"
                            + (config.autoCuration
                            ? (config.purgeRejected ? "Dataset reevaluado y baja confianza eliminada"
                            : "Dataset reevaluado; rechazadas conservadas para revisión")
                            : "Curación automática desactivada"));
                    Toast.makeText(this, "Entrenamiento incorporado", Toast.LENGTH_LONG).show();
                    setResult(RESULT_OK);
                });
            } catch (Exception error) {
                busy.set(false);
                runOnUiThread(() -> showError(error.getMessage()));
            }
        });
    }

    private void showError(String message) {
        statusText.setText("ERROR\n" + (message == null ? "Operación fallida" : message));
        Toast.makeText(this, message == null ? "Operación fallida" : message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDestroy();
    }
}
