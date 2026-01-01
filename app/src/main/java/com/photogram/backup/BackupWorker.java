package com.photogram.backup;

import android.app.*;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.database.Cursor;
import android.content.ContentResolver;
import android.net.Uri;
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
        this.dbHelper = new DatabaseHelper(context);
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @NonNull
    @Override
    public Result doWork() {
        boolean isManual = getInputData().getBoolean("is_manual", false);
        long lastSyncSeconds = prefs.getLong("last_sync_timestamp", 0) / 1000;
        int intervalMins = prefs.getInt("sync_interval", 60);

        if (!isManual && (System.currentTimeMillis() / 1000 - lastSyncSeconds < TimeUnit.MINUTES.toSeconds(intervalMins))) {
            return Result.success();
        }

        createNotificationChannel();
        setForegroundAsync(createForegroundInfo("Syncing with Telegram..."));

        String token = prefs.getString("bot_token", "");
        String chatId = prefs.getString("chat_id", "");
        if (token.isEmpty() || chatId.isEmpty()) {
            dbHelper.addLog("AUTH", "Bot Token or Chat ID is missing.");
            return Result.failure();
        }

        TelegramHelper helper = new TelegramHelper(token, chatId);
        int uploadedCount = 0;

        try {
            uploadedCount = performDeltaSync(lastSyncSeconds, helper);
            dbHelper.addLog("SUCCESS", "Backup finished. Uploaded " + uploadedCount + " photos.");
        } catch (Exception e) {
            dbHelper.addLog("RETRY", "Connection lost. Will retry later.");
            return Result.retry(); // Triggers Exponential Backoff
        }

        prefs.edit().putLong("last_sync_timestamp", System.currentTimeMillis()).apply();
        showNotification("Photogram Sync", "Delta Backup complete • " + uploadedCount + " items");

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

                    if (parent != null && prefs.getBoolean(parent.getAbsolutePath(), false)) {
                        if (!dbHelper.isFileUploaded(filePath, modifiedTime)) {
                            String threadId = getTopic(parent, helper);
                            if (!threadId.isEmpty() && helper.uploadPhoto(file, threadId)) {
                                dbHelper.markAsUploaded(filePath, modifiedTime);
                                count++;
                                // FLOOD CONTROL: Pause 3 seconds between photos
                                Thread.sleep(3000); 
                                if (count % 3 == 0) showNotification("Syncing...", "Saved " + count + " photos");
                            }
                        }
                    }
                } while (cursor.moveToNext());
            }
        }
        return count;
    }

    private String getTopic(File dir, TelegramHelper helper) throws Exception {
        String key = "topic_" + dir.getAbsolutePath();
        String id = prefs.getString(key, "");
        if (id.isEmpty()) {
            id = helper.createTopic(dir.getName());
            prefs.edit().putString(key, id).apply();
            dbHelper.addLog("TOPIC", "Created new topic for: " + dir.getName());
        }
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
                .setContentTitle("Photogram")
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