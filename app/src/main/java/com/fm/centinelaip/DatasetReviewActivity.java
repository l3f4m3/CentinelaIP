package com.fm.centinelaip;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Cola local para revisar, corregir o eliminar muestras antes de entrenar. */
public final class DatasetReviewActivity extends Activity {
    private DatasetStore dataset;
    private LinearLayout container;
    private TextView statsText;
    private Switch pendingOnly;
    private final ExecutorService thumbnails = Executors.newSingleThreadExecutor();
    private final List<Bitmap> loadedThumbnails = new ArrayList<>();
    private int renderGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dataset_review);
        dataset = new DatasetStore(this);
        container = findViewById(R.id.samplesContainer);
        statsText = findViewById(R.id.reviewStatsText);
        pendingOnly = findViewById(R.id.pendingOnlySwitch);
        findViewById(R.id.backButton).setOnClickListener(view -> finish());
        findViewById(R.id.purgeRejectedButton).setOnClickListener(view -> confirmPurge());
        pendingOnly.setOnCheckedChangeListener((button, checked) -> render());
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        int generation = ++renderGeneration;
        for (Bitmap bitmap : loadedThumbnails) {
            if (!bitmap.isRecycled()) bitmap.recycle();
        }
        loadedThumbnails.clear();
        container.removeAllViews();
        DatasetStore.DatasetStats stats = dataset.stats();
        statsText.setText(stats.samples + " muestras · " + stats.ready + " listas · "
                + stats.review + " por revisar · " + stats.rejected + " rechazadas");
        List<AnnotationSample> samples = dataset.list();
        int shown = 0;
        for (AnnotationSample sample : samples) {
            if (pendingOnly.isChecked() && !needsAttention(sample)) continue;
            View card = createCard(sample, generation);
            container.addView(card);
            Space space = new Space(this);
            container.addView(space, new LinearLayout.LayoutParams(1, dp(10)));
            shown++;
        }
        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText(pendingOnly.isChecked()
                    ? "No hay muestras pendientes de revisión"
                    : "Todavía no hay muestras anotadas");
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setTextSize(15f);
            empty.setPadding(dp(20), dp(70), dp(20), dp(30));
            container.addView(empty);
        }
    }

    private View createCard(AnnotationSample sample, int generation) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.setBackgroundResource(R.drawable.bg_card);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(getColor(R.color.surface_alt));
        image.setTag(sample.id);
        row.addView(image, new LinearLayout.LayoutParams(dp(116), dp(88)));

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(12), 0, 0, 0);
        TextView title = text(labels(sample), 15f, R.color.text_primary, true);
        details.addView(title);
        String date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(sample.createdAt));
        details.addView(text(date + " · " + counts(sample), 12f,
                R.color.text_secondary, false));
        String origin = sample.timestampUs >= 0
                ? "video · " + formatTime(sample.timestampUs) : "imagen";
        details.addView(text(origin, 11f, R.color.text_secondary, false));
        row.addView(details, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(row);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, 0);
        Button edit = action("Editar con precisión", R.color.primary);
        edit.setOnClickListener(view -> edit(sample.id));
        Button delete = action("Borrar muestra", R.color.danger);
        delete.setOnClickListener(view -> confirmDelete(sample));
        actions.addView(edit, new LinearLayout.LayoutParams(0, dp(44), 1.3f));
        actions.addView(delete, new LinearLayout.LayoutParams(0, dp(44), 1f));
        card.addView(actions);
        image.setOnClickListener(view -> edit(sample.id));

        File file = dataset.imageFile(sample);
        thumbnails.execute(() -> {
            Bitmap bitmap = decodeThumbnail(file, 240);
            runOnUiThread(() -> {
                if (generation != renderGeneration || isFinishing()) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (sample.id.equals(image.getTag())) {
                    image.setImageBitmap(bitmap);
                    if (bitmap != null) loadedThumbnails.add(bitmap);
                }
                else if (bitmap != null) bitmap.recycle();
            });
        });
        return card;
    }

    private void edit(String id) {
        Intent intent = new Intent(this, AnnotationActivity.class);
        intent.putExtra(AnnotationActivity.EXTRA_SAMPLE_ID, id);
        startActivity(intent);
    }

    private void confirmDelete(AnnotationSample sample) {
        new AlertDialog.Builder(this)
                .setTitle("Borrar muestra")
                .setMessage("Se eliminarán la imagen guardada y todas sus cajas. No se puede deshacer.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Borrar", (dialog, which) -> {
                    if (!dataset.deleteSample(sample.id)) {
                        Toast.makeText(this, "No se pudo borrar la muestra", Toast.LENGTH_LONG).show();
                    }
                    render();
                }).show();
    }

    private void confirmPurge() {
        DatasetStore.DatasetStats stats = dataset.stats();
        if (stats.rejected == 0) {
            Toast.makeText(this, "No hay cajas rechazadas", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Eliminar baja confianza")
                .setMessage("Se borrarán definitivamente " + stats.rejected
                        + " cajas rechazadas. Revisa la cola antes si quieres corregirlas.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Eliminar", (dialog, which) -> {
                    DatasetStore.PurgeResult result = dataset.purgeRejected();
                    Toast.makeText(this, result.boxes + " cajas eliminadas · "
                            + result.samples + " muestras vacías retiradas", Toast.LENGTH_LONG).show();
                    render();
                }).show();
    }

    private static boolean needsAttention(AnnotationSample sample) {
        for (AnnotationBox box : sample.boxes) {
            if (AnnotationBox.REVIEW.equals(box.state)
                    || AnnotationBox.REJECTED.equals(box.state)) return true;
        }
        return false;
    }

    private static String labels(AnnotationSample sample) {
        Set<String> values = new LinkedHashSet<>();
        for (AnnotationBox box : sample.boxes) if (!box.label.isEmpty()) values.add(box.label);
        if (values.isEmpty()) return "Sin etiquetas";
        String joined = android.text.TextUtils.join(", ", values);
        return joined.length() > 46 ? joined.substring(0, 43) + "…" : joined;
    }

    private static String counts(AnnotationSample sample) {
        int ready = 0, review = 0, rejected = 0;
        for (AnnotationBox box : sample.boxes) {
            if (box.isTrainingReady()) ready++;
            else if (AnnotationBox.REVIEW.equals(box.state)) review++;
            else rejected++;
        }
        return ready + " listas · " + review + " revisar · " + rejected + " rechazadas";
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(getColor(color));
        text.setTextSize(size);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private Button action(String value, int color) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextColor(getColor(color));
        button.setTextSize(12f);
        button.setBackgroundResource(R.drawable.bg_button_secondary);
        return button;
    }

    private static Bitmap decodeThumbnail(File file, int maximum) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > maximum * 2) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap raw = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (raw == null) return null;
        int largest = Math.max(raw.getWidth(), raw.getHeight());
        if (largest <= maximum) return raw;
        float scale = (float) maximum / largest;
        Bitmap resized = Bitmap.createScaledBitmap(raw,
                Math.max(1, Math.round(raw.getWidth() * scale)),
                Math.max(1, Math.round(raw.getHeight() * scale)), true);
        if (resized != raw) raw.recycle();
        return resized;
    }

    private static String formatTime(long timestampUs) {
        long totalMs = Math.max(0, timestampUs / 1000L);
        return String.format(java.util.Locale.US, "%02d:%06.3f",
                totalMs / 60_000L, (totalMs % 60_000L) / 1000f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        renderGeneration++;
        thumbnails.shutdownNow();
        for (Bitmap bitmap : loadedThumbnails) {
            if (!bitmap.isRecycled()) bitmap.recycle();
        }
        loadedThumbnails.clear();
        super.onDestroy();
    }
}
