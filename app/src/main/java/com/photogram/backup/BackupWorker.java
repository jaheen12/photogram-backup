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
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.FirebaseDatabase;
import com.google.android.gms.tasks.Tasks;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

public class BackupWorker extends Worker {
    private static final String CHANNEL_ID = "sync_channel";
    private static final int NOTIF_ID = 1;
    private static final String DB_URL = "https://photogram-dd154-default-rtdb.asia-southeast1.firebasedatabase.app/";

    private final SharedPreferences prefs;
    private final DatabaseHelper dbHelper;
    private final NotificationManager nm;
    private final Context ctx;

    private boolean isLimited = false;
    private int dailyLimit = 0;
    private int currentUsage = 0;

    public BackupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
        this.ctx = context;
        this.prefs = context.getSharedPreferences("BackupPrefs", Context.MODE_PRIVATE);
        this.dbHelper = new DatabaseHelper(context);
        this.nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @NonNull
    @Override
    public Result doWork() {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) return Result.failure();

        if (prefs.getBoolean("only_wifi", false) && !isWifiConnected()) return Result.retry();

        if (!fetchCloudState(uid)) return Result.retry();

        createChannel();
        setForegroundAsync(new ForegroundInfo(NOTIF_ID, new NotificationCompat.Builder(ctx, CHANNEL_ID).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Photogram Sync").setOngoing(true).build()));
        
        TelegramHelper helper = new TelegramHelper(BuildConfig.BOT_TOKEN, prefs.getString("chat_id", ""));
        try {
            Map<String, String> reg = helper.getTopicRegistry();
            if (dbHelper.getTotalBackupCount() == 0 && reg.containsKey("CLOUD_HISTORY_ID")) {
                dbHelper.importHistoryFromJson(helper.downloadHistoryFile(reg.get("CLOUD_HISTORY_ID")));
            }

            int count = performDeltaSync(prefs.getLong("last_sync_timestamp", 0) / 1000, helper, reg, uid);

            if (count > 0 || !reg.containsKey("CLOUD_HISTORY_ID")) {
                String fid = helper.uploadHistoryFile(dbHelper.exportHistoryToJson());
                if (fid != null) { reg.put("CLOUD_HISTORY_ID", fid); helper.saveTopicRegistry(reg); }
            }

            prefs.edit().putLong("last_sync_timestamp", System.currentTimeMillis()).apply();
            return Result.success();
        } catch (Exception e) {
            dbHelper.addLog("ERROR", "Sync Failed: " + e.getMessage());
            return Result.retry();
        }
    }

    private boolean fetchCloudState(String uid) {
        try {
            DataSnapshot snap = Tasks.await(FirebaseDatabase.getInstance(DB_URL).getReference("users").child(uid).get());
            String status = snap.child("status").getValue(String.class);
            isLimited = "limited".equals(status);
            if (isLimited) {
                dailyLimit = snap.child("daily_limit").getValue(Integer.class);
                currentUsage = snap.child("usage_count").getValue(Integer.class);
                String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
                if (!today.equals(snap.child("last_sync_date").getValue(String.class))) {
                    currentUsage = 0;
                    FirebaseDatabase.getInstance(DB_URL).getReference("users").child(uid).child("usage_count").setValue(0);
                    FirebaseDatabase.getInstance(DB_URL).getReference("users").child(uid).child("last_sync_date").setValue(today);
                }
            }
            return "approved".equals(status) || "limited".equals(status);
        } catch (Exception e) { return false; }
    }

    private int performDeltaSync(long since, TelegramHelper helper, Map<String, String> reg, String uid) throws Exception {
        int count = 0;
        ContentResolver resolver = ctx.getContentResolver();
        try (Cursor cursor = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, new String[]{MediaStore.Images.Media.DATA, MediaStore.Images.Media.DATE_MODIFIED}, MediaStore.Images.Media.DATE_MODIFIED + " > ?", new String[]{String.valueOf(since)}, MediaStore.Images.Media.DATE_MODIFIED + " ASC")) {
            if (cursor != null && cursor.moveToFirst()) {
                int total = cursor.getCount(), idx = 0;
                do {
                    if (isStopped()) break;
                    if (isLimited && currentUsage >= dailyLimit) break;

                    String path = cursor.getString(0);
                    long mod = cursor.getLong(1);
                    File f = new File(path);
                    if (f.getParentFile() != null && prefs.getBoolean(f.getParentFile().getAbsolutePath(), false)) {
                        if (!dbHelper.isFileUploaded(path, mod)) {
                            setProgressAsync(new Data.Builder().putString("current_file", f.getName()).putInt("progress_percent", (int)((idx/(float)total)*100)).build());
                            String tid = getTid(f.getParentFile(), helper, reg);
                            if (!tid.isEmpty() && helper.uploadPhoto(f, tid)) {
                                dbHelper.markAsUploaded(path, mod);
                                count++;
                                if (isLimited) {
                                    currentUsage++;
                                    FirebaseDatabase.getInstance(DB_URL).getReference("users").child(uid).child("usage_count").setValue(currentUsage);
                                }
                                Thread.sleep(1000); // 1 second fast sync
                            }
                        }
                    }
                    idx++;
                } while (cursor.moveToNext());
            }
        }
        return count;
    }

    private String getTid(File d, TelegramHelper h, Map<String, String> r) throws Exception {
        if (r.containsKey(d.getName())) return r.get(d.getName());
        String id = h.createTopic(d.getName());
        r.put(d.getName(), id); h.saveTopicRegistry(r);
        return id;
    }

    private boolean isWifiConnected() {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (Build.VERSION.SDK_INT >= 23) {
            Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        }
        return false;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Sync", NotificationManager.IMPORTANCE_LOW));
    }
}