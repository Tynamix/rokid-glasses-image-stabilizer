package com.roman.rokidarcsoft;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.rokid.media.process.MediaManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int PICK_VIDEO = 10;
    private static final int PICK_SENSOR = 11;
    private Uri videoUri;
    private Uri sensorUri;
    private Uri outputUri;
    private TextView videoLabel;
    private TextView sensorLabel;
    private TextView statusLabel;
    private ProgressBar progress;
    private Button convertButton;
    private Button openButton;
    private Button folderButton;
    private MediaManager mediaManager;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
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
        explanation.setText("MP4 und zugehörige Gyro-TXT auswählen. Die Verarbeitung läuft lokal mit der originalen Rokid/Arcsoft-Pipeline.");
        explanation.setTextSize(15);
        explanation.setTextColor(0xff4b5563);
        LinearLayout.LayoutParams explanationParams = new LinearLayout.LayoutParams(-1, -2);
        explanationParams.topMargin = 20;
        root.addView(explanation, explanationParams);

        Button videoButton = new Button(this);
        videoButton.setText("MP4 auswählen");
        videoButton.setOnClickListener(v -> pick(PICK_VIDEO, "video/mp4"));
        addTopMargin(root, videoButton);
        videoLabel = label("Noch kein Video ausgewählt");
        root.addView(videoLabel);

        Button sensorButton = new Button(this);
        sensorButton.setText("TXT-Gyrodatei auswählen");
        sensorButton.setOnClickListener(v -> pick(PICK_SENSOR, "text/plain"));
        addTopMargin(root, sensorButton);
        sensorLabel = label("Noch keine TXT-Datei ausgewählt");
        root.addView(sensorLabel);

        convertButton = new Button(this);
        convertButton.setText("Konvertieren");
        convertButton.setEnabled(false);
        convertButton.setOnClickListener(v -> startConversion());
        addTopMargin(root, convertButton);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        root.addView(progress, new LinearLayout.LayoutParams(-1, 40));

        statusLabel = label("Bereit");
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = 16;
        root.addView(statusLabel, statusParams);

        openButton = new Button(this);
        openButton.setText("Video öffnen");
        openButton.setEnabled(false);
        openButton.setOnClickListener(v -> openOutputVideo());
        addTopMargin(root, openButton);

        folderButton = new Button(this);
        folderButton.setText("Ordner öffnen");
        folderButton.setEnabled(false);
        folderButton.setOnClickListener(v -> openOutputFolder());
        root.addView(folderButton);
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

    private void pick(int requestCode, String type) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(type);
        startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
            // Some document providers do not offer persistable permissions.
        }
        if (requestCode == PICK_VIDEO) {
            videoUri = uri;
            videoLabel.setText("Video: " + displayName(uri));
            Uri matchingSensor = findMatchingSensor(uri);
            if (matchingSensor != null) {
                sensorUri = matchingSensor;
                sensorLabel.setText("Gyro automatisch: " + displayName(matchingSensor));
            } else {
                sensorLabel.setText("Keine passende TXT gefunden");
            }
        } else if (requestCode == PICK_SENSOR) {
            sensorUri = uri;
            sensorLabel.setText("Gyro: " + displayName(uri));
        }
        convertButton.setEnabled(videoUri != null && sensorUri != null);
    }

    private Uri findMatchingSensor(Uri selectedVideo) {
        String name = displayName(selectedVideo);
        if (name == null || !name.toLowerCase(Locale.US).endsWith(".mp4")) return null;
        String targetName = name.substring(0, name.length() - 4) + ".txt";

        String authority = selectedVideo.getAuthority();
        if (authority != null && authority.contains("documents")) {
            try {
                String documentId = DocumentsContract.getDocumentId(selectedVideo);
                int slash = documentId.lastIndexOf('/');
                if (slash >= 0) {
                    String siblingId = documentId.substring(0, slash + 1) + targetName;
                    Uri sibling = DocumentsContract.buildDocumentUri(authority, siblingId);
                    if (isReadable(sibling)) return sibling;
                }
            } catch (Exception ignored) {
                // Fall through to the MediaStore lookup below.
            }
        }

        Uri files = MediaStore.Files.getContentUri("external");
        String[] projection = {MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME};
        String selection = MediaStore.Files.FileColumns.DISPLAY_NAME + "=?";
        try (Cursor cursor = getContentResolver().query(files, projection, selection,
                new String[]{targetName}, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(0);
                return ContentUris.withAppendedId(files, id);
            }
        } catch (Exception ignored) {
            // The TXT may not be indexed yet; manual selection remains available.
        }
        return null;
    }

    private boolean isReadable(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            return input != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String displayName(Uri uri) {
        Cursor cursor = getContentResolver().query(uri, new String[]{"_display_name"}, null, null, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) return cursor.getString(0);
            } finally {
                cursor.close();
            }
        }
        return uri.getLastPathSegment();
    }

    private void startConversion() {
        convertButton.setEnabled(false);
        progress.setProgress(0);
        statusLabel.setText("Dateien werden vorbereitet...");
        new Thread(() -> {
            File work = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "arcsoft_work");
            if (!work.exists() && !work.mkdirs()) {
                showError("Arbeitsordner konnte nicht erstellt werden");
                return;
            }
            File video = new File(work, "input.mp4");
            File sensor = new File(work, "input.txt");
            File output = new File(work, "output.mp4");
            try {
                copyUri(videoUri, video);
                copyUri(sensorUri, sensor);
                if (output.exists()) output.delete();
                mediaManager = new MediaManager();
                mediaManager.setListener(new MediaManager.Listener() {
                    @Override public void onProgress(int percent) {
                        runOnUiThread(() -> {
                            progress.setProgress(percent);
                            statusLabel.setText("Konvertiere... " + percent + "%");
                        });
                    }

                    @Override public void onComplete() {
                        new Thread(() -> finishOutput(output), "rokid-export").start();
                    }

                    @Override public void onError(int code) {
                        showError("Arcsoft-Fehler: " + code);
                    }
                });
                int result = mediaManager.start(video.getAbsolutePath(), output.getAbsolutePath(), sensor.getAbsolutePath());
                if (result != 0) showError("Start fehlgeschlagen: " + result);
            } catch (Exception error) {
                showError(error.getMessage() == null ? error.toString() : error.getMessage());
            }
        }, "rokid-converter").start();
    }

    private void finishOutput(File output) {
        try {
            if (!output.exists() || output.length() == 0) throw new Exception("Arcsoft-Ausgabe fehlt");
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, "Rokid-Arcsoft-" + stamp + ".mp4");
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Rokid Arcsoft");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
            }
            Uri destination = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (destination == null) throw new Exception("Ausgabe konnte nicht gespeichert werden");
            try (InputStream input = new FileInputStream(output);
                 OutputStream target = getContentResolver().openOutputStream(destination)) {
                if (target == null) throw new Exception("Ausgabestream konnte nicht geöffnet werden");
                byte[] buffer = new byte[1024 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) target.write(buffer, 0, count);
            }
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(destination, ready, null, null);
            }
            outputUri = destination;
            if (mediaManager != null) mediaManager.stopAndRelease();
            runOnUiThread(() -> {
                progress.setProgress(100);
                statusLabel.setText("Fertig. Gespeichert unter Movies/Rokid Arcsoft.");
                convertButton.setEnabled(true);
                openButton.setEnabled(true);
                folderButton.setEnabled(true);
            });
        } catch (Exception error) {
            showError(error.getMessage() == null ? error.toString() : error.getMessage());
        }
    }

    private void copyUri(Uri uri, File destination) throws Exception {
        ContentResolver resolver = getContentResolver();
        try (InputStream input = resolver.openInputStream(uri);
             OutputStream output = new FileOutputStream(destination)) {
            if (input == null) throw new Exception("Datei konnte nicht gelesen werden");
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        }
    }

    private void showError(String message) {
        runOnUiThread(() -> {
            statusLabel.setText("Fehler: " + message);
            convertButton.setEnabled(true);
        });
    }

    private void openOutputVideo() {
        if (outputUri == null) return;
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(outputUri, "video/mp4");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (Exception error) {
            showError("Kein Video-Player gefunden");
        }
    }

    private void openOutputFolder() {
        Uri folder = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:Movies/Rokid Arcsoft");
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(folder, "vnd.android.document/directory");
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(view);
            return;
        } catch (Exception ignored) {
            // Fall back to the system folder picker if Files does not handle ACTION_VIEW.
        }
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI, folder);
        }
        startActivityForResult(picker, 20);
    }
}
