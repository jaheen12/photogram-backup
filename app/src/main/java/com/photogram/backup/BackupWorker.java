package com.photogram.backup;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.io.File;

public class BackupWorker extends Worker {
    private SharedPreferences prefs;
    private SharedPreferences history;
    private NotificationManager notificationManager;
    private static final String CHANNEL_ID = "sync_channel";

    public BackupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
        prefs = context.getSharedPreferences("BackupPrefs", Context.MODE_PRIVATE);
        history = context.getSharedPreferences("HistoryPrefs", Context.MODE_PRIVATE);
        notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @NonNull
    @Override
    public Result doWork() {
        createNotificationChannel();
        showNotification("Photogram Sync", "Starting backup...");

        String token = prefs.getString("bot_token", "");
        String chatId = prefs.getString("chat_id", "");
        if (token.isEmpty() || chatId.isEmpty()) return Result.failure();

        TelegramHelper helper = new TelegramHelper(token, chatId);
        int uploaded = scanAndUpload(android.os.Environment.getExternalStorageDirectory(), helper, 0);

        showNotification("Photogram Sync", "Backup Complete! " + uploaded + " new photos saved.");
        return Result.success();
    }

    private int scanAndUpload(File dir, TelegramHelper helper, int count) {
        File[] files = dir.listFiles();
        if (files == null) return count;

        if (prefs.getBoolean(dir.getAbsolutePath(), false)) {
            try {
                String threadId = prefs.getString("topic_" + dir.getAbsolutePath(), "");
                if (threadId.isEmpty()) {
                    threadId = helper.createTopic(dir.getName());
                    prefs.edit().putString("topic_" + dir.getAbsolutePath(), threadId).apply();
                }

                for (File f : files) {
                    if (f.isFile() && isImage(f)) {
                        String fileId = f.getAbsolutePath() + "_" + f.length();
                        if (!history.getBoolean(fileId, false)) {
                            if (helper.uploadPhoto(f, threadId)) {
                                history.edit().putBoolean(fileId, true).apply();
                                count++;
                                showNotification("Photogram Sync", "Uploading: " + f.getName());
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        for (File f : files) {
            if (f.isDirectory() && !f.getName().startsWith(".") && !f.getName().equalsIgnoreCase("Android")) {
                count = scanAndUpload(f, helper, count);
            }
        }
        return count;
    }

    private boolean isImage(File f) {
        String n = f.getName().toLowerCase();
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp");
    }

    private void showNotification(String title, String message) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true);
        notificationManager.notify(1, builder.build());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Sync Status", NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
        }
    }
}