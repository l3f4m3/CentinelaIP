package com.fm.centinelaip;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public final class SourcesActivity extends Activity {
    private SourceStore store;
    private LinearLayout sourcesList;
    private TextView sourceCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sources);
        store = new SourceStore(this);
        sourcesList = findViewById(R.id.sourcesList);
        sourceCount = findViewById(R.id.sourceCount);

        findViewById(R.id.backButton).setOnClickListener(view -> finishWithChanges());
        findViewById(R.id.doneButton).setOnClickListener(view -> finishWithChanges());
        findViewById(R.id.addSourceButton).setOnClickListener(view -> openEditor("", CameraConfig.SOURCE_RTSP));
        findViewById(R.id.addHcamButton).setOnClickListener(view -> openEditor("", CameraConfig.SOURCE_HCAM));
        findViewById(R.id.modelsButton).setOnClickListener(view ->
                startActivity(new Intent(this, ModelManagerActivity.class)));
        findViewById(R.id.annotationButton).setOnClickListener(view ->
                startActivity(new Intent(this, AnnotationActivity.class)));
        findViewById(R.id.rulesButton).setOnClickListener(view ->
                startActivity(new Intent(this, EventRulesActivity.class)));
        findViewById(R.id.trainingButton).setOnClickListener(view ->
                startActivity(new Intent(this, TrainingActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderSources();
    }

    private void renderSources() {
        List<CameraConfig> sources = store.loadAll();
        sourcesList.removeAllViews();
        int enabled = 0;
        for (CameraConfig source : sources) {
            if (source.enabled) enabled++;
            sourcesList.addView(createCard(source));
        }
        sourceCount.setText(sources.size() + " de " + SourceStore.MAX_SOURCES
                + " configuradas · " + enabled + " activas");
        if (sources.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Todavía no hay fuentes. Añade la HCAM, una URL RTSP o el lente del teléfono.");
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setTextSize(14f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(48), dp(24), dp(48));
            sourcesList.addView(empty);
        }
    }

    private View createCard(CameraConfig source) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, dp(5), 0, dp(5));
        card.setLayoutParams(cardParams);

        LinearLayout headline = new LinearLayout(this);
        headline.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(source.name);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(16f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        headline.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Switch active = new Switch(this);
        active.setText(source.enabled ? "Activa" : "Pausada");
        active.setTextColor(getColor(R.color.text_secondary));
        active.setChecked(source.enabled);
        active.setThumbTintList(getColorStateList(R.color.primary));
        active.setOnCheckedChangeListener((button, checked) -> {
            store.setEnabled(source.id, checked);
            renderSources();
        });
        headline.addView(active);
        card.addView(headline);

        TextView description = new TextView(this);
        description.setText(source.shortDescription());
        description.setTextColor(getColor(R.color.text_secondary));
        description.setTextSize(12f);
        description.setMaxLines(2);
        description.setPadding(0, dp(5), 0, dp(7));
        card.addView(description);

        TextView detection = new TextView(this);
        detection.setText(source.detectEnabled
                ? "YOLO activo · confianza " + Math.round(source.confidence * 100f) + "%"
                : "YOLO desactivado para esta fuente");
        detection.setTextColor(getColor(source.detectEnabled ? R.color.primary : R.color.text_secondary));
        detection.setTextSize(11f);
        card.addView(detection);

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(8), 0, 0);
        Button edit = actionButton("Editar");
        edit.setOnClickListener(view -> openEditor(source.id, source.sourceType));
        actions.addView(edit, new LinearLayout.LayoutParams(0, dp(44), 1f));
        Button delete = actionButton("Eliminar");
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        deleteParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(delete, deleteParams);
        delete.setOnClickListener(view -> confirmDelete(source));
        card.addView(actions);
        return card;
    }

    private Button actionButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(getColor(R.color.text_primary));
        button.setBackgroundResource(R.drawable.bg_button_secondary);
        return button;
    }

    private void confirmDelete(CameraConfig source) {
        new AlertDialog.Builder(this)
                .setTitle("Eliminar " + source.name)
                .setMessage("Se quitará la fuente del mosaico. Los eventos ya guardados no se borrarán.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Eliminar", (dialog, which) -> {
                    store.delete(source.id);
                    renderSources();
                    Toast.makeText(this, "Fuente eliminada", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void openEditor(String id, String type) {
        if (id.isBlank() && store.loadAll().size() >= SourceStore.MAX_SOURCES) {
            Toast.makeText(this, "Límite de " + SourceStore.MAX_SOURCES + " fuentes", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(this, SourceEditorActivity.class);
        intent.putExtra(SourceEditorActivity.EXTRA_SOURCE_ID, id);
        intent.putExtra(SourceEditorActivity.EXTRA_SOURCE_TYPE, type);
        startActivity(intent);
    }

    private void finishWithChanges() {
        setResult(RESULT_OK);
        finish();
    }

    @Override
    public void onBackPressed() {
        finishWithChanges();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
