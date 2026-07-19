package com.fm.centinelaip;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public final class SourceEditorActivity extends Activity {
    static final String EXTRA_SOURCE_ID = "source_id";
    static final String EXTRA_SOURCE_TYPE = "source_type";
    private static final int DISCOVERY_PERMISSION = 301;
    private SourceStore store;
    private String sourceId = "";
    private EditText nameInput;
    private EditText uuidInput;
    private EditText ssidInput;
    private EditText urlInput;
    private EditText userInput;
    private EditText passwordInput;
    private RadioButton typeHcam;
    private RadioButton typeRtsp;
    private RadioButton typePhone;
    private RadioButton frontCamera;
    private LinearLayout hcamSection;
    private LinearLayout networkSection;
    private LinearLayout phoneSection;
    private Switch tcpSwitch;
    private Switch enabledSwitch;
    private Switch detectSwitch;
    private Switch alertsSwitch;
    private SeekBar confidenceSeek;
    private TextView confidenceValue;
    private TextView discoveryReport;
    private Button discoverButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_source_editor);
        store = new SourceStore(this);
        sourceId = getIntent().getStringExtra(EXTRA_SOURCE_ID);
        if (sourceId == null) sourceId = "";
        bindViews();
        loadSource();
        updateSections(false);

        ((RadioGroup) findViewById(R.id.typeGroup)).setOnCheckedChangeListener((group, checked) ->
                updateSections(true));
        confidenceSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                confidenceValue.setText(value + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        findViewById(R.id.backButton).setOnClickListener(view -> finish());
        findViewById(R.id.saveButton).setOnClickListener(view -> save());
        findViewById(R.id.openWifiButton).setOnClickListener(view -> openWifiPanel());
        discoverButton.setOnClickListener(view -> requestDiscovery());
        findViewById(R.id.vendorAppButton).setOnClickListener(view -> openVendorApp());
        findViewById(R.id.copyReportButton).setOnClickListener(view -> copyReport());
    }

    private void bindViews() {
        nameInput = findViewById(R.id.nameInput);
        uuidInput = findViewById(R.id.uuidInput);
        ssidInput = findViewById(R.id.ssidInput);
        urlInput = findViewById(R.id.urlInput);
        userInput = findViewById(R.id.userInput);
        passwordInput = findViewById(R.id.passwordInput);
        typeHcam = findViewById(R.id.typeHcam);
        typeRtsp = findViewById(R.id.typeRtsp);
        typePhone = findViewById(R.id.typePhone);
        frontCamera = findViewById(R.id.frontCameraRadio);
        hcamSection = findViewById(R.id.hcamSection);
        networkSection = findViewById(R.id.networkSection);
        phoneSection = findViewById(R.id.phoneSection);
        tcpSwitch = findViewById(R.id.tcpSwitch);
        enabledSwitch = findViewById(R.id.enabledSwitch);
        detectSwitch = findViewById(R.id.detectSwitch);
        alertsSwitch = findViewById(R.id.alertsSwitch);
        confidenceSeek = findViewById(R.id.confidenceSeek);
        confidenceValue = findViewById(R.id.confidenceValue);
        discoveryReport = findViewById(R.id.discoveryReport);
        discoverButton = findViewById(R.id.discoverButton);
    }

    private void loadSource() {
        CameraConfig source = sourceId.isBlank() ? null : store.find(sourceId);
        if (source == null) {
            String requested = getIntent().getStringExtra(EXTRA_SOURCE_TYPE);
            if (CameraConfig.SOURCE_HCAM.equals(requested)) {
                typeHcam.setChecked(true);
                nameInput.setText("Cámara HCAM");
                userInput.setText("admin");
            } else {
                typeRtsp.setChecked(true);
                nameInput.setText("Nueva cámara");
            }
            findViewById(R.id.titleText).setTag("new");
            return;
        }
        ((TextView) findViewById(R.id.titleText)).setText("Editar fuente");
        nameInput.setText(source.name);
        uuidInput.setText(source.uuid);
        ssidInput.setText(source.expectedSsid);
        urlInput.setText(source.rtspUrl);
        userInput.setText(source.username);
        passwordInput.setText(source.password);
        tcpSwitch.setChecked(source.forceTcp);
        enabledSwitch.setChecked(source.enabled);
        detectSwitch.setChecked(source.detectEnabled);
        alertsSwitch.setChecked(source.personAlerts);
        frontCamera.setChecked(source.frontCamera);
        ((RadioButton) findViewById(source.frontCamera
                ? R.id.frontCameraRadio : R.id.rearCameraRadio)).setChecked(true);
        confidenceSeek.setProgress(Math.round(source.confidence * 100f));
        confidenceValue.setText(Math.round(source.confidence * 100f) + "%");
        if (source.usesHcam()) typeHcam.setChecked(true);
        else if (source.usesPhoneCamera()) typePhone.setChecked(true);
        else typeRtsp.setChecked(true);
    }

    private void updateSections(boolean suggestName) {
        boolean hcam = typeHcam.isChecked();
        boolean phone = typePhone.isChecked();
        hcamSection.setVisibility(hcam ? View.VISIBLE : View.GONE);
        networkSection.setVisibility(phone ? View.GONE : View.VISIBLE);
        phoneSection.setVisibility(phone ? View.VISIBLE : View.GONE);
        if (!suggestName || !sourceId.isBlank()) return;
        String name = nameInput.getText().toString().trim();
        if (name.isBlank() || "Nueva cámara".equals(name) || "Cámara HCAM".equals(name)) {
            nameInput.setText(hcam ? "Cámara HCAM" : phone ? "Cámara del teléfono" : "Nueva cámara");
        }
        if (hcam) {
            if (userInput.getText().toString().isBlank()) userInput.setText("admin");
        }
    }

    private void openWifiPanel() {
        try {
            startActivity(new Intent(Settings.Panel.ACTION_WIFI));
        } catch (Exception error) {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        }
    }

    private void requestDiscovery() {
        ArrayList<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), DISCOVERY_PERMISSION);
            return;
        }
        runDiscovery();
    }

    private void runDiscovery() {
        String uuid = CameraConfig.normalizeUuid(uuidInput.getText().toString());
        if (uuid.isBlank()) uuid = uuidFromSsid(ssidInput.getText().toString());
        discoverButton.setEnabled(false);
        discoverButton.setText("Buscando…");
        discoveryReport.setText("Preparando la búsqueda en la red Wi‑Fi…");
        HcamDiscovery.discover(this, uuid, userInput.getText().toString(),
                passwordInput.getText().toString(), new HcamDiscovery.Listener() {
                    @Override public void onProgress(String message) {
                        discoveryReport.setText(message);
                    }

                    @Override public void onFinished(HcamDiscovery.Result result) {
                        discoverButton.setEnabled(true);
                        discoverButton.setText("2 · Detectar cámara");
                        discoveryReport.setText(result.report);
                        if (!result.rtspUrl.isBlank()) {
                            urlInput.setText(result.rtspUrl);
                            urlInput.setSelection(urlInput.length());
                        }
                        if (!result.connectedSsid.isBlank()) {
                            if (ssidInput.getText().toString().isBlank()) {
                                ssidInput.setText(result.connectedSsid);
                            }
                            if (uuidInput.getText().toString().isBlank()) {
                                String detectedUuid = uuidFromSsid(result.connectedSsid);
                                if (!detectedUuid.isBlank()) uuidInput.setText(detectedUuid);
                            }
                        }
                        if (result.success) {
                            Toast.makeText(SourceEditorActivity.this,
                                    "Flujo encontrado; ya puedes guardar la fuente", Toast.LENGTH_LONG).show();
                        } else if (result.credentialsRejected) {
                            Toast.makeText(SourceEditorActivity.this,
                                    "Servidor encontrado: corrige la contraseña y repite", Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(SourceEditorActivity.this,
                                    "No se encontró RTSP; revisa el diagnóstico", Toast.LENGTH_LONG).show();
                        }
                    }
                });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == DISCOVERY_PERMISSION) runDiscovery();
    }

    private void openVendorApp() {
        Intent installed = getPackageManager().getLaunchIntentForPackage("shix.go.zoom");
        if (installed != null) {
            startActivity(installed);
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=shix.go.zoom")));
        } catch (Exception error) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=shix.go.zoom")));
        }
    }

    private void copyReport() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("Diagnóstico HCAM",
                discoveryReport.getText().toString()));
        Toast.makeText(this, "Diagnóstico copiado", Toast.LENGTH_SHORT).show();
    }

    private static String uuidFromSsid(String value) {
        if (value == null) return "";
        String clean = value.trim().replace("\"", "");
        int start = clean.toUpperCase().indexOf("HCAM");
        return start < 0 ? "" : CameraConfig.normalizeUuid(clean.substring(start));
    }

    private void save() {
        boolean phone = typePhone.isChecked();
        boolean hcam = typeHcam.isChecked();
        String type = phone ? CameraConfig.SOURCE_PHONE
                : hcam ? CameraConfig.SOURCE_HCAM : CameraConfig.SOURCE_RTSP;
        if (phone && store.hasOtherPhoneSource(sourceId)) {
            Toast.makeText(this,
                    "Ya existe una fuente del teléfono. Android normalmente solo permite abrir un lente a la vez.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        String uuid = CameraConfig.normalizeUuid(uuidInput.getText().toString());
        if (uuid.isBlank()) uuid = uuidFromSsid(ssidInput.getText().toString());
        if (hcam && uuid.isBlank()) {
            uuidInput.setError("El UUID es obligatorio para una fuente HCAM");
            uuidInput.requestFocus();
            return;
        }
        String url = urlInput.getText().toString().trim();
        if (!url.isBlank() && !url.contains("://")) url = "rtsp://" + url;
        Uri parsed = Uri.parse(url);
        if (!phone && (!url.isBlank() || !hcam)
                && (!"rtsp".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null)) {
            urlInput.setError("Usa rtsp://IP:puerto/ruta, o pulsa Detectar");
            urlInput.requestFocus();
            return;
        }

        String username = userInput.getText().toString();
        String password = passwordInput.getText().toString();
        if (!url.isBlank() && parsed.getUserInfo() != null) {
            String[] values = parsed.getUserInfo().split(":", 2);
            if (username.isBlank()) username = values[0];
            if (password.isBlank() && values.length > 1) password = values[1];
            String host = parsed.getHost();
            if (host != null && host.contains(":")) host = "[" + host + "]";
            parsed = parsed.buildUpon().encodedAuthority(host
                    + (parsed.getPort() >= 0 ? ":" + parsed.getPort() : "")).build();
            url = parsed.toString();
        }

        String ssid = ssidInput.getText().toString().trim();
        if (hcam && ssid.isBlank()) ssid = "XIOTA-" + uuid;
        CameraConfig source = new CameraConfig(
                sourceId,
                nameInput.getText().toString(),
                type,
                phone ? "" : url,
                hcam ? uuid : "",
                hcam ? ssid : "",
                phone ? "" : username,
                phone ? "" : password,
                tcpSwitch.isChecked(),
                confidenceSeek.getProgress() / 100f,
                alertsSwitch.isChecked(),
                frontCamera.isChecked(),
                enabledSwitch.isChecked(),
                detectSwitch.isChecked());
        try {
            store.upsert(source);
            setResult(RESULT_OK);
            finish();
        } catch (Exception error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
