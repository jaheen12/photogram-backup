package com.photogram.backup;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
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
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class BackupWorker extends Worker {

    private static final String CHANNEL_ID = "sync_channel";
    private static final int NOTIF_ID = 1;

    private final SharedPreferences prefs;
    private final SharedPreferences history;
    private final DatabaseHelper dbHelper;
    private final NotificationManager notificationManager;

    public BackupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
        this.prefs = context.getSharedPreferences("BackupPrefs", Context.MODE_PRIVATE);
        this.history = context.getSharedPreferences("HistoryPrefs", Context.MODE_PRIVATE);
        this.dbHelper = new DatabaseHelper(context);
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @NonNull
    @Override
    public Result doWork() {
        // 1. Failsafe Check
        boolean isManual = getInputData().getBoolean("is_manual", false);
        long lastSync = prefs.getLong("last_sync_timestamp", 0);
        int intervalMins = prefs.getInt("sync_interval", 60);

        if (!isManual && (System.currentTimeMillis() - lastSync < TimeUnit.MINUTES.toMillis(intervalMins))) {
            return Result.success();
        }

        // 2. Setup Notification
        createNotificationChannel();
        setForegroundAsync(createForegroundInfo("Starting persistent sync..."));

        // Use the Secure Injected Token or Custom User Token
        String userToken = prefs.getString("custom_bot_token", "");
        final String token = (userToken != null && !userToken.isEmpty()) ? userToken : BuildConfig.BOT_TOKEN;
        String chatId = prefs.getString("chat_id", "");

        if (token.isEmpty() || chatId.isEmpty()) return Result.failure();

        TelegramHelper helper = new TelegramHelper(token, chatId);
        int uploadedCount = 0;

        try {
            uploadedCount = scanAndUploadPersistent(Environment.getExternalStorageDirectory(), helper);
        } catch (Exception e) {
            dbHelper.addLog("ERROR", "Backup Interrupted: " + e.getMessage());
            return Result.retry();
        }

        prefs.edit().putLong("last_sync_timestamp", System.currentTimeMillis()).apply();
        dbHelper.addLog("SUCCESS", "Sync Complete. " + uploadedCount + " new photos saved.");
        showNotification("Photogram Sync", "Backup complete • " + uploadedCount + " new items");
        
        return Result.success();
    }

    private int scanAndUploadPersistent(File root, TelegramHelper helper) throws Exception {
        int count = 0;
        Deque<File> stack = new ArrayDeque<>();
        stack.push(root);

        // Fetch the Registry from Telegram once at the start
        Map<String, String> remoteRegistry = helper.getTopicRegistry();

        while (!stack.isEmpty() && !isStopped()) {
            File dir = stack.pop();
            File[] files = dir.listFiles();
            if (files == null) continue;

            boolean enabled = prefs.getBoolean(dir.getAbsolutePath(), false);

            if (enabled) {
                String threadId = getOrCreateTopicPersistent(dir, helper, remoteRegistry);
                
                for (File f : files) {
                    if (isStopped()) break;
                    if (f.isDirectory()) {
                        if (!f.getName().startsWith(".") && !f.getName().equalsIgnoreCase("Android")) {
                            stack.push(f);
                        }
                    } else if (isImage(f)) {
                        String fileKey = f.getAbsolutePath() + "_" + f.lastModified();
                        // Use Database for history to handle re-installs
                        if (!dbHelper.isFileUploaded(f.getAbsolutePath(), f.lastModified())) {
                            if (helper.uploadPhoto(f, threadId)) {
                                dbHelper.markAsUploaded(f.getAbsolutePath(), f.lastModified());
                                count++;
                                Thread.sleep(3000); // Flood Control
                                if (count % 3 == 0) showNotification("Syncing...", "Saved " + count + " items");
                            }
                        }
                    }
                }
            } else {
                // If not enabled, we still push subfolders to the stack to keep scanning
                for (File f : files) {
                    if (f.isDirectory() && !f.getName().startsWith(".") && !f.getName().equalsIgnoreCase("Android")) {
                        stack.push(f);
                    }
                }
            }
        }
        return count;
    }

    private String getOrCreateTopicPersistent(File dir, TelegramHelper helper, Map<String, String> remoteRegistry) throws Exception {
        String localKey = "topic_" + dir.getAbsolutePath();
        String threadId = prefs.getString(localKey, "");

        if (threadId.isEmpty()) {
            // Check if Telegram's pinned registry knows about this folder
            if (remoteRegistry.containsKey(dir.getName())) {
                threadId = remoteRegistry.get(dir.getName());
                dbHelper.addLog("INFO", "Restored topic ID for: " + dir.getName());
            } else {
                // Create brand new
                dbHelper.addLog("TOPIC", "Creating brand new topic: " + dir.getName());
                threadId = helper.createTopic(dir.getName());
                // Update remote registry immediately
                remoteRegistry.put(dir.getName(), threadId);
                helper.saveTopicRegistry(remoteRegistry);
            }
            // Cache locally
            prefs.edit().putString(localKey, threadId).apply();
        }
        return threadId;
    }

    private boolean isImage(File f) {
        String n = f.getName().toLowerCase(Locale.US);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".heic");
    }

    private ForegroundInfo createForegroundInfo(String text) {
        Notification n = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Photogram")
                .setContentText(text)
                .setOngoing(true).build();
        return new ForegroundInfo(NOTIF_ID, n);
    }

    private void showNotification(String title, String msg) {
        Notification n = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title).setContentText(msg).setPriority(NotificationCompat.PRIORITY_LOW).build();
        notificationManager.notify(NOTIF_ID, n);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "Sync Status", NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(c);
        }
    }
}