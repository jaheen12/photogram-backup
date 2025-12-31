package com.photogram.backup;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.io.File;

public class BackupWorker extends Worker {
    private SharedPreferences prefs;
    private SharedPreferences history;

    public BackupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
        prefs = context.getSharedPreferences("BackupPrefs", Context.MODE_PRIVATE);
        history = context.getSharedPreferences("HistoryPrefs", Context.MODE_PRIVATE);
    }

    @NonNull
    @Override
    public Result doWork() {
        String token = prefs.getString("bot_token", "");
        String chatId = prefs.getString("chat_id", "");

        if (token.isEmpty() || chatId.isEmpty()) return Result.failure();

        TelegramHelper helper = new TelegramHelper(token, chatId);

        // This worker needs a list of folders. For simplicity, we scan storage again
        File root = android.os.Environment.getExternalStorageDirectory();
        scanAndUpload(root, helper);

        return Result.success();
    }

    private void scanAndUpload(File dir, TelegramHelper helper) {
        File[] files = dir.listFiles();
        if (files == null) return;

        // Check if this folder is enabled for backup
        if (prefs.getBoolean(dir.getAbsolutePath(), false)) {
            try {
                String topicKey = "topic_" + dir.getAbsolutePath();
                String threadId = prefs.getString(topicKey, "");

                if (threadId.isEmpty()) {
                    threadId = helper.createTopic(dir.getName());
                    prefs.edit().putString(topicKey, threadId).apply();
                }

                for (File f : files) {
                    if (f.isFile() && isImage(f)) {
                        // --- THE HISTORY ENGINE ---
                        // Key = Path + Size (to detect if same name but different photo)
                        String fileId = f.getAbsolutePath() + "_" + f.length();
                        
                        if (!history.getBoolean(fileId, false)) {
                            boolean success = helper.uploadPhoto(f, threadId);
                            if (success) {
                                // Mark as uploaded
                                history.edit().putBoolean(fileId, true).apply();
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // Keep searching other folders
        for (File f : files) {
            if (f.isDirectory() && !f.getName().startsWith(".") && !f.getName().equalsIgnoreCase("Android")) {
                scanAndUpload(f, helper);
            }
        }
    }

    private boolean isImage(File f) {
        String n = f.getName().toLowerCase();
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp");
    }
}