package io.github.p5rrecomp;

import android.app.ActivityManager;
import android.app.NativeActivity;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Map;

/** NativeActivity wrapper for the pause shortcut and optional live performance overlay. */
public final class GameActivity extends NativeActivity {
    private boolean pauseScreenOpening;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView metricsView;
    private long previousFrames, previousCpuTotal, previousCpuIdle;
    private final Runnable metricsTick = new Runnable() {
        @Override public void run() {
            refreshMetrics();
            handler.postDelayed(this, 1000);
        }
    };

    private static native long nativeFrames();
    private static native String nativeGpuName();

    @Override protected void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        metricsView = new TextView(this);
        metricsView.setTextColor(0xfff5f2ef);
        metricsView.setTextSize(12);
        metricsView.setTypeface(android.graphics.Typeface.MONOSPACE);
        metricsView.setPadding(dp(9), dp(6), dp(9), dp(6));
        metricsView.setBackgroundColor(0xcc101015);
        metricsView.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        lp.setMargins(dp(10), dp(10), dp(10), dp(10));
        addContentView(metricsView, lp);
    }

    @Override protected void onResume() {
        super.onResume();
        pauseScreenOpening = false;
        handler.removeCallbacks(metricsTick);
        handler.post(metricsTick);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(metricsTick);
        super.onPause();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void refreshMetrics() {
        Map<String, String> config = Launcher.readConfig(new File(Launcher.dataDir(), "config.txt"));
        if (!"1".equals(config.get("NX_SHOW_FPS"))) {
            metricsView.setVisibility(View.GONE);
            return;
        }
        long frames = nativeFrames();
        long delta = previousFrames == 0 ? 0 : Math.max(0, frames - previousFrames);
        previousFrames = frames;
        StringBuilder lines = new StringBuilder("FPS  ").append(delta);
        if (enabled(config, "NX_FPS_SHOW_GPU")) lines.append("\nGPU  ").append(safeGpuName());
        if (enabled(config, "NX_FPS_SHOW_BATTERY_TEMP")) lines.append("\nBAT  ").append(batteryTemperature());
        if (enabled(config, "NX_FPS_SHOW_CPU_TEMP")) lines.append("\nCPU° ").append(cpuTemperature());
        if (enabled(config, "NX_FPS_SHOW_CPU")) lines.append("\nCPU  ").append(cpuUsage()).append('%');
        if (enabled(config, "NX_FPS_SHOW_RAM")) lines.append("\nRAM  ").append(ramUsage()).append('%');
        if (enabled(config, "NX_FPS_SHOW_GPU_LOAD")) lines.append("\nGPU% ").append(gpuUsage());
        metricsView.setText(lines.toString());
        metricsView.setVisibility(View.VISIBLE);
    }

    private boolean enabled(Map<String, String> c, String key) { return "1".equals(c.get(key)); }
    private String safeGpuName() {
        try { String name = nativeGpuName(); return name == null || name.isEmpty() ? Build.HARDWARE : name; }
        catch (Throwable ignored) { return Build.HARDWARE; }
    }

    private String batteryTemperature() {
        try {
            Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int temp = i == null ? 0 : i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            return temp > 0 ? String.format(java.util.Locale.ROOT, "%.1f°C", temp / 10.0) : "N/D";
        } catch (Exception ignored) { return "N/D"; }
    }

    private String cpuTemperature() {
        for (int i = 0; i < 40; i++) {
            File zone = new File("/sys/class/thermal/thermal_zone" + i);
            String type = read(new File(zone, "type"));
            if (type == null || !(type.toLowerCase(java.util.Locale.ROOT).contains("cpu")
                    || type.toLowerCase(java.util.Locale.ROOT).contains("soc")
                    || type.toLowerCase(java.util.Locale.ROOT).contains("ap"))) continue;
            try {
                double value = Double.parseDouble(read(new File(zone, "temp")).trim());
                if (value > 1000) value /= 1000.0;
                else if (value > 150) value /= 10.0;
                if (value > 0 && value < 150) return String.format(java.util.Locale.ROOT, "%.1f°C", value);
            } catch (Exception ignored) { }
        }
        return "N/D";
    }

    private String cpuUsage() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/stat"))) {
            String[] p = r.readLine().trim().split("\\s+");
            long total = 0;
            for (int i = 1; i < p.length; i++) total += Long.parseLong(p[i]);
            long idle = Long.parseLong(p[4]) + (p.length > 5 ? Long.parseLong(p[5]) : 0);
            long dt = total - previousCpuTotal, di = idle - previousCpuIdle;
            previousCpuTotal = total; previousCpuIdle = idle;
            return dt > 0 ? String.valueOf(Math.max(0, Math.min(100, Math.round(100.0 * (dt - di) / dt)))) : "N/D";
        } catch (Exception ignored) { return "N/D"; }
    }

    private String ramUsage() {
        try {
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(info);
            return info.totalMem > 0 ? String.valueOf(Math.round(100.0 * (info.totalMem - info.availMem) / info.totalMem)) : "N/D";
        } catch (Exception ignored) { return "N/D"; }
    }

    private String gpuUsage() {
        String[] paths = {"/sys/class/kgsl/kgsl-3d0/gpubusy", "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"};
        for (String path : paths) {
            File f = new File(path);
            if (f.isFile()) {
                String data = read(f);
                if (data != null) {
                    String[] p = data.trim().split("\\s+");
                    try {
                        if (p.length >= 2) return Math.max(0, Math.min(100, Math.round(100f * Float.parseFloat(p[0]) / Float.parseFloat(p[1])))) + "%";
                        return Math.max(0, Math.min(100, Integer.parseInt(p[0]))) + "%";
                    } catch (Exception ignored) { }
                }
            }
        }
        File devfreq = new File("/sys/class/devfreq");
        File[] devices = devfreq.listFiles();
        if (devices != null) for (File device : devices) {
            if (!device.getName().toLowerCase(java.util.Locale.ROOT).contains("gpu")) continue;
            for (String attribute : new String[] {"load", "gpu_load", "utilization", "gpu_busy_percentage"}) {
                String value = read(new File(device, attribute));
                if (value == null) continue;
                try {
                    String[] p = value.trim().split("\\s+");
                    int percent = (int) Math.round(Float.parseFloat(p[0]));
                    return Math.max(0, Math.min(100, percent)) + "%";
                } catch (Exception ignored) { }
            }
        }
        return "N/D";
    }

    private String read(File f) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) { return r.readLine(); }
        catch (Exception ignored) { return null; }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) openPauseScreen();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public void onBackPressed() { openPauseScreen(); }

    private void openPauseScreen() {
        if (pauseScreenOpening || isFinishing()) return;
        pauseScreenOpening = true;
        startActivity(new Intent(this, PauseActivity.class));
        overridePendingTransition(0, 0);
    }
}
