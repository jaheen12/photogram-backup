package com.photogram.backup;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.work.Data;
import java.io.File;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class BackupWorker extends Worker {

    private static final String CHANNEL_ID = "sync_channel";
    private static final int NOTIF_ID = 1;

    private final SharedPreferences prefs;
    private final DatabaseHelper dbHelper;
    private final NotificationManager notificationManager;
    private final Context context;

    public BackupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
        this.context = context;
        this.prefs = context.getSharedPreferences("BackupPrefs", Context.MODE_PRIVATE);
        this.dbHelper = new DatabaseHelper(context);
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @NonNull
    @Override
    public Result doWork() {
        // --- FAILSAFE 1: HARDWARE NETWORK CHECK ---
        boolean onlyWifi = prefs.getBoolean("only_wifi", false);
        if (onlyWifi && !isWifiConnected()) {
            dbHelper.addLog("INFO", "Sync paused: Waiting for Wi-Fi connection.");
            // Returning retry tells WorkManager to try again when constraints are met
            return Result.retry(); 
        }

        // --- FAILSAFE 2: RECENT SYNC CHECK ---
        boolean isManual = getInputData().getBoolean("is_manual", false);
        long lastSyncSeconds = prefs.getLong("last_sync_timestamp", 0) / 1000;
        int intervalMins = prefs.getInt("sync_interval", 60);

        if (!isManual && (System.currentTimeMillis() / 1000 - lastSyncSeconds < TimeUnit.MINUTES.toSeconds(intervalMins))) {
            return Result.success();
        }

        // --- TOKEN & AUTH ---
        String userToken = prefs.getString("custom_bot_token", "");
        final String token = (userToken != null && !userToken.isEmpty()) ? userToken : BuildConfig.BOT_TOKEN;
        String chatId = prefs.getString("chat_id", "");

        if (token == null || token.isEmpty() || chatId.isEmpty()) {
            dbHelper.addLog("ERROR", "Backup failed: Missing Token or Chat ID.");
            return Result.failure();
        }

        // --- START SYNC ---
        createNotificationChannel();
        setForegroundAsync(createForegroundInfo("Safe Cloud Syncing..."));
        dbHelper.addLog("INFO", "Sync started" + (isManual ? " (Manual)" : " (Scheduled)"));
        
        TelegramHelper helper = new TelegramHelper(token, chatId);
        int uploadedCount = 0;

        try {
            // A. Get Pinned Registry from Telegram
            Map<String, String> registry = helper.getTopicRegistry();

            // B. Restore History if local DB is empty
            if (dbHelper.getTotalBackupCount() == 0 && registry.containsKey("CLOUD_HISTORY_ID")) {
                dbHelper.addLog("INFO", "Downloading history from Telegram...");
                String historyJson = helper.downloadHistoryFile(registry.get("CLOUD_HISTORY_ID"));
                dbHelper.importHistoryFromJson(historyJson);
                dbHelper.addLog("SUCCESS", "History restored. Skipping old items.");
            }

            // C. Perform Delta Sync with Progress Updates
            uploadedCount = performDeltaSync(lastSyncSeconds, helper, registry);

            // D. Save updated history to Telegram Cloud
            if (uploadedCount > 0 || !registry.containsKey("CLOUD_HISTORY_ID")) {
                dbHelper.addLog("INFO", "Updating cloud history file...");
                String newFileId = helper.uploadHistoryFile(dbHelper.exportHistoryToJson());
                if (newFileId != null) {
                    registry.put("CLOUD_HISTORY_ID", newFileId);
                    helper.saveTopicRegistry(registry);
                }
            }

            // Wrap up
            prefs.edit().putLong("last_sync_timestamp", System.currentTimeMillis()).apply();
            dbHelper.addLog("SUCCESS", "Backup complete. Saved " + uploadedCount + " items.");
            return Result.success();

        } catch (Exception e) {
            dbHelper.addLog("RETRY", "Sync interrupted: " + e.getMessage());
            return Result.retry(); 
        }
    }

    private int performDeltaSync(long since, TelegramHelper helper, Map<String, String> registry) throws Exception {
        int count = 0;
        ContentResolver resolver = context.getContentResolver();
        Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        
        String[] projection = {MediaStore.Images.Media.DATA, MediaStore.Images.Media.DATE_MODIFIED};
        String selection = MediaStore.Images.Media.DATE_MODIFIED + " > ?";
        String[] selectionArgs = {String.valueOf(since)};
        String sortOrder = MediaStore.Images.Media.DATE_MODIFIED + " ASC";

        try (Cursor cursor = resolver.query(uri, projection, selection, selectionArgs, sortOrder)) {
            if (cursor != null && cursor.moveToFirst()) {
                int totalItems = cursor.getCount();
                int currentIndex = 0;

                do {
                    if (isStopped()) break;

                    String filePath = cursor.getString(0);
                    long modifiedTime = cursor.getLong(1);
                    File file = new File(filePath);
                    File parent = file.getParentFile();

                    if (parent != null && prefs.getBoolean(parent.getAbsolutePath(), false)) {
                        if (!dbHelper.isFileUploaded(filePath, modifiedTime)) {
                            
                            // Send progress to Dashboard
                            Data progress = new Data.Builder()
                                .putString("current_file", file.getName())
                                .putInt("progress_percent", (int) ((currentIndex / (float) totalItems) * 100))
                                .build();
                            setProgressAsync(progress);

                            String threadId = getTopicFromRegistry(parent, helper, registry);
                            if (!threadId.isEmpty() && helper.uploadPhoto(file, threadId)) {
                                dbHelper.markAsUploaded(filePath, modifiedTime);
                                count++;
                                Thread.sleep(3000); // Flood control
                            }
                        }
                    }
                    currentIndex++;
                } while (cursor.moveToNext());
            }
        }
        return count;
    }

    private String getTopicFromRegistry(File dir, TelegramHelper helper, Map<String, String> registry) throws Exception {
        String name = dir.getName();
        if (registry.containsKey(name)) return registry.get(name);
        
        String id = helper.createTopic(name);
        registry.put(name, id);
        helper.saveTopicRegistry(registry);
        return id;
    }

    private boolean isWifiConnected() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = cm.getActiveNetwork();
            NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
            return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } else {
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected() && info.getType() == ConnectivityManager.TYPE_WIFI;
        }
    }

    private ForegroundInfo createForegroundInfo(String text) {
        Notification notification = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Photogram Sync")
                .setContentText(text)
                .setOngoing(true)
                .build();
        return new ForegroundInfo(NOTIF_ID, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Sync Status", NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
        }
    }
}