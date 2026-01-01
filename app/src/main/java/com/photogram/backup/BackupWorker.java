package com.photogram.backup;

import android.app.*;
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
        this.dbHelper = new DatabaseHelper(context);
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @NonNull
    @Override
    public Result doWork() {
        boolean isManual = getInputData().getBoolean("is_manual", false);
        long lastSyncSeconds = prefs.getLong("last_sync_timestamp", 0) / 1000; // MediaStore uses seconds
        int intervalMins = prefs.getInt("sync_interval", 60);

        // Failsafe timer check
        if (!isManual && (System.currentTimeMillis() / 1000 - lastSyncSeconds < TimeUnit.MINUTES.toSeconds(intervalMins))) {
            return Result.success();
        }

        createNotificationChannel();
        setForegroundAsync(createForegroundInfo("Querying new media..."));

        String token = prefs.getString("bot_token", "");
        String chatId = prefs.getString("chat_id", "");
        if (token.isEmpty() || chatId.isEmpty()) return Result.failure();

        TelegramHelper helper = new TelegramHelper(token, chatId);
        int uploadedCount = scanMediaStoreAndUpload(lastSyncSeconds, helper);

        prefs.edit().putLong("last_sync_timestamp", System.currentTimeMillis()).apply();
        showNotification("Photogram Sync", "Delta Backup complete • " + uploadedCount + " new items");

        return Result.success();
    }

    private int scanMediaStoreAndUpload(long sinceTimestampSeconds, TelegramHelper helper) {
        int count = 0;
        ContentResolver contentResolver = context.getContentResolver();

        // Query only for Images
        Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        
        // We only want: File Path and Date Modified
        String[] projection = {
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DATE_MODIFIED
        };

        // DELTA LOGIC: Only files newer than our last sync
        String selection = MediaStore.Images.Media.DATE_MODIFIED + " > ?";
        String[] selectionArgs = new String[]{String.valueOf(sinceTimestampSeconds)};
        String sortOrder = MediaStore.Images.Media.DATE_MODIFIED + " ASC";

        try (Cursor cursor = contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)) {
            if (cursor != null && cursor.moveToFirst()) {
                int dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
                int dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED);

                do {
                    if (isStopped()) break;

                    String filePath = cursor.getString(dataColumn);
                    long modifiedTime = cursor.getLong(dateColumn);
                    File file = new File(filePath);
                    File parentDir = file.getParentFile();

                    if (parentDir != null && prefs.getBoolean(parentDir.getAbsolutePath(), false)) {
                        // Check Database history
                        if (!dbHelper.isFileUploaded(filePath, modifiedTime)) {
                            String threadId = getOrCreateTopic(parentDir, helper);
                            if (!threadId.isEmpty() && helper.uploadPhoto(file, threadId)) {
                                dbHelper.markAsUploaded(filePath, modifiedTime);
                                count++;
                                if (count % 3 == 0) showNotification("Photogram Sync", "Uploading new media...");
                            }
                        }
                    }
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return count;
    }

    private String getOrCreateTopic(File dir, TelegramHelper helper) {
        String threadId = prefs.getString("topic_" + dir.getAbsolutePath(), "");
        if (threadId.isEmpty()) {
            try {
                threadId = helper.createTopic(dir.getName());
                prefs.edit().putString("topic_" + dir.getAbsolutePath(), threadId).apply();
            } catch (Exception e) {
                return "";
            }
        }
        return threadId;
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

    private void showNotification(String title, String msg) {
        Notification notification = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(msg)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        notificationManager.notify(NOTIF_ID, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Sync Status", NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
        }
    }
}