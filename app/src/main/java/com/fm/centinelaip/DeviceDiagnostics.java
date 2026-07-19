package com.fm.centinelaip;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;

import java.util.Locale;

/** Resumen ligero del estado del dispositivo para pruebas sostenidas de video e IA. */
final class DeviceDiagnostics {
    private DeviceDiagnostics() { }

    static Snapshot read(Context context) {
        Context app = context.getApplicationContext();
        ActivityManager manager = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        if (manager != null) manager.getMemoryInfo(memoryInfo);
        long availableMemoryMb = memoryInfo.availMem / (1024L * 1024L);

        float batteryTemperatureC = Float.NaN;
        Intent battery = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (tenths != Integer.MIN_VALUE) batteryTemperatureC = tenths / 10f;
        }

        int thermalStatus = PowerManager.THERMAL_STATUS_NONE;
        if (Build.VERSION.SDK_INT >= 29) {
            PowerManager power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (power != null) thermalStatus = power.getCurrentThermalStatus();
        }
        return new Snapshot(availableMemoryMb, batteryTemperatureC, thermalStatus);
    }

    static final class Snapshot {
        final long availableMemoryMb;
        final float batteryTemperatureC;
        final int thermalStatus;

        Snapshot(long availableMemoryMb, float batteryTemperatureC, int thermalStatus) {
            this.availableMemoryMb = Math.max(0L, availableMemoryMb);
            this.batteryTemperatureC = batteryTemperatureC;
            this.thermalStatus = thermalStatus;
        }

        String compactLabel() {
            String temperature = Float.isFinite(batteryTemperatureC)
                    ? String.format(Locale.US, "%.1f °C", batteryTemperatureC)
                    : "temp. n/d";
            return temperature + " · " + thermalLabel(thermalStatus)
                    + " · RAM libre " + availableMemoryMb + " MB";
        }
    }

    static String thermalLabel(int status) {
        if (Build.VERSION.SDK_INT < 29) return "térmico n/d";
        switch (status) {
            case PowerManager.THERMAL_STATUS_NONE: return "térmico normal";
            case PowerManager.THERMAL_STATUS_LIGHT: return "térmico leve";
            case PowerManager.THERMAL_STATUS_MODERATE: return "térmico moderado";
            case PowerManager.THERMAL_STATUS_SEVERE: return "térmico alto";
            case PowerManager.THERMAL_STATUS_CRITICAL: return "térmico crítico";
            case PowerManager.THERMAL_STATUS_EMERGENCY: return "térmico emergencia";
            case PowerManager.THERMAL_STATUS_SHUTDOWN: return "apagado térmico";
            default: return "térmico desconocido";
        }
    }
}
