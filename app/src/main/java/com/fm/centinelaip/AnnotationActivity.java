package com.fm.centinelaip;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.exifinterface.media.ExifInterface;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class AnnotationActivity extends Activity {
    public static final String EXTRA_SAMPLE_ID = "sample_id";
    private static final int OPEN_IMAGE = 401;
    private static final int OPEN_VIDEO = 402;
    private static final int EXPORT_DATASET = 403;

    private AnnotationCanvasView canvas;
    private TextView sourceText;
    private TextView datasetText;
    private TextView videoTimeText;
    private SeekBar videoSeek;
    private LinearLayout videoControls;
    private Button autoButton;
    private Button panButton;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicInteger frameRequest = new AtomicInteger();
    private DatasetStore dataset;
    private ModelSpec activeModel;
    private volatile YoloDetector detector;
    private Bitmap currentBitmap;
    private String sourceKey = "unknown";
    private long currentTimeUs = -1L;
    private long durationUs;
    private long frameStepUs = 33_333L;
    private MediaMetadataRetriever retriever;
    private boolean panMode;
    private boolean videoSource;
    private String editingSampleId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_annotation);
        dataset = new DatasetStore(this);
        activeModel = new ModelStore(this).active();
        canvas = findViewById(R.id.annotationCanvas);
        sourceText = findViewById(R.id.sourceText);
        datasetText = findViewById(R.id.datasetText);
        videoTimeText = findViewById(R.id.videoTimeText);
        videoSeek = findViewById(R.id.videoSeek);
        videoControls = findViewById(R.id.videoControls);
        autoButton = findViewById(R.id.autoButton);
        panButton = findViewById(R.id.panButton);
        ((TextView) findViewById(R.id.modelText)).setText(activeModel.name);

        findViewById(R.id.backButton).setOnClickListener(view -> finish());
        findViewById(R.id.openImageButton).setOnClickListener(view -> choose("image/*", OPEN_IMAGE));
        findViewById(R.id.openVideoButton).setOnClickListener(view -> choose("video/*", OPEN_VIDEO));
        autoButton.setOnClickListener(view -> autoAnnotate());
        panButton.setOnClickListener(view -> togglePan());
        findViewById(R.id.editButton).setOnClickListener(view -> editSelected());
        findViewById(R.id.acceptButton).setOnClickListener(view ->
                canvas.setSelectedState(AnnotationBox.ACCEPTED));
        findViewById(R.id.rejectButton).setOnClickListener(view ->
                canvas.setSelectedState(AnnotationBox.REJECTED));
        findViewById(R.id.deleteButton).setOnClickListener(view -> canvas.deleteSelected());
        findViewById(R.id.undoButton).setOnClickListener(view -> canvas.undo());
        findViewById(R.id.resetZoomButton).setOnClickListener(view -> canvas.resetView());
        findViewById(R.id.saveSampleButton).setOnClickListener(view -> saveSample());
        findViewById(R.id.reviewDatasetButton).setOnClickListener(view ->
                startActivity(new Intent(this, DatasetReviewActivity.class)));
        findViewById(R.id.curateButton).setOnClickListener(view -> showCurationDialog());
        findViewById(R.id.exportDatasetButton).setOnClickListener(view -> chooseDatasetExport());
        findViewById(R.id.previousFrameButton).setOnClickListener(view -> stepFrame(-1));
        findViewById(R.id.nextFrameButton).setOnClickListener(view -> stepFrame(1));

        canvas.setListener(new AnnotationCanvasView.Listener() {
            @Override public void onBoxCreated(int index) { editSelected(); }
            @Override public void onSelectionChanged(int index) { updateSelectionHint(); }
        });
        videoSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) updateTimeText(durationUs * progress / 1000L);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                requestVideoFrame(durationUs * seekBar.getProgress() / 1000L);
            }
        });
        updateStats();
        loadDetector();
        String savedSample = getIntent().getStringExtra(EXTRA_SAMPLE_ID);
        if (savedSample != null && !savedSample.isEmpty()) loadSavedSample(savedSample);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (dataset != null) updateStats();
    }

    private void loadDetector() {
        io.execute(() -> {
            try {
                detector = new YoloDetector(getApplicationContext(), activeModel);
                runOnUiThread(() -> autoButton.setEnabled(true));
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this,
                        "El modelo activo no pudo cargarse", Toast.LENGTH_LONG).show());
            }
        });
    }

    private void choose(String type, int request) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(type);
        startActivityForResult(intent, request);
    }

    private void chooseDatasetExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, "Centinela-dataset-YOLO.zip");
        startActivityForResult(intent, EXPORT_DATASET);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == OPEN_IMAGE) loadImage(uri);
        if (requestCode == OPEN_VIDEO) loadVideo(uri);
        if (requestCode == EXPORT_DATASET) exportDataset(uri);
    }

    private void loadImage(Uri uri) {
        editingSampleId = null;
        videoSource = false;
        frameRequest.incrementAndGet();
        videoControls.setVisibility(View.GONE);
        currentTimeUs = -1L;
        sourceKey = "image:" + uri;
        sourceText.setText("Cargando " + displayName(uri) + "…");
        io.execute(() -> {
            try {
                if (retriever != null) {
                    retriever.release();
                    retriever = null;
                }
                Bitmap bitmap = decodeImage(uri, 2400);
                runOnUiThread(() -> showBitmap(bitmap, displayName(uri)));
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this,
                        "No se pudo abrir la imagen", Toast.LENGTH_LONG).show());
            }
        });
    }

    private void loadVideo(Uri uri) {
        editingSampleId = null;
        videoSource = true;
        sourceKey = "video:" + uri;
        sourceText.setText("Preparando " + displayName(uri) + "…");
        int request = frameRequest.incrementAndGet();
        io.execute(() -> {
            try {
                if (retriever != null) retriever.release();
                retriever = new MediaMetadataRetriever();
                retriever.setDataSource(this, uri);
                String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                durationUs = Math.max(0L, Long.parseLong(duration == null ? "0" : duration) * 1000L);
                String fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
                if (fps != null) {
                    float rate = Float.parseFloat(fps);
                    if (rate >= 1f && rate <= 240f) frameStepUs = Math.round(1_000_000f / rate);
                }
                runOnUiThread(() -> {
                    videoControls.setVisibility(View.VISIBLE);
                    sourceText.setText(displayName(uri) + " · paso "
                            + String.format(Locale.US, "%.1f ms", frameStepUs / 1000f));
                });
                requestVideoFrameInternal(0L, request);
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this,
                        "No se pudo abrir el video", Toast.LENGTH_LONG).show());
            }
        });
    }

    private void requestVideoFrame(long timestampUs) {
        long bounded = Math.max(0L, Math.min(durationUs, timestampUs));
        int request = frameRequest.incrementAndGet();
        io.execute(() -> requestVideoFrameInternal(bounded, request));
    }

    private void requestVideoFrameInternal(long timestampUs, int request) {
        try {
            if (request != frameRequest.get() || retriever == null) return;
            Bitmap raw = retriever.getFrameAtTime(timestampUs, MediaMetadataRetriever.OPTION_CLOSEST);
            if (raw == null) throw new IllegalStateException("Fotograma no disponible");
            Bitmap frame = scaleDown(raw, 2200);
            if (request != frameRequest.get()) { frame.recycle(); return; }
            runOnUiThread(() -> {
                currentTimeUs = timestampUs;
                showBitmap(frame, "Fotograma " + formatTime(timestampUs));
                videoSeek.setProgress(durationUs == 0 ? 0 : (int) (timestampUs * 1000L / durationUs));
                updateTimeText(timestampUs);
            });
        } catch (Exception error) {
            runOnUiThread(() -> Toast.makeText(this,
                    "No se pudo extraer ese fotograma", Toast.LENGTH_SHORT).show());
        }
    }

    private void stepFrame(int direction) {
        if (retriever == null) return;
        requestVideoFrame(currentTimeUs + direction * frameStepUs);
    }

    private void showBitmap(Bitmap bitmap, String title) {
        Bitmap previous = currentBitmap;
        currentBitmap = bitmap;
        canvas.setBitmap(bitmap);
        sourceText.setText(title + " · dibuja una caja o usa Sugerir con IA");
        if (previous != null && previous != bitmap && !previous.isRecycled()) previous.recycle();
    }

    private void loadSavedSample(String id) {
        editingSampleId = id;
        videoSource = false;
        videoControls.setVisibility(View.GONE);
        sourceText.setText("Cargando muestra guardada…");
        io.execute(() -> {
            AnnotationSample sample = dataset.find(id);
            Bitmap bitmap = sample == null ? null
                    : BitmapFactory.decodeFile(dataset.imageFile(sample).getAbsolutePath());
            runOnUiThread(() -> {
                if (sample == null || bitmap == null) {
                    Toast.makeText(this, "La muestra ya no existe", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                sourceKey = sample.sourceKey;
                currentTimeUs = sample.timestampUs;
                showBitmap(bitmap, "Muestra guardada");
                canvas.setBoxes(sample.boxes);
                sourceText.setText("Editando muestra · corrige, acepta o rechaza cada caja");
            });
        });
    }

    private void autoAnnotate() {
        if (currentBitmap == null || detector == null) {
            Toast.makeText(this, "Primero abre una imagen o fotograma", Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap input = currentBitmap.copy(Bitmap.Config.ARGB_8888, false);
        List<AnnotationBox> existing = canvas.getBoxes();
        List<AnnotationBox> manual = new ArrayList<>();
        for (AnnotationBox box : existing) if (AnnotationBox.MANUAL.equals(box.origin)) manual.add(box);
        autoButton.setEnabled(false);
        sourceText.setText("Generando sugerencias con " + activeModel.name + "…");
        io.execute(() -> {
            try {
                List<Detection> detections = detector.detect(input, .20f);
                List<AnnotationBox> suggested = new ArrayList<>(manual);
                for (Detection detection : detections) {
                    String state = detection.confidence >= .75f ? AnnotationBox.ACCEPTED
                            : detection.confidence < .35f ? AnnotationBox.REJECTED : AnnotationBox.REVIEW;
                    suggested.add(new AnnotationBox(
                            detection.left / input.getWidth(), detection.top / input.getHeight(),
                            detection.right / input.getWidth(), detection.bottom / input.getHeight(),
                            detection.label, detection.confidence, AnnotationBox.AUTO, state));
                }
                runOnUiThread(() -> {
                    canvas.setBoxes(suggested);
                    sourceText.setText(detections.size() + " sugerencias · toca una caja para renombrarla");
                    autoButton.setEnabled(true);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    autoButton.setEnabled(true);
                    Toast.makeText(this, "Falló el autoetiquetado", Toast.LENGTH_LONG).show();
                });
            } finally {
                input.recycle();
            }
        });
    }

    private void editSelected() {
        AnnotationBox selected = canvas.getSelected();
        if (selected == null) {
            Toast.makeText(this, "Selecciona o dibuja una caja", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(4), dp(20), 0);
        AutoCompleteTextView label = new AutoCompleteTextView(this);
        label.setHint("Nombre de clase, por ejemplo placa");
        label.setSingleLine(true);
        List<String> suggestions = new ArrayList<>(activeModel.labels);
        if (!suggestions.contains("placa")) suggestions.add(0, "placa");
        label.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, suggestions));
        label.setThreshold(0);
        label.setText(selected.label);
        label.setSelection(label.length());
        form.addView(label, new LinearLayout.LayoutParams(-1, dp(54)));

        EditText left = numberField("X izquierda %", selected.left * 100f);
        EditText top = numberField("Y superior %", selected.top * 100f);
        EditText right = numberField("X derecha %", selected.right * 100f);
        EditText bottom = numberField("Y inferior %", selected.bottom * 100f);
        form.addView(pair(left, top));
        form.addView(pair(right, bottom));

        new AlertDialog.Builder(this)
                .setTitle("Etiqueta y coordenadas precisas")
                .setView(form)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Aplicar", (dialog, which) -> {
                    try {
                        String name = label.getText().toString().trim();
                        if (name.isEmpty()) throw new IllegalArgumentException();
                        float l = percent(left), t = percent(top), r = percent(right), b = percent(bottom);
                        if (r <= l || b <= t) throw new IllegalArgumentException();
                        canvas.updateSelected(name, l, t, r, b);
                    } catch (Exception error) {
                        Toast.makeText(this, "Revisa nombre y coordenadas 0–100", Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void saveSample() {
        if (currentBitmap == null) {
            Toast.makeText(this, "No hay imagen para guardar", Toast.LENGTH_SHORT).show();
            return;
        }
        List<AnnotationBox> boxes = canvas.getBoxes();
        if (boxes.isEmpty()) {
            Toast.makeText(this, "Dibuja al menos una caja", Toast.LENGTH_SHORT).show();
            return;
        }
        String sampleId = editingSampleId;
        Bitmap snapshot = sampleId == null
                ? currentBitmap.copy(Bitmap.Config.ARGB_8888, false) : null;
        String key = sourceKey;
        long timestamp = currentTimeUs;
        io.execute(() -> {
            try {
                if (sampleId == null) dataset.save(snapshot, key, timestamp, boxes);
                else if (!dataset.updateAnnotations(sampleId, boxes)) {
                    throw new IllegalStateException("La muestra ya no existe");
                }
                runOnUiThread(() -> {
                    Toast.makeText(this, sampleId == null
                            ? "Anotación guardada" : "Correcciones guardadas", Toast.LENGTH_SHORT).show();
                    updateStats();
                    if (sampleId != null) setResult(RESULT_OK);
                    else if (videoSource) stepFrame(1);
                });
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this,
                        "No se pudo guardar la anotación", Toast.LENGTH_LONG).show());
            } finally {
                if (snapshot != null) snapshot.recycle();
            }
        });
    }

    private void showCurationDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), 0, dp(20), 0);
        EditText accept = numberField("Aceptar automáticamente desde %", 75f);
        EditText reject = numberField("Rechazar automáticamente bajo %", 35f);
        form.addView(accept);
        form.addView(reject);
        TextView note = new TextView(this);
        note.setText("Las anotaciones manuales siempre se conservan. Las intermedias quedan en revisión y las rechazadas se excluyen del entrenamiento sin borrar la imagen.");
        note.setTextColor(getColor(R.color.text_secondary));
        note.setPadding(0, dp(8), 0, 0);
        form.addView(note);
        new AlertDialog.Builder(this)
                .setTitle("Curación de autoaprendizaje")
                .setView(form)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Aplicar", (dialog, which) -> {
                    try {
                        float high = percentRaw(accept) / 100f;
                        float low = percentRaw(reject) / 100f;
                        if (low >= high) throw new IllegalArgumentException();
                        DatasetStore.CurationResult result = dataset.curate(high, low);
                        Toast.makeText(this, result.accepted + " aceptadas · "
                                + result.review + " revisar · " + result.rejected + " rechazadas",
                                Toast.LENGTH_LONG).show();
                        updateStats();
                    } catch (Exception error) {
                        Toast.makeText(this, "El umbral de rechazo debe ser menor", Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void exportDataset(Uri uri) {
        io.execute(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new IllegalStateException();
                DatasetStore.ExportResult result = dataset.exportYolo(output);
                runOnUiThread(() -> Toast.makeText(this, "Dataset exportado: "
                        + result.train + " train · " + result.val + " val · "
                        + result.classes + " clases", Toast.LENGTH_LONG).show());
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this,
                        error.getMessage() == null ? "No se pudo exportar" : error.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void togglePan() {
        panMode = !panMode;
        canvas.setPanMode(panMode);
        panButton.setText(panMode ? "Dibujar cajas" : "Mover vista");
        Toast.makeText(this, panMode ? "Arrastra para mover; pellizca para zoom"
                : "Dibuja o edita cajas", Toast.LENGTH_SHORT).show();
    }

    private void updateSelectionHint() {
        AnnotationBox box = canvas.getSelected();
        if (box == null) return;
        sourceText.setText(box.label + " · " + box.origin + " · " + box.state
                + " · toca Editar caja para coordenadas exactas");
    }

    private void updateStats() {
        DatasetStore.DatasetStats stats = dataset.stats();
        datasetText.setText(stats.samples + " muestras · " + stats.ready + " cajas listas · "
                + stats.review + " revisar · " + stats.rejected + " rechazadas · "
                + stats.classes + " clases");
    }

    private Bitmap decodeImage(Uri uri, int maximum) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(input, null, bounds);
        }
        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > maximum * 1.5f) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(input, null, options);
        }
        if (bitmap == null) throw new IllegalArgumentException("Imagen no compatible");
        Matrix orientationMatrix = new Matrix();
        boolean transform = false;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            ExifInterface exif = new ExifInterface(input);
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            if (orientation == ExifInterface.ORIENTATION_FLIP_HORIZONTAL) {
                orientationMatrix.setScale(-1f, 1f); transform = true;
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) {
                orientationMatrix.setRotate(180f); transform = true;
            } else if (orientation == ExifInterface.ORIENTATION_FLIP_VERTICAL) {
                orientationMatrix.setRotate(180f); orientationMatrix.postScale(-1f, 1f); transform = true;
            } else if (orientation == ExifInterface.ORIENTATION_TRANSPOSE) {
                orientationMatrix.setRotate(90f); orientationMatrix.postScale(-1f, 1f); transform = true;
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_90) {
                orientationMatrix.setRotate(90f); transform = true;
            } else if (orientation == ExifInterface.ORIENTATION_TRANSVERSE) {
                orientationMatrix.setRotate(-90f); orientationMatrix.postScale(-1f, 1f); transform = true;
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) {
                orientationMatrix.setRotate(-90f); transform = true;
            }
        } catch (Exception ignored) { }
        if (transform) {
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                    bitmap.getWidth(), bitmap.getHeight(), orientationMatrix, true);
            if (rotated != bitmap) bitmap.recycle();
            bitmap = rotated;
        }
        return scaleDown(bitmap, maximum);
    }

    private static Bitmap scaleDown(Bitmap source, int maximum) {
        int largest = Math.max(source.getWidth(), source.getHeight());
        if (largest <= maximum) return source;
        float scale = (float) maximum / largest;
        Bitmap resized = Bitmap.createScaledBitmap(source,
                Math.max(1, Math.round(source.getWidth() * scale)),
                Math.max(1, Math.round(source.getHeight() * scale)), true);
        if (resized != source) source.recycle();
        return resized;
    }

    private EditText numberField(String hint, float value) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        field.setText(String.format(Locale.US, "%.3f", value));
        field.setSelectAllOnFocus(true);
        return field;
    }

    private LinearLayout pair(View first, View second) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(first, new LinearLayout.LayoutParams(0, dp(54), 1f));
        row.addView(second, new LinearLayout.LayoutParams(0, dp(54), 1f));
        return row;
    }

    private float percent(EditText field) {
        return Math.max(0f, Math.min(100f, percentRaw(field))) / 100f;
    }

    private float percentRaw(EditText field) {
        return Float.parseFloat(field.getText().toString().replace(',', '.'));
    }

    private void updateTimeText(long timestampUs) { videoTimeText.setText(formatTime(timestampUs)); }

    private static String formatTime(long timestampUs) {
        long totalMs = Math.max(0, timestampUs / 1000L);
        long minutes = totalMs / 60_000L;
        float seconds = (totalMs % 60_000L) / 1000f;
        return String.format(Locale.US, "%02d:%06.3f", minutes, seconds);
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) { }
        return uri.getLastPathSegment() == null ? "archivo" : uri.getLastPathSegment();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        frameRequest.incrementAndGet();
        io.shutdownNow();
        YoloDetector value = detector;
        if (value != null) try { value.close(); } catch (Exception ignored) { }
        if (retriever != null) try { retriever.release(); } catch (Exception ignored) { }
        if (currentBitmap != null && !currentBitmap.isRecycled()) currentBitmap.recycle();
        super.onDestroy();
    }
}
