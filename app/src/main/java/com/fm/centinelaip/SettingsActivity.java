package com.fm.centinelaip;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public final class SettingsActivity extends Activity {
    private static final String[] COMMON_PATHS = {
            "/stream1", "/stream2", "/live/ch00_0", "/live/ch00_1",
            "/h264Preview_01_main", "/cam/realmonitor?channel=1&subtype=0", "/onvif1", "/11"
    };

    private EditText nameInput;
    private EditText urlInput;
    private EditText userInput;
    private EditText passwordInput;
    private RadioButton sourcePhoneRadio;
    private RadioButton tcpRadio;
    private RadioButton frontCameraRadio;
    private SeekBar confidenceSeek;
    private TextView confidenceValue;
    private Switch personAlerts;
    private ConfigStore configStore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        configStore = new ConfigStore(this);

        nameInput = findViewById(R.id.nameInput);
        urlInput = findViewById(R.id.urlInput);
        userInput = findViewById(R.id.userInput);
        passwordInput = findViewById(R.id.passwordInput);
        sourcePhoneRadio = findViewById(R.id.sourcePhoneRadio);
        tcpRadio = findViewById(R.id.tcpRadio);
        frontCameraRadio = findViewById(R.id.frontCameraRadio);
        confidenceSeek = findViewById(R.id.confidenceSeek);
        confidenceValue = findViewById(R.id.confidenceValue);
        personAlerts = findViewById(R.id.personAlertsSwitch);

        CameraConfig config = configStore.load();
        nameInput.setText(config.name);
        urlInput.setText(config.rtspUrl);
        userInput.setText(config.username);
        passwordInput.setText(config.password);
        ((RadioButton) findViewById(config.usesPhoneCamera()
                ? R.id.sourcePhoneRadio : R.id.sourceRtspRadio)).setChecked(true);
        ((RadioButton) findViewById(config.forceTcp ? R.id.tcpRadio : R.id.udpRadio)).setChecked(true);
        ((RadioButton) findViewById(config.frontCamera
                ? R.id.frontCameraRadio : R.id.rearCameraRadio)).setChecked(true);
        confidenceSeek.setProgress(Math.round(config.confidence * 100));
        confidenceValue.setText(Math.round(config.confidence * 100) + "%");
        personAlerts.setChecked(config.personAlerts);

        confidenceSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                confidenceValue.setText(value + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        LinearLayout suggestions = findViewById(R.id.pathSuggestions);
        for (String path : COMMON_PATHS) suggestions.addView(createPathButton(path));

        findViewById(R.id.backButton).setOnClickListener(view -> finish());
        findViewById(R.id.saveButton).setOnClickListener(view -> save());
        findViewById(R.id.modelsButton).setOnClickListener(view ->
                startActivity(new android.content.Intent(this, ModelManagerActivity.class)));
        findViewById(R.id.annotationButton).setOnClickListener(view ->
                startActivity(new android.content.Intent(this, AnnotationActivity.class)));
        findViewById(R.id.eventRulesButton).setOnClickListener(view ->
                startActivity(new android.content.Intent(this, EventRulesActivity.class)));
        findViewById(R.id.trainingButton).setOnClickListener(view ->
                startActivity(new android.content.Intent(this, TrainingActivity.class)));
    }

    private Button createPathButton(String path) {
        Button button = new Button(this);
        button.setText(path);
        button.setTextSize(11f);
        button.setTextColor(getColor(R.color.text_primary));
        button.setAllCaps(false);
        button.setBackgroundResource(R.drawable.bg_button_secondary);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(42));
        params.setMargins(0, 0, dp(8), 0);
        button.setLayoutParams(params);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setOnClickListener(view -> applyPath(path));
        return button;
    }

    private void applyPath(String path) {
        String current = urlInput.getText().toString().trim();
        if (current.isEmpty()) current = "rtsp://192.168.1.50:554";
        if (!current.contains("://")) current = "rtsp://" + current;
        Uri base = Uri.parse(current);
        if (base.getHost() == null) {
            Toast.makeText(this, "Primero escribe la IP de la cámara", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri route = Uri.parse("rtsp://camera" + path);
        Uri completed = base.buildUpon()
                .encodedPath(route.getEncodedPath())
                .encodedQuery(route.getEncodedQuery())
                .build();
        urlInput.setText(completed.toString());
        urlInput.setSelection(urlInput.length());
    }

    private void save() {
        boolean phoneSource = sourcePhoneRadio.isChecked();
        String url = urlInput.getText().toString().trim();
        if (!url.isEmpty() && !url.contains("://")) url = "rtsp://" + url;
        Uri parsed = Uri.parse(url);
        if (!phoneSource
                && (!"rtsp".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null)) {
            urlInput.setError("Usa una URL válida: rtsp://IP:554/ruta");
            urlInput.requestFocus();
            return;
        }

        String username = userInput.getText().toString();
        String password = passwordInput.getText().toString();
        String userInfo = parsed.getUserInfo();
        if (userInfo != null && !userInfo.isEmpty()) {
            String[] credentials = userInfo.split(":", 2);
            if (username.isEmpty()) username = credentials[0];
            if (password.isEmpty() && credentials.length > 1) password = credentials[1];
            String host = parsed.getHost();
            if (host.contains(":")) host = "[" + host + "]";
            String authority = host + (parsed.getPort() >= 0 ? ":" + parsed.getPort() : "");
            parsed = parsed.buildUpon().encodedAuthority(authority).build();
        }

        CameraConfig config = new CameraConfig(
                nameInput.getText().toString(),
                phoneSource ? CameraConfig.SOURCE_PHONE : CameraConfig.SOURCE_RTSP,
                parsed.toString(),
                username,
                password,
                tcpRadio.isChecked(),
                confidenceSeek.getProgress() / 100f,
                personAlerts.isChecked(),
                frontCameraRadio.isChecked());
        try {
            configStore.save(config);
            setResult(RESULT_OK);
            finish();
        } catch (Exception error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
