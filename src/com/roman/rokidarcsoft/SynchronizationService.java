package com.roman.rokidarcsoft;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;

import com.rokid.media.process.MediaManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

public final class SynchronizationService extends Service {
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "rokid_synchronization";
    private static final String WORK_DIRECTORY = "sync_work";

    private final IBinder binder = new LocalBinder();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object stateLock = new Object();
    private volatile Status status = new Status(false, false, 0, 0, 0, "", "Bereit");
    private volatile boolean running;
    private volatile MediaManager activeManager;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private NotificationManager notificationManager;

    public interface Listener {
        void onStatus(Status status);
    }

    public final class LocalBinder extends Binder {
        SynchronizationService getService() {
            return SynchronizationService.this;
        }
    }

    public static final class Status {
        public final boolean running;
        public final boolean finished;
        public final int total;
        public final int processed;
        public final int currentPercent;
        public final String currentName;
        public final String message;

        private Status(boolean running, boolean finished, int total, int processed,
                       int currentPercent, String currentName, String message) {
            this.running = running;
            this.finished = finished;
            this.total = total;
            this.processed = processed;
            this.currentPercent = currentPercent;
            this.currentName = currentName;
            this.message = message;
        }
    }

    private static final class VideoPair {
        final File video;
        final File sensor;

        VideoPair(File video, File sensor) {
            this.video = video;
            this.sensor = sensor;
        }
    }

    private static final class NativeResult {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile int errorCode;
        volatile boolean completed;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            startForeground(NOTIFICATION_ID, notification("Synchronisierung wird vorbereitet...", 0));
            running = true;
            acquireWakeLock();
            worker = new Thread(this::runSynchronization, "rokid-synchronization");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        MediaManager manager = activeManager;
        if (manager != null) {
            try {
                manager.stopAndRelease();
            } catch (Throwable ignored) {
                // The process may already have been killed while native processing ran.
            }
            activeManager = null;
        }
        releaseWakeLock();
        super.onDestroy();
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
        listener.onStatus(status);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void runSynchronization() {
        try {
            if (!hasStoragePermission()) {
                finishSynchronization("Fehler: Zugriff auf Downloads wurde nicht erlaubt.", 0, 0);
                return;
            }

            File inputDirectory = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "Hi Rokid");
            List<VideoPair> pairs = findPairs(inputDirectory);
            publish(0, pairs.size(), 0, "", pairs.isEmpty()
                    ? "Keine unverarbeiteten Videos gefunden."
                    : "Synchronisierung gestartet...");
            if (pairs.isEmpty()) {
                finishSynchronization("Keine unverarbeiteten Videos gefunden.", 0, 0);
                return;
            }

            File workDirectory = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                    WORK_DIRECTORY);
            if (!workDirectory.exists() && !workDirectory.mkdirs()) {
                finishSynchronization("Fehler: Arbeitsordner konnte nicht erstellt werden.",
                        0, pairs.size());
                return;
            }

            int failures = 0;
            int processed = 0;
            for (VideoPair pair : pairs) {
                int position = processed + 1;
                publish(processed, pairs.size(), 0, pair.video.getName(),
                        "Konvertiere " + position + "/" + pairs.size() + "...");
                boolean success = convertPair(pair, workDirectory);
                if (!success) failures++;
                processed++;
                if (success) {
                    publish(processed, pairs.size(), 100, pair.video.getName(),
                            "Erfolgreich synchronisiert: " + pair.video.getName());
                }
            }

