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
        String userToken = prefs.getString("custom_bot_token", "");
        final String token = (userToken != null && !userToken.isEmpty()) ? userToken : BuildConfig.BOT_TOKEN;
        String chatId = prefs.getString("chat_id", "");
        if (token.isEmpty() || chatId.isEmpty()) return Result.failure();

        createNotificationChannel();
        setForegroundAsync(createForegroundInfo("Preparing Photogram sync..."));
        TelegramHelper helper = new TelegramHelper(token, chatId);

        try {
            Map<String, String> registry = helper.getTopicRegistry();
            
            // Restore history if needed
            if (dbHelper.getTotalBackupCount() == 0 && registry.containsKey("CLOUD_HISTORY_ID")) {
                String historyJson = helper.downloadHistoryFile(registry.get("CLOUD_HISTORY_ID"));
                dbHelper.importHistoryFromJson(historyJson);
            }

            // Perform Delta Sync with REAL-TIME UPDATES
            int uploadedCount = performDeltaSync(prefs.getLong("last_sync_timestamp", 0) / 1000, helper, registry);

            // Backup History to Cloud
            if (uploadedCount > 0 || !registry.containsKey("CLOUD_HISTORY_ID")) {
                String newFileId = helper.uploadHistoryFile(dbHelper.exportHistoryToJson());
                if (newFileId != null) {
                    registry.put("CLOUD_HISTORY_ID", newFileId);
                    helper.saveTopicRegistry(registry);
                }
            }

            prefs.edit().putLong("last_sync_timestamp", System.currentTimeMillis()).apply();
            return Result.success();
        } catch (Exception e) {
            dbHelper.addLog("ERROR", "Sync Interrupted: " + e.getMessage());
            return Result.retry();
        }
    }

    private int performDeltaSync(long since, TelegramHelper helper, Map<String, String> registry) throws Exception {
        int count = 0;
        ContentResolver resolver = context.getContentResolver();
        Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        
        try (Cursor cursor = resolver.query(uri, new String[]{MediaStore.Images.Media.DATA, MediaStore.Images.Media.DATE_MODIFIED}, MediaStore.Images.Media.DATE_MODIFIED + " > ?", new String[]{String.valueOf(since)}, MediaStore.Images.Media.DATE_MODIFIED + " ASC")) {
            if (cursor != null && cursor.moveToFirst()) {
                int totalItems = cursor.getCount();
                int currentIndex = 0;

                do {
                    if (isStopped()) break;
                    String path = cursor.getString(0);
                    long mod = cursor.getLong(1);
                    File file = new File(path);
                    File parent = file.getParentFile();

                    if (parent != null && prefs.getBoolean(parent.getAbsolutePath(), false)) {
                        if (!dbHelper.isFileUploaded(path, mod)) {
                            
                            // --- BROADCAST PROGRESS TO UI ---
                            Data progress = new Data.Builder()
                                .putString("current_file", file.getName())
                                .putInt("progress_percent", (int) ((currentIndex / (float) totalItems) * 100))
                                .build();
                            setProgressAsync(progress);

                            String tid = getTopic(parent, helper, registry);
                            if (!tid.isEmpty() && helper.uploadPhoto(file, tid)) {
                                dbHelper.markAsUploaded(path, mod);
                                count++;
                                Thread.sleep(3000); 
                            }
                        }
                    }
                    currentIndex++;
                } while (cursor.moveToNext());
            }
        }
        return count;
    }

    private String getTopic(File dir, TelegramHelper helper, Map<String, String> registry) throws Exception {
        String name = dir.getName();
        if (registry.containsKey(name)) return registry.get(name);
        String id = helper.createTopic(name);
        registry.put(name, id);
        helper.saveTopicRegistry(registry);
        return id;
    }

    private ForegroundInfo createForegroundInfo(String text) {
        Notification n = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Photogram").setContentText(text).setOngoing(true).build();
        return new ForegroundInfo(NOTIF_ID, n);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Sync", NotificationManager.IMPORTANCE_LOW));
        }
    }
}