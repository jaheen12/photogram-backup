package com.photogram.backup;

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
import java.io.File;
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
        // Using the new Phase 5 robust database
        this.dbHelper = new DatabaseHelper(context);
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @NonNull
    @Override
    public Result doWork() {
        // 1. Initial Checks (Failsafe & Auth)
        boolean isManual = getInputData().getBoolean("is_manual", false);
        long lastSyncSeconds = prefs.getLong("last_sync_timestamp", 0) / 1000;
        int intervalMins = prefs.getInt("sync_interval", 60);

        if (!isManual && (System.currentTimeMillis() / 1000 - lastSyncSeconds < TimeUnit.MINUTES.toSeconds(intervalMins))) {
            return Result.success();
        }

        String token = prefs.getString("bot_token", "");
        String chatId = prefs.getString("chat_id", "");
        if (token.isEmpty() || chatId.isEmpty()) {
            dbHelper.addLog("ERROR", "Backup failed: Bot Token or Chat ID is missing.");
            return Result.failure();
        }

        // 2. Start Sync with Notification
        createNotificationChannel();
        setForegroundAsync(createForegroundInfo("Starting smart sync..."));
        dbHelper.addLog("INFO", "Backup started" + (isManual ? " (Manual)" : " (Scheduled)"));

        TelegramHelper helper = new TelegramHelper(token, chatId);
        int uploadedCount = 0;

        try {
            uploadedCount = performDeltaSync(lastSyncSeconds, helper);
            
            // 3. Finalize
            prefs.edit().putLong("last_sync_timestamp", System.currentTimeMillis()).apply();
            dbHelper.addLog("SUCCESS", "Backup complete. " + uploadedCount + " new photos saved.");
            showNotification("Photogram Sync", "Backup Complete! " + uploadedCount + " new items.");
            
        } catch (Exception e) {
            dbHelper.addLog("RETRY", "Network error: " + e.getMessage());
            // This tells WorkManager to try again later with Exponential Backoff
            return Result.retry(); 
        }

        return Result.success();
    }

    private int performDeltaSync(long sinceTimestamp, TelegramHelper helper) throws Exception {
        int count = 0;
        ContentResolver resolver = context.getContentResolver();
        Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        
        String[] projection = {MediaStore.Images.Media.DATA, MediaStore.Images.Media.DATE_MODIFIED};
        String selection = MediaStore.Images.Media.DATE_MODIFIED + " > ?";
        String[] selectionArgs = {String.valueOf(sinceTimestamp)};
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

                    // Check if the user has enabled backup for this specific folder
                    if (parent != null && prefs.getBoolean(parent.getAbsolutePath(), false)) {
                        
                        // Check Database to prevent duplicates
                        if (!dbHelper.isFileUploaded(filePath, modifiedTime)) {
                            String threadId = getOrCreateTopic(parent, helper);
                            
                            if (!threadId.isEmpty() && helper.uploadPhoto(file, threadId)) {
                                dbHelper.markAsUploaded(filePath, modifiedTime);
                                count++;
                                
                                // TELEGRAM FLOOD CONTROL: 
                                // We pause for 3 seconds to avoid being flagged as spam by Telegram
                                Thread.sleep(3000); 
                                
                                if (count % 2 == 0) {
                                    showNotification("Photogram Syncing", "Uploaded " + count + " photos...");
                                }
                            }
                        }
                    }
                } while (cursor.moveToNext());
            }
        }
        return count;
    }

    private String getOrCreateTopic(File dir, TelegramHelper helper) throws Exception {
        String key = "topic_" + dir.getAbsolutePath();
        String id = prefs.getString(key, "");
        if (id.isEmpty()) {
            dbHelper.addLog("TOPIC", "Creating new topic for: " + dir.getName());
            id = helper.createTopic(dir.getName());
            prefs.edit().putString(key, id).apply();
        }
        return id;
    }

    private void showNotification(String title, String msg) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(msg)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true);
        notificationManager.notify(NOTIF_ID, builder.build());
    }

    private ForegroundInfo createForegroundInfo(String text) {
        Notification notification = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Photogram Syncing")
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