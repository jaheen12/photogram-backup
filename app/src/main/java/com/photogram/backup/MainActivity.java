package com.photogram.backup;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.io.File;
import java.util.ArrayList;

public class MainActivity extends Activity {
    ListView listView;
    ArrayList<File> imageFolders = new ArrayList<>();
    SharedPreferences prefs;
    private static final int PERM_CODE = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        prefs = getSharedPreferences("BackupPrefs", Context.MODE_PRIVATE);
        listView = findViewById(R.id.folderListView);
        
        // Button 1: Open Settings
        Button btnSettings = findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
        });

        // Button 2: Run a test backup
        Button btnBackup = findViewById(R.id.btnStartBackup);
        btnBackup.setOnClickListener(v -> {
            new Thread(this::performManualBackup).start();
        });

        handlePermissions();
    }

    private void handlePermissions() {
        String permission = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) 
            ? Manifest.permission.READ_MEDIA_IMAGES 
            : Manifest.permission.READ_EXTERNAL_STORAGE;

        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{permission}, PERM_CODE);
        } else {
            startAppLogic();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startAppLogic();
        } else {
            Toast.makeText(this, "Permission Denied!", Toast.LENGTH_LONG).show();
        }
    }

    private void startAppLogic() {
        new Thread(() -> {
            imageFolders.clear();
            File root = Environment.getExternalStorageDirectory();
            recursiveScan(root);
            runOnUiThread(this::setupAdapter);
        }).start();
    }

    private void recursiveScan(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        boolean folderHasImages = false;
        for (File file : files) {
            if (file.isDirectory()) {
                if (!file.getName().startsWith(".") && !file.getName().equalsIgnoreCase("Android")) {
                    recursiveScan(file);
                }
            } else {
                String name = file.getName().toLowerCase();
                if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")) {
                    folderHasImages = true;
                }
            }
        }
        if (folderHasImages) imageFolders.add(dir);
    }

    private void performManualBackup() {
        String token = prefs.getString("bot_token", "");
        String chatId = prefs.getString("chat_id", "");

        if (token.isEmpty() || chatId.isEmpty()) {
            runOnUiThread(() -> Toast.makeText(this, "Set Token/ID in Settings first!", Toast.LENGTH_LONG).show());
            return;
        }

        TelegramHelper helper = new TelegramHelper(token, chatId);
        int uploadCount = 0;

        for (File folder : imageFolders) {
            // Check if user toggled this folder ON
            if (prefs.getBoolean(folder.getAbsolutePath(), false)) {
                try {
                    // Check for existing Topic ID or create new one
                    String topicKey = "topic_" + folder.getAbsolutePath();
                    String threadId = prefs.getString(topicKey, "");

                    if (threadId.isEmpty()) {
                        runOnUiThread(() -> Toast.makeText(this, "Creating topic: " + folder.getName(), Toast.LENGTH_SHORT).show());
                        threadId = helper.createTopic(folder.getName());
                        prefs.edit().putString(topicKey, threadId).apply();
                    }

                    // Upload the first photo found as a test
                    File[] files = folder.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (f.isFile() && f.getName().toLowerCase().endsWith(".jpg")) {
                                boolean success = helper.uploadPhoto(f, threadId);
                                if (success) uploadCount++;
                                break; // Just one photo per folder for this test
                            }
                        }
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }
        }
        final int finalCount = uploadCount;
        runOnUiThread(() -> Toast.makeText(this, "Test Backup Finished. Uploaded: " + finalCount, Toast.LENGTH_LONG).show());
    }

    void setupAdapter() {
        listView.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() { return imageFolders.size(); }
            @Override
            public Object getItem(int i) { return imageFolders.get(i); }
            @Override
            public long getItemId(int i) { return i; }
            @Override
            public View getView(int i, View v, ViewGroup p) {
                if (v == null) v = LayoutInflater.from(MainActivity.this).inflate(R.layout.folder_item, null);
                File folder = imageFolders.get(i);
                ((TextView)v.findViewById(R.id.folderName)).setText(folder.getName());
                ((TextView)v.findViewById(R.id.folderPath)).setText(folder.getAbsolutePath());
                Switch sw = v.findViewById(R.id.backupSwitch);
                sw.setOnCheckedChangeListener(null);
                sw.setChecked(prefs.getBoolean(folder.getAbsolutePath(), false));
                sw.setOnCheckedChangeListener((btn, isChecked) -> prefs.edit().putBoolean(folder.getAbsolutePath(), isChecked).apply());
                return v;
            }
        });
    }
}