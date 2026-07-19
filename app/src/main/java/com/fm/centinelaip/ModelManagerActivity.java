package com.fm.centinelaip;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ModelManagerActivity extends Activity {
    private static final int IMPORT_REQUEST = 301;
    private static final int EXPORT_REQUEST = 302;

    private ModelStore store;
    private LinearLayout container;
    private TextView activeText;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private ModelSpec pendingExport;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_models);
        store = new ModelStore(this);
        container = findViewById(R.id.modelsContainer);
        activeText = findViewById(R.id.activeModelText);
        findViewById(R.id.backButton).setOnClickListener(view -> finish());
        findViewById(R.id.importButton).setOnClickListener(view -> chooseImport());
        findViewById(R.id.exportButton).setOnClickListener(view -> chooseExport(store.active()));
        render();
    }

    private void chooseImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        startActivityForResult(intent, IMPORT_REQUEST);
    }

    private void chooseExport(ModelSpec spec) {
        pendingExport = spec;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, safeName(spec.name) + ".centinela-model.zip");
        startActivityForResult(intent, EXPORT_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == IMPORT_REQUEST) importPackage(uri);
        if (requestCode == EXPORT_REQUEST && pendingExport != null) exportPackage(uri, pendingExport);
    }

    private void importPackage(Uri uri) {
        Toast.makeText(this, "Validando modelo…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalStateException("No se pudo abrir el ZIP");
                ModelSpec imported = store.importPackage(input);
                runOnUiThread(() -> {
                    setResult(RESULT_OK);
                    Toast.makeText(this, imported.name + " importado y activado", Toast.LENGTH_LONG).show();
                    render();
                });
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this,
                        error.getMessage() == null ? "Falló la importación" : error.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void exportPackage(Uri uri, ModelSpec spec) {
        io.execute(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new IllegalStateException("No se pudo crear el ZIP");
                store.exportPackage(spec, output);
                runOnUiThread(() -> Toast.makeText(this,
                        "Modelo exportado", Toast.LENGTH_LONG).show());
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this,
                        "No fue posible exportar el modelo", Toast.LENGTH_LONG).show());
            }
        });
    }

    private void render() {
        ModelSpec active = store.active();
        activeText.setText("ACTIVO · " + active.name + "\n" + active.labels.size() + " clases");
        container.removeAllViews();
        for (ModelSpec spec : store.list()) {
            container.addView(createCard(spec, active.id.equals(spec.id)));
            Space space = new Space(this);
            container.addView(space, new LinearLayout.LayoutParams(1, dp(10)));
        }
    }

    private View createCard(ModelSpec spec, boolean active) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackgroundResource(R.drawable.bg_card);

        TextView title = new TextView(this);
        title.setText(spec.name + (active ? "  ·  ACTIVO" : ""));
        title.setTextColor(getColor(active ? R.color.primary : R.color.text_primary));
        title.setTextSize(16f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(title);

        TextView details = new TextView(this);
        details.setText(spec.labels.size() + " clases · " + (spec.bundled ? "incluido" : "importado"));
        details.setTextColor(getColor(R.color.text_secondary));
        details.setTextSize(12f);
        details.setPadding(0, dp(4), 0, dp(10));
        card.addView(details);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        if (!active) actions.addView(actionButton("Activar", view -> {
            store.setActive(spec.id);
            setResult(RESULT_OK);
            render();
        }));
        actions.addView(actionButton("Exportar", view -> chooseExport(spec)));
        if (!spec.bundled) actions.addView(actionButton("Eliminar", view -> confirmDelete(spec)));
        card.addView(actions);
        return card;
    }

    private Button actionButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(getColor(R.color.text_primary));
        button.setTextSize(12f);
        button.setBackgroundResource(R.drawable.bg_button_secondary);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1f);
        params.setMargins(dp(3), 0, dp(3), 0);
        button.setLayoutParams(params);
        return button;
    }

    private void confirmDelete(ModelSpec spec) {
        new AlertDialog.Builder(this)
                .setTitle("Eliminar modelo")
                .setMessage("Se eliminará " + spec.name + ". Los datasets y anotaciones no se borrarán.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Eliminar", (dialog, which) -> {
                    store.delete(spec);
                    setResult(RESULT_OK);
                    render();
                })
                .show();
    }

    private static String safeName(String value) {
        String safe = value.replaceAll("[^a-zA-Z0-9._-]+", "_");
        return safe.isEmpty() ? "modelo" : safe;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
