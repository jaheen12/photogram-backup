package com.photogram.backup;

import android.app.*;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class BackupWorker extends Worker {

    private static final String CHANNEL_ID = "sync_channel";
    private static final int NOTIF_ID = 1;

    private final SharedPreferences prefs;
    private final DatabaseHelper dbHelper; // Our new Librarian
    private final NotificationManager notificationManager;

    public BackupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
        prefs = context.getSharedPreferences("BackupPrefs", Context.MODE_PRIVATE);
        dbHelper = new DatabaseHelper(context); // Initialize DB
        notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @NonNull
    @Override
    public Result doWork() {
        boolean isManual = getInputData().getBoolean("is_manual", false);
        long lastSync = prefs.getLong("last_sync_timestamp", 0);
        int intervalMins = prefs.getInt("sync_interval", 60);

        if (!isManual && (System.currentTimeMillis() - lastSync < TimeUnit.MINUTES.toMillis(intervalMins))) {
            return Result.success();
        }

        createNotificationChannel();
        setForegroundAsync(createForegroundInfo("Starting full secure scan..."));

        String token = prefs.getString("bot_token", "");
        String chatId = prefs.getString("chat_id", "");
        if (token.isEmpty() || chatId.isEmpty()) return Result.failure();

        TelegramHelper helper = new TelegramHelper(token, chatId);
        int uploaded = 0;

        try {
            uploaded = scanAndUploadIterative(Environment.getExternalStorageDirectory(), helper);
        } catch (Exception e) {
            return Result.retry();
        }

        prefs.edit().putLong("last_sync_timestamp", System.currentTimeMillis()).apply();
        showNotification("Photogram Sync", "Backup complete • " + uploaded + " new photos");
        
        return Result.success();
    }

    private int scanAndUploadIterative(File root, TelegramHelper helper) {
        int count = 0;
        Deque<File> stack = new ArrayDeque<>();
        stack.push(root);

        while (!stack.isEmpty() && !isStopped()) {
            File dir = stack.pop();
            File[] files = dir.listFiles();
            if (files == null) continue;

            boolean enabled = prefs.getBoolean(dir.getAbsolutePath(), false);
            String threadId = "";

            if (enabled) {
                threadId = prefs.getString("topic_" + dir.getAbsolutePath(), "");
                try {
                    if (threadId.isEmpty()) {
                        threadId = helper.createTopic(dir.getName());
                        prefs.edit().putString("topic_" + dir.getAbsolutePath(), threadId).apply();
                    }
                } catch (Exception e) { enabled = false; }
            }

            for (File f : files) {
                if (isStopped()) break;
                if (f.isDirectory()) {
                    if (!f.getName().startsWith(".") && !f.getName().equalsIgnoreCase("Android")) {
                        stack.push(f);
                    }
                    continue;
                }

                // --- DATABASE CHECK ---
                if (enabled && isImage(f)) {
                    if (!dbHelper.isFileUploaded(f.getAbsolutePath(), f.lastModified())) {
                        try {
                            if (helper.uploadPhoto(f, threadId)) {
                                dbHelper.markAsUploaded(f.getAbsolutePath(), f.lastModified());
                                count++;
                                if (count % 5 == 0) {
                                    showNotification("Photogram Sync", "Uploaded " + count + " photos...");
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
        return count;
    }

    private boolean isImage(File f) {
        String name = f.getName().toLowerCase(Locale.US);
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp") || name.endsWith(".heic");
    }

    private ForegroundInfo createForegroundInfo(String text) {
        Notification notification = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Photogram Background Sync")
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