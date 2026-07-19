package com.fm.centinelaip;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class EventRulesActivity extends Activity {
    private final Map<String, CheckBox> detectBoxes = new HashMap<>();
    private final Map<String, CheckBox> saveBoxes = new HashMap<>();
    private final Map<String, CheckBox> notifyBoxes = new HashMap<>();
    private EventRulesStore rules;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_event_rules);
        rules = new EventRulesStore(this);
        ModelSpec model = new ModelStore(this).active();
        ((TextView) findViewById(R.id.modelText)).setText(
                model.name + " · selecciona qué clases crean capturas y alertas");
        findViewById(R.id.backButton).setOnClickListener(view -> finish());
        findViewById(R.id.saveButton).setOnClickListener(view -> save());

        Set<String> all = new HashSet<>(model.labels);
        Set<String> detect = rules.detectLabels(all);
        Set<String> save = rules.saveLabels();
        Set<String> notify = rules.notifyLabels();
        LinearLayout container = findViewById(R.id.rulesContainer);
        for (String label : model.labels) container.addView(row(label, detect, save, notify));
    }

    private LinearLayout row(String label, Set<String> detect, Set<String> save, Set<String> notify) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(3), 0, dp(3));

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(14f);
        row.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1f));

        CheckBox detectBox = new CheckBox(this);
        detectBox.setGravity(Gravity.CENTER);
        detectBox.setButtonTintList(getColorStateList(R.color.primary));
        detectBox.setChecked(detect.contains(label));
        row.addView(detectBox, new LinearLayout.LayoutParams(dp(64), dp(44)));

        CheckBox saveBox = new CheckBox(this);
        saveBox.setGravity(Gravity.CENTER);
        saveBox.setButtonTintList(getColorStateList(R.color.primary));
        saveBox.setChecked(save.contains(label));
        row.addView(saveBox, new LinearLayout.LayoutParams(dp(64), dp(44)));

        CheckBox notifyBox = new CheckBox(this);
        notifyBox.setGravity(Gravity.CENTER);
        notifyBox.setButtonTintList(getColorStateList(R.color.primary));
        notifyBox.setChecked(notify.contains(label));
        notifyBox.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                saveBox.setChecked(true);
                detectBox.setChecked(true);
            }
        });
        saveBox.setOnCheckedChangeListener((button, checked) -> {
            if (checked) detectBox.setChecked(true);
            if (!checked) notifyBox.setChecked(false);
        });
        detectBox.setOnCheckedChangeListener((button, checked) -> {
            if (!checked) {
                saveBox.setChecked(false);
                notifyBox.setChecked(false);
            }
        });
        row.addView(notifyBox, new LinearLayout.LayoutParams(dp(64), dp(44)));
        detectBoxes.put(label, detectBox);
        saveBoxes.put(label, saveBox);
        notifyBoxes.put(label, notifyBox);
        return row;
    }

    private void save() {
        Set<String> detect = new HashSet<>();
        Set<String> save = new HashSet<>();
        Set<String> notify = new HashSet<>();
        for (String label : saveBoxes.keySet()) {
            if (detectBoxes.get(label).isChecked()) detect.add(label);
            if (saveBoxes.get(label).isChecked()) save.add(label);
            if (notifyBoxes.get(label).isChecked()) notify.add(label);
        }
        rules.save(detect, save, notify);
        Toast.makeText(this, detect.size() + " visibles · " + save.size()
                + " guardan · " + notify.size() + " notifican", Toast.LENGTH_LONG).show();
        setResult(RESULT_OK);
        finish();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
