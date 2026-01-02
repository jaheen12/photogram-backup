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
        // --- HYBRID TOKEN LOGIC ---
        String userToken = prefs.getString("custom_bot_token", "");
        final String token = (userToken != null && !userToken.isEmpty()) ? userToken : BuildConfig.BOT_TOKEN;

        boolean isManual = getInputData().getBoolean("is_manual", false);
        long lastSyncSec = prefs.getLong("last_sync_timestamp", 0) / 1000;
        int intervalMins = prefs.getInt("sync_interval", 60);

        if (!isManual && (System.currentTimeMillis() / 1000 - lastSyncSec < TimeUnit.MINUTES.toSeconds(intervalMins))) {
            return Result.success();
        }

        String chatId = prefs.getString("chat_id", "");
        if (chatId.isEmpty() || token == null || token.isEmpty()) {
            dbHelper.addLog("ERROR", "Auth Failed: Missing Token or Chat ID.");
            return Result.failure();
        }

        createNotificationChannel();
        setForegroundAsync(createForegroundInfo("Processing Photogram sync..."));

        TelegramHelper helper = new TelegramHelper(token, chatId);
        int count = 0;

        try {
            count = performDeltaSync(lastSyncSec, helper);
            prefs.edit().putLong("last_sync_timestamp", System.currentTimeMillis()).apply();
            dbHelper.addLog("SUCCESS", "Uploaded " + count + " new items.");
            showNotification("Photogram Sync", "Complete • " + count + " items saved.");
        } catch (Exception e) {
            dbHelper.addLog("RETRY", "Network Error: " + e.getMessage());
            return Result.retry();
        }

        return Result.success();
    }

    private int performDeltaSync(long since, TelegramHelper helper) throws Exception {
        int count = 0;
        ContentResolver resolver = context.getContentResolver();
        Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String selection = MediaStore.Images.Media.DATE_MODIFIED + " > ?";
        String[] args = {String.valueOf(since)};
        
        try (Cursor cursor = resolver.query(uri, new String[]{MediaStore.Images.Media.DATA, MediaStore.Images.Media.DATE_MODIFIED}, selection, args, MediaStore.Images.Media.DATE_MODIFIED + " ASC")) {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    if (isStopped()) break;
                    String path = cursor.getString(0);
                    long mod = cursor.getLong(1);
                    File file = new File(path);
                    File parent = file.getParentFile();

                    if (parent != null && prefs.getBoolean(parent.getAbsolutePath(), false)) {
                        if (!dbHelper.isFileUploaded(path, mod)) {
                            String tid = getTopic(parent, helper);
                            if (!tid.isEmpty() && helper.uploadPhoto(file, tid)) {
                                dbHelper.markAsUploaded(path, mod);
                                count++;
                                Thread.sleep(3000); // Flood Control
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
            dbHelper.addLog("TOPIC", "Created: " + dir.getName());
        }
        return id;
    }

    private void showNotification(String title, String msg) {
        Notification n = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title).setContentText(msg).setPriority(NotificationCompat.PRIORITY_LOW).setSilent(true).build();
        notificationManager.notify(NOTIF_ID, n);
    }

    private ForegroundInfo createForegroundInfo(String text) {
        Notification n = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Photogram").setContentText(text).setOngoing(true).build();
        return new ForegroundInfo(NOTIF_ID, n);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "Sync Status", NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(c);
        }
    }
}