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

// Firebase Imports
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.FirebaseDatabase;
import com.google.android.gms.tasks.Tasks;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class BackupWorker extends Worker {

    private static final String CHANNEL_ID = "sync_channel";
    private static final int NOTIF_ID = 1;
    private static final String DB_URL = "https://photogram-dd154-default-rtdb.asia-southeast1.firebasedatabase.app/";

    private final SharedPreferences prefs;
    private final DatabaseHelper dbHelper;
    private final NotificationManager notificationManager;
    private final Context context;

    // Quota Variables
    private boolean isLimited = false;
    private int dailyLimit = 0;
    private int currentUsage = 0;

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
        // 1. FIREBASE AUTH CHECK
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            dbHelper.addLog("ERROR", "Sync failed: No user logged in.");
            return Result.failure();
        }

        // 2. NETWORK & INTERVAL CHECKS
        if (prefs.getBoolean("only_wifi", false) && !isWifiConnected()) {
            return Result.retry();
        }

        boolean isManual = getInputData().getBoolean("is_manual", false);
        long lastSyncSeconds = prefs.getLong("last_sync_timestamp", 0) / 1000;
        int intervalMins = prefs.getInt("sync_interval", 60);

        if (!isManual && (System.currentTimeMillis() / 1000 - lastSyncSeconds < TimeUnit.MINUTES.toSeconds(intervalMins))) {
            return Result.success();
        }

        // 3. FETCH QUOTA & STATUS FROM FIREBASE (Synchronous)
        if (!fetchQuotaFromFirebase(uid)) {
            dbHelper.addLog("ERROR", "Cloud verification failed. Check internet.");
            return Result.retry();
        }

        // 4. START SYNC PROCESS
        createNotificationChannel();
        setForegroundAsync(createForegroundInfo("Safe Cloud Syncing..."));
        
        final String token = BuildConfig.BOT_TOKEN; // Using Secret Injected Token
        String chatId = prefs.getString("chat_id", "");
        if (chatId.isEmpty()) return Result.failure();

        TelegramHelper helper = new TelegramHelper(token, chatId);
        int uploadedCount = 0;

        try {
            Map<String, String> registry = helper.getTopicRegistry();

            // Restore memory if first time
            if (dbHelper.getTotalBackupCount() == 0 && registry.containsKey("CLOUD_HISTORY_ID")) {
                String historyJson = helper.downloadHistoryFile(registry.get("CLOUD_HISTORY_ID"));
                dbHelper.importHistoryFromJson(historyJson);
            }

            // --- RUN DELTA SYNC WITH QUOTA ENFORCEMENT ---
            uploadedCount = performDeltaSync(lastSyncSeconds, helper, registry, uid);

            // Save state to Cloud
            if (uploadedCount > 0 || !registry.containsKey("CLOUD_HISTORY_ID")) {
                String newFileId = helper.uploadHistoryFile(dbHelper.exportHistoryToJson());
                if (newFileId != null) {
                    registry.put("CLOUD_HISTORY_ID", newFileId);
                    helper.saveTopicRegistry(registry);
                }
            }

            prefs.edit().putLong("last_sync_timestamp", System.currentTimeMillis()).apply();
            dbHelper.addLog("SUCCESS", "Backup complete. Saved " + uploadedCount + " items.");
            return Result.success();

        } catch (Exception e) {
            dbHelper.addLog("ERROR", "Sync interrupted: " + e.getMessage());
            return Result.retry();
        }
    }

    private boolean fetchQuotaFromFirebase(String uid) {
        try {
            // Using Tasks.await to make Firebase calls work in doWork thread
            DataSnapshot snap = Tasks.await(FirebaseDatabase.getInstance(DB_URL).getReference("users").child(uid).get());
            
            String status = snap.child("status").getValue(String.class);
            if (!"approved".equals(status) && !"limited".equals(status)) return false;

            isLimited = "limited".equals(status);
            if (isLimited) {
                dailyLimit = snap.child("daily_limit").getValue(Integer.class);
                currentUsage = snap.child("usage_count").getValue(Integer.class);
                String lastDate = snap.child("last_sync_date").getValue(String.class);
                String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());

                // Reset counter if it's a new day
                if (!today.equals(lastDate)) {
                    currentUsage = 0;
                    FirebaseDatabase.getInstance(DB_URL).getReference("users").child(uid).child("usage_count").setValue(0);
                    FirebaseDatabase.getInstance(DB_URL).getReference("users").child(uid).child("last_sync_date").setValue(today);
                }
            }
            return true;
        } catch (Exception e) { return false; }
    }

    private int performDeltaSync(long since, TelegramHelper helper, Map<String, String> registry, String uid) throws Exception {
        int count = 0;
        ContentResolver resolver = context.getContentResolver();
        Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        
        try (Cursor cursor = resolver.query(uri, new String[]{MediaStore.Images.Media.DATA, MediaStore.Images.Media.DATE_MODIFIED}, MediaStore.Images.Media.DATE_MODIFIED + " > ?", new String[]{String.valueOf(since)}, MediaStore.Images.Media.DATE_MODIFIED + " ASC")) {
            if (cursor != null && cursor.moveToFirst()) {
                int total = cursor.getCount();
                int currentIdx = 0;

                do {
                    if (isStopped()) break;
                    
                    // --- QUOTA CHECK ---
                    if (isLimited && currentUsage >= dailyLimit) {
                        dbHelper.addLog("LIMIT", "Daily limit reached (" + dailyLimit + "). Sync stopped.");
                        break;
                    }

                    String path = cursor.getString(0);
                    long mod = cursor.getLong(1);
                    File file = new File(path);
                    File parent = file.getParentFile();

                    if (parent != null && prefs.getBoolean(parent.getAbsolutePath(), false)) {
                        if (!dbHelper.isFileUploaded(path, mod)) {
                            
                            setProgressAsync(new Data.Builder().putString("current_file", file.getName()).putInt("progress_percent", (int)((currentIdx/(float)total)*100)).build());

                            String tid = getTopic(parent, helper, registry);
                            if (!tid.isEmpty() && helper.uploadPhoto(file, tid)) {
                                dbHelper.markAsUploaded(path, mod);
                                count++;
                                
                                // Update Firebase Usage
                                if (isLimited) {
                                    currentUsage++;
                                    FirebaseDatabase.getInstance(DB_URL).getReference("users").child(uid).child("usage_count").setValue(currentUsage);
                                }
                                
                                Thread.sleep(3000); // Flood Control
                            }
                        }
                    }
                    currentIdx++;
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

    private boolean isWifiConnected() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        }
        return false;
    }

    private ForegroundInfo createForegroundInfo(String text) {
        Notification n = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Photogram Sync").setContentText(text).setOngoing(true).build();
        return new ForegroundInfo(NOTIF_ID, n);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Sync", NotificationManager.IMPORTANCE_LOW));
        }
    }
}