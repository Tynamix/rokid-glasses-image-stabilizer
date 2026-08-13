package com.roman.rokidarcsoft;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int STORAGE_PERMISSION_REQUEST = 10;

    private Button synchronizeButton;
    private ProgressBar progress;
    private TextView statusLabel;
    private TextView currentLabel;
    private SynchronizationService service;
    private boolean serviceBound;

    private final SynchronizationService.Listener serviceListener = status ->
            runOnUiThread(() -> renderStatus(status));

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, android.os.IBinder binder) {
            SynchronizationService.LocalBinder localBinder =
                    (SynchronizationService.LocalBinder) binder;
            service = localBinder.getService();
            serviceBound = true;
            service.addListener(serviceListener);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            serviceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, SynchronizationService.class), serviceConnection,
                BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        if (serviceBound) {
            service.removeListener(serviceListener);
            unbindService(serviceConnection);
            serviceBound = false;
            service = null;
        }
        super.onStop();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 32);

        TextView title = new TextView(this);
        title.setText("Rokid Arcsoft Converter");
        title.setTextSize(24);
        title.setTextColor(0xff111827);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView explanation = new TextView(this);
        explanation.setText("Sucht in Downloads/Hi Rokid nach MP4-Dateien mit passender TXT-Datei und synchronisiert sie automatisch im Hintergrund.");
        explanation.setTextSize(15);
        explanation.setTextColor(0xff4b5563);
        LinearLayout.LayoutParams explanationParams = new LinearLayout.LayoutParams(-1, -2);
        explanationParams.topMargin = 20;
        root.addView(explanation, explanationParams);

        synchronizeButton = new Button(this);
        synchronizeButton.setText("Start Synchronization");
        synchronizeButton.setOnClickListener(v -> requestSynchronization());
        addTopMargin(root, synchronizeButton);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, 40);
        progressParams.topMargin = 24;
        root.addView(progress, progressParams);

        currentLabel = label("");
        root.addView(currentLabel);

        statusLabel = label("Bereit");
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = 16;
        root.addView(statusLabel, statusParams);

        setContentView(root);
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        view.setTextColor(0xff374151);
        return view;
    }

    private void addTopMargin(LinearLayout root, View view) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = 24;
        root.addView(view, params);
    }

    private void requestSynchronization() {
        if (Build.VERSION.SDK_INT >= 23 && !hasStoragePermission()) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, STORAGE_PERMISSION_REQUEST);
            return;
        }
        startSynchronizationService();
    }

    private boolean hasStoragePermission() {
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != STORAGE_PERMISSION_REQUEST) return;
        if (hasStoragePermission()) {
            startSynchronizationService();
        } else {
            statusLabel.setText("Fehler: Zugriff auf Downloads wurde nicht erlaubt.");
        }
    }

    private void startSynchronizationService() {
        Intent intent = new Intent(this, SynchronizationService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        synchronizeButton.setEnabled(false);
        statusLabel.setText("Synchronisierung wird vorbereitet...");
    }

    private void renderStatus(SynchronizationService.Status status) {
        progress.setProgress(status.currentPercent);
        synchronizeButton.setEnabled(!status.running);

        if (status.total > 0 && status.running) {
            currentLabel.setText((status.processed + 1) + "/" + status.total
                    + " - " + status.currentName);
        } else if (status.total > 0) {
            currentLabel.setText(status.processed + "/" + status.total);
        } else {
            currentLabel.setText("");
        }
        statusLabel.setText(status.message);
    }
}
