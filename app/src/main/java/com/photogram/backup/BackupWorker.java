package com.photogram.backup;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
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
        // 1. Token Setup (Hybrid Model)
        String userToken = prefs.getString("custom_bot_token", "");
        final String token = (userToken != null && !userToken.isEmpty()) ? userToken : BuildConfig.BOT_TOKEN;
        String chatId = prefs.getString("chat_id", "");

        // 2. Initial Checks
        boolean isManual = getInputData().getBoolean("is_manual", false);
        long lastSyncSeconds = prefs.getLong("last_sync_timestamp", 0) / 1000;
        int intervalMins = prefs.getInt("sync_interval", 60);

        if (!isManual && (System.currentTimeMillis() / 1000 - lastSyncSeconds < TimeUnit.MINUTES.toSeconds(intervalMins))) {
            return Result.success();
        }

        if (token == null || token.isEmpty() || chatId.isEmpty()) {
            dbHelper.addLog("ERROR", "Backup failed: Missing Bot Token or Chat ID.");
            return Result.failure();
        }

        // 3. Start Sync with Foreground Status
        createNotificationChannel();
        setForegroundAsync(createForegroundInfo("Restoring cloud memory..."));
        
        TelegramHelper helper = new TelegramHelper(token, chatId);
        int uploadedCount = 0;

        try {
            // A. Fetch Registry from Telegram Pinned Message
            Map<String, String> registry = helper.getTopicRegistry();

            // B. Restore History if the local database is empty (e.g., after reinstall)
            if (dbHelper.getTotalBackupCount() == 0 && registry.containsKey("CLOUD_HISTORY_ID")) {
                dbHelper.addLog("INFO", "Restoring photo history from Telegram...");
                String historyJson = helper.downloadHistoryFile(registry.get("CLOUD_HISTORY_ID"));
                dbHelper.importHistoryFromJson(historyJson);
                dbHelper.addLog("SUCCESS", "Memory restored! Skipping old photos.");
            }

            // C. Perform Delta Sync (Upload only new photos)
            uploadedCount = performDeltaSync(lastSyncSeconds, helper, registry);

            // D. Backup updated history back to Telegram Cloud
            if (uploadedCount > 0 || !registry.containsKey("CLOUD_HISTORY_ID")) {
                dbHelper.addLog("INFO", "Saving memory to Telegram...");
                String newFileId = helper.uploadHistoryFile(dbHelper.exportHistoryToJson());
                if (newFileId != null) {
                    registry.put("CLOUD_HISTORY_ID", newFileId);
                    helper.saveTopicRegistry(registry);
                }
            }

            // 4. Wrap up
            prefs.edit().putLong("last_sync_timestamp", System.currentTimeMillis()).apply();
            dbHelper.addLog("SUCCESS", "Backup complete. Saved " + uploadedCount + " items.");
            showNotification("Photogram Sync", "Backup Complete! " + uploadedCount + " items.");

        } catch (Exception e) {
            dbHelper.addLog("RETRY", "Sync failed: " + e.getMessage());
            return Result.retry(); 
        }

        return Result.success();
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
                int dataIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
                int dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED);

                do {
                    if (isStopped()) break;

                    String filePath = cursor.getString(dataIdx);
                    long modifiedTime = cursor.getLong(dateIdx);
                    File file = new File(filePath);
                    File parent = file.getParentFile();

                    if (parent != null && prefs.getBoolean(parent.getAbsolutePath(), false)) {
                        if (!dbHelper.isFileUploaded(filePath, modifiedTime)) {
                            String threadId = getTopicFromRegistry(parent, helper, registry);
                            
                            if (!threadId.isEmpty() && helper.uploadPhoto(file, threadId)) {
                                dbHelper.markAsUploaded(filePath, modifiedTime);
                                count++;
                                Thread.sleep(3000); // Telegram Flood Control
                                if (count % 2 == 0) showNotification("Syncing...", "Saved " + count + " items");
                            }
                        }
                    }
                } while (cursor.moveToNext());
            }
        }
        return count;
    }

    private String getTopicFromRegistry(File dir, TelegramHelper helper, Map<String, String> registry) throws Exception {
        String name = dir.getName();
        // Check cloud registry first
        if (registry.containsKey(name)) {
            return registry.get(name);
        }
        
        // Create new topic if not in cloud
        dbHelper.addLog("TOPIC", "Creating new topic for: " + name);
        String id = helper.createTopic(name);
        registry.put(name, id);
        helper.saveTopicRegistry(registry);
        return id;
    }

    private void showNotification(String title, String msg) {
        Notification notification = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(msg)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .build();
        notificationManager.notify(NOTIF_ID, notification);
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