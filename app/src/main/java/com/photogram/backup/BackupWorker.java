package com.photogram.backup;
import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.*;
import java.io.File;
import java.util.Map;

public class BackupWorker extends Worker {
    private final SharedPreferences prefs;
    private final DatabaseHelper dbHelper;
    private final NotificationManager nm;
    private final Context ctx;

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
        String ut = prefs.getString("custom_bot_token", "");
        final String token = (ut != null && !ut.isEmpty()) ? ut : BuildConfig.BOT_TOKEN;
        String cid = prefs.getString("chat_id", "");
        if (token.isEmpty() || cid.isEmpty()) return Result.failure();

        createChannel();
        setForegroundAsync(new ForegroundInfo(1, new NotificationCompat.Builder(ctx, "sync").setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Photogram").setContentText("Syncing...").build()));
        
        TelegramHelper helper = new TelegramHelper(token, cid);
        try {
            Map<String, String> reg = helper.getTopicRegistry();
            if (dbHelper.getTotalBackupCount() == 0 && reg.containsKey("CLOUD_HISTORY_ID")) {
                dbHelper.importHistoryFromJson(helper.downloadHistoryFile(reg.get("CLOUD_HISTORY_ID")));
            }

            int count = 0;
            ContentResolver res = ctx.getContentResolver();
            Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            long since = prefs.getLong("last_sync_timestamp", 0) / 1000;

            try (Cursor cur = res.query(uri, new String[]{MediaStore.Images.Media.DATA, MediaStore.Images.Media.DATE_MODIFIED}, MediaStore.Images.Media.DATE_MODIFIED + " > ?", new String[]{String.valueOf(since)}, MediaStore.Images.Media.DATE_MODIFIED + " ASC")) {
                if (cur != null && cur.moveToFirst()) {
                    int total = cur.getCount(), idx = 0;
                    do {
                        if (isStopped()) break;
                        String path = cur.getString(0);
                        long mod = cur.getLong(1);
                        File f = new File(path);
                        if (f.getParentFile() != null && prefs.getBoolean(f.getParentFile().getAbsolutePath(), false)) {
                            if (!dbHelper.isFileUploaded(path, mod)) {
                                setProgressAsync(new Data.Builder().putString("current_file", f.getName()).putInt("progress_percent", (int)((idx/(float)total)*100)).build());
                                String tid = getTid(f.getParentFile(), helper, reg);
                                if (!tid.isEmpty() && helper.uploadPhoto(f, tid)) {
                                    dbHelper.markAsUploaded(path, mod);
                                    count++;
                                    Thread.sleep(3000);
                                }
                            }
                        }
                        idx++;
                    } while (cur.moveToNext());
                }
            }

            if (count > 0 || !reg.containsKey("CLOUD_HISTORY_ID")) {
                String fid = helper.uploadHistoryFile(dbHelper.exportHistoryToJson());
                if (fid != null) { reg.put("CLOUD_HISTORY_ID", fid); helper.saveTopicRegistry(reg); }
            }
            prefs.edit().putLong("last_sync_timestamp", System.currentTimeMillis()).apply();
            return Result.success();
        } catch (Exception e) { return Result.retry(); }
    }

    private String getTid(File d, TelegramHelper h, Map<String, String> r) throws Exception {
        if (r.containsKey(d.getName())) return r.get(d.getName());
        String id = h.createTopic(d.getName());
        r.put(d.getName(), id); h.saveTopicRegistry(r);
        return id;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel("sync", "Sync", NotificationManager.IMPORTANCE_LOW));
    }
}