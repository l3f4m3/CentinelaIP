package com.fm.centinelaip;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

/** Fuente ligera de telemetría GNSS e IMU para el modo ADA Drive. */
final class DriveSensors implements SensorEventListener, LocationListener {
    interface Listener {
        void onSnapshot(Snapshot snapshot);
    }

    private static final String PREFS = "ada_drive_calibration";
    private static final String KEY_PITCH = "pitch_offset";
    private static final String KEY_ROLL = "roll_offset";
    private static final String KEY_CALIBRATED = "calibrated";

    private final Context context;
    private final Listener listener;
    private final SensorManager sensorManager;
    private final LocationManager locationManager;
    private final Sensor rotationSensor;
    private final SharedPreferences preferences;
    private final float[] rotationMatrix = new float[9];
    private final float[] remappedMatrix = new float[9];
    private final float[] orientation = new float[3];

    private boolean started;
    private boolean motionAvailable;
    private boolean locationAvailable;
    private boolean calibrated;
    private float pitchOffset;
    private float rollOffset;
    private float rawPitch;
    private float rawRoll;
    private float heading;
    private float speedKmh = Float.NaN;
    private float locationAccuracy = Float.NaN;
    private long locationAgeMs = Long.MAX_VALUE;

    DriveSensors(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        sensorManager = (SensorManager) this.context.getSystemService(Context.SENSOR_SERVICE);
        locationManager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
        rotationSensor = sensorManager == null ? null
                : sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        calibrated = preferences.getBoolean(KEY_CALIBRATED, false);
        pitchOffset = preferences.getFloat(KEY_PITCH, 0f);
        rollOffset = preferences.getFloat(KEY_ROLL, 0f);
    }

    void start() {
        if (started) return;
        started = true;
        if (sensorManager != null && rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        startLocationIfPermitted();
        publish();
    }

    void stop() {
        if (!started) return;
        started = false;
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (locationManager != null) {
            try { locationManager.removeUpdates(this); } catch (SecurityException ignored) { }
        }
    }

    void startLocationIfPermitted() {
        if (locationManager == null || context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                        500L, 0f, this, Looper.getMainLooper());
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                        1_000L, 0f, this, Looper.getMainLooper());
            }
        } catch (SecurityException ignored) { }
    }

    boolean calibrateCurrentMount() {
        if (!motionAvailable) return false;
        pitchOffset = rawPitch;
        rollOffset = rawRoll;
        calibrated = true;
        preferences.edit()
                .putFloat(KEY_PITCH, pitchOffset)
                .putFloat(KEY_ROLL, rollOffset)
                .putBoolean(KEY_CALIBRATED, true)
                .apply();
        publish();
        return true;
    }

    void clearCalibration() {
        calibrated = false;
        pitchOffset = 0f;
        rollOffset = 0f;
        preferences.edit().clear().apply();
        publish();
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        remapForDisplay(rotationMatrix, remappedMatrix);
        SensorManager.getOrientation(remappedMatrix, orientation);
        heading = DriveTelemetryMath.normalizeDegrees((float) Math.toDegrees(orientation[0]));
        rawPitch = DriveTelemetryMath.normalizeDegrees((float) Math.toDegrees(orientation[1]));
        rawRoll = DriveTelemetryMath.normalizeDegrees((float) Math.toDegrees(orientation[2]));
        motionAvailable = true;
        publish();
    }

    private void remapForDisplay(float[] source, float[] destination) {
        WindowManager window = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = window == null ? null : window.getDefaultDisplay();
        int rotation = display == null ? Surface.ROTATION_0 : display.getRotation();
        int axisX = SensorManager.AXIS_X;
        int axisY = SensorManager.AXIS_Y;
        if (rotation == Surface.ROTATION_90) {
            axisX = SensorManager.AXIS_Y;
            axisY = SensorManager.AXIS_MINUS_X;
        } else if (rotation == Surface.ROTATION_180) {
            axisX = SensorManager.AXIS_MINUS_X;
            axisY = SensorManager.AXIS_MINUS_Y;
        } else if (rotation == Surface.ROTATION_270) {
            axisX = SensorManager.AXIS_MINUS_Y;
            axisY = SensorManager.AXIS_X;
        }
        SensorManager.remapCoordinateSystem(source, axisX, axisY, destination);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override public void onLocationChanged(Location location) {
        if (location == null) return;
        float measured = DriveTelemetryMath.speedKmh(location.getSpeed(), location.hasSpeed());
        speedKmh = DriveTelemetryMath.smooth(speedKmh, measured, 0.35f);
        locationAccuracy = location.hasAccuracy() ? location.getAccuracy() : Float.NaN;
        locationAgeMs = Math.max(0L, System.currentTimeMillis() - location.getTime());
        locationAvailable = true;
        publish();
    }

    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)) {
            locationAvailable = false;
            speedKmh = Float.NaN;
            publish();
        }
    }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }

    private void publish() {
        if (listener == null) return;
        float pitch = calibrated
                ? DriveTelemetryMath.calibratedAngle(rawPitch, pitchOffset) : rawPitch;
        float roll = calibrated
                ? DriveTelemetryMath.calibratedAngle(rawRoll, rollOffset) : rawRoll;
        listener.onSnapshot(new Snapshot(
                motionAvailable,
                locationAvailable,
                calibrated,
                pitch,
                roll,
                heading,
                speedKmh,
                locationAccuracy,
                locationAgeMs));
    }

    static final class Snapshot {
        final boolean motionAvailable;
        final boolean locationAvailable;
        final boolean calibrated;
        final float pitchDegrees;
        final float rollDegrees;
        final float headingDegrees;
        final float speedKmh;
        final float locationAccuracyMeters;
        final long locationAgeMs;

        Snapshot(boolean motionAvailable, boolean locationAvailable, boolean calibrated,
                 float pitchDegrees, float rollDegrees, float headingDegrees,
                 float speedKmh, float locationAccuracyMeters, long locationAgeMs) {
            this.motionAvailable = motionAvailable;
            this.locationAvailable = locationAvailable;
            this.calibrated = calibrated;
            this.pitchDegrees = pitchDegrees;
            this.rollDegrees = rollDegrees;
            this.headingDegrees = headingDegrees;
            this.speedKmh = speedKmh;
            this.locationAccuracyMeters = locationAccuracyMeters;
            this.locationAgeMs = locationAgeMs;
        }
    }
}