            String message;
            if (failures == 0) {
                message = "Synchronisierung abgeschlossen: " + processed + "/"
                        + pairs.size() + " Videos erfolgreich.";
            } else {
                message = "Synchronisierung abgeschlossen: " + (processed - failures)
                        + "/" + pairs.size() + " erfolgreich, " + failures
                        + " Fehler. Fehlgeschlagene Paare wurden behalten.";
            }
            finishSynchronization(message, processed, pairs.size());
        } catch (Throwable error) {
            String message = error.getMessage();
            finishSynchronization("Fehler: " + (message == null ? error.toString() : message),
                    status.processed, status.total);
        }
    }

    private List<VideoPair> findPairs(File directory) {
        List<VideoPair> result = new ArrayList<>();
        if (!directory.isDirectory()) return result;
        recoverStaleTransactions(directory);

        File[] files = directory.listFiles();
        if (files == null) return result;
        Map<String, File> sensors = new HashMap<>();
        for (File file : files) {
            if (file.isFile() && file.getName().toLowerCase(Locale.US).endsWith(".txt")) {
                sensors.put(file.getName().toLowerCase(Locale.US), file);
            }
        }

        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File video : files) {
            String name = video.getName();
            String lowerName = name.toLowerCase(Locale.US);
            if (!video.isFile() || !lowerName.endsWith(".mp4")) continue;
            String baseName = lowerName.substring(0, lowerName.length() - 4);
            File sensor = sensors.get(baseName + ".txt");
            if (sensor != null && sensor.isFile()) result.add(new VideoPair(video, sensor));
        }
        return result;
    }

    private void recoverStaleTransactions(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return;
        String backupSuffix = ".rokid-old";
        for (File backup : files) {
            String name = backup.getName();
            if (!backup.isFile() || !name.startsWith(".") || !name.endsWith(backupSuffix)) continue;
            String originalName = name.substring(1, name.length() - backupSuffix.length());
            File original = new File(directory, originalName);
            if (original.exists()) {
                backup.delete();
            } else {
                backup.renameTo(original);
            }
        }
    }

    private boolean convertPair(VideoPair pair, File workDirectory) {
        File input = new File(workDirectory, "input.mp4");
        File sensor = new File(workDirectory, "input.txt");
        File output = new File(workDirectory, "output.mp4");
        try {
            deleteIfExists(input);
            deleteIfExists(sensor);
            deleteIfExists(output);
            copyFile(pair.video, input);
            copyFile(pair.sensor, sensor);

            NativeResult nativeResult = new NativeResult();
            MediaManager manager = new MediaManager();
            activeManager = manager;
            manager.setListener(new MediaManager.Listener() {
                @Override
                public void onProgress(int percent) {
                    publish(status.processed, status.total, percent, pair.video.getName(),
                            "Konvertiere " + (status.processed + 1) + "/" + status.total
                                    + " - " + percent + "%");
                }

                @Override
                public void onComplete() {
                    nativeResult.completed = true;
                    nativeResult.latch.countDown();
                }

                @Override
                public void onError(int code) {
                    nativeResult.errorCode = code;
                    nativeResult.latch.countDown();
                }
            });

            int startResult = manager.start(input.getAbsolutePath(), output.getAbsolutePath(),
                    sensor.getAbsolutePath());
            if (startResult != 0) {
                publishFailure(pair, "Start fehlgeschlagen: " + startResult);
                return false;
            }
            nativeResult.latch.await();
            if (!nativeResult.completed) {
                publishFailure(pair, "Arcsoft-Fehler: " + nativeResult.errorCode);
                return false;
            }
            if (!output.isFile() || output.length() == 0) {
                publishFailure(pair, "Arcsoft-Ausgabe fehlt");
                return false;
            }
            replaceOriginal(pair.video, pair.sensor, output);
            return true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            publishFailure(pair, "Verarbeitung wurde unterbrochen");
            return false;
        } catch (Exception error) {
            String message = error.getMessage();
            publishFailure(pair, message == null ? error.toString() : message);
            return false;
        } finally {
            MediaManager manager = activeManager;
            activeManager = null;
            if (manager != null) {
                try {
                    manager.stopAndRelease();
                } catch (Throwable ignored) {
                    // Cleanup must not prevent the remaining pairs from running.
                }
            }
            deleteQuietly(input);
            deleteQuietly(sensor);
            deleteQuietly(output);
        }
    }

    private void replaceOriginal(File video, File sensor, File output) throws Exception {
        File replacement = new File(video.getParentFile(), "." + video.getName() + ".rokid-new");
        File videoBackup = new File(video.getParentFile(), "." + video.getName() + ".rokid-old");
        File sensorBackup = new File(sensor.getParentFile(), "." + sensor.getName() + ".rokid-old");
        deleteIfExists(replacement);
        deleteIfExists(videoBackup);
        deleteIfExists(sensorBackup);
        copyFile(output, replacement);
        if (replacement.length() == 0) throw new Exception("Neue Videodatei ist leer");

        boolean videoBackedUp = false;
        boolean sensorBackedUp = false;
        boolean replacementInstalled = false;
        try {
            if (!video.renameTo(videoBackup)) throw new Exception("Originalvideo konnte nicht gesichert werden");
            videoBackedUp = true;
            if (!sensor.renameTo(sensorBackup)) throw new Exception("TXT-Datei konnte nicht entfernt werden");
            sensorBackedUp = true;
            if (!replacement.renameTo(video)) throw new Exception("Neues Video konnte nicht eingesetzt werden");
            replacementInstalled = true;
            deleteQuietly(videoBackup);
            deleteQuietly(sensorBackup);
        } catch (Exception error) {
            if (replacementInstalled) deleteQuietly(video);
            if (sensorBackedUp && !sensor.exists()) sensorBackup.renameTo(sensor);
            if (videoBackedUp && !video.exists()) videoBackup.renameTo(video);
            deleteQuietly(replacement);
            throw error;
        }
    }

    private void publishFailure(VideoPair pair, String reason) {
        publish(status.processed, status.total, 0, pair.video.getName(),
                "Fehler bei " + pair.video.getName() + ": " + reason);
    }

    private void publish(int processed, int total, int percent, String currentName,
                         String message) {
        Status next = new Status(running, false, total, processed,
                Math.max(0, Math.min(100, percent)), currentName, message);
        synchronized (stateLock) {
            status = next;
        }
        for (Listener listener : listeners) listener.onStatus(next);
        if (running) updateNotification(message, next.currentPercent);
    }

    private void finishSynchronization(String message, int processed, int total) {
        running = false;
        Status finished = new Status(false, true, total, processed,
                total == 0 ? 0 : 100, "", message);
        synchronized (stateLock) {
            status = finished;
        }
        for (Listener listener : listeners) listener.onStatus(finished);
        releaseWakeLock();
        stopForeground(true);
        stopSelf();
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void acquireWakeLock() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                getPackageName() + ":synchronization");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Rokid Synchronization", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Fortschritt der Rokid-Videosynchronisierung");
        notificationManager.createNotificationChannel(channel);
    }

    private Notification notification(String message, int percent) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Rokid Synchronization")
                .setContentText(message)
                .setOngoing(true)
                .setProgress(100, percent, false);
        return builder.build();
    }

    private void updateNotification(String message, int percent) {
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, notification(message, percent));
        }
    }

    private void copyFile(File source, File destination) throws IOException {
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
    }

    private void deleteIfExists(File file) throws IOException {
        if (file.exists() && !file.delete()) throw new IOException("Datei konnte nicht gelöscht werden: " + file.getName());
    }

    private void deleteQuietly(File file) {
        if (file != null && file.exists()) file.delete();
    }
}
