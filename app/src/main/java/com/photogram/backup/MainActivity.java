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
import java.util.HashSet;

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
        
        Button btnSettings = findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
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
        // Show a loading toast because full scan takes a few seconds
        Toast.makeText(this, "Scanning all folders for photos...", Toast.LENGTH_SHORT).show();
        
        new Thread(() -> {
            imageFolders.clear();
            // Start scanning from the very root of internal storage
            File root = Environment.getExternalStorageDirectory();
            recursiveScan(root);
            
            // Refresh UI on the main thread
            runOnUiThread(this::setupAdapter);
        }).start();
    }

    private void recursiveScan(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;

        boolean folderHasImages = false;

        for (File file : files) {
            if (file.isDirectory()) {
                // Skip hidden folders (start with .) and system Android folder
                if (!file.getName().startsWith(".") && !file.getName().equalsIgnoreCase("Android")) {
                    recursiveScan(file);
                }
            } else {
                // Check if this file is an image
                String name = file.getName().toLowerCase();
                if (name.endsWith(".jpg") || name.endsWith(".jpeg") || 
                    name.endsWith(".png") || name.endsWith(".webp") || name.endsWith(".heic")) {
                    folderHasImages = true;
                }
            }
        }

        // Only add the folder if it actually contains at least one image
        if (folderHasImages) {
            imageFolders.add(dir);
        }
    }

    void setupAdapter() {
        if (imageFolders.isEmpty()) {
            Toast.makeText(this, "No photo folders found!", Toast.LENGTH_LONG).show();
        }

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
                TextView name = v.findViewById(R.id.folderName);
                TextView path = v.findViewById(R.id.folderPath);
                Switch sw = v.findViewById(R.id.backupSwitch);

                name.setText(folder.getName());
                path.setText(folder.getAbsolutePath());
                
                sw.setOnCheckedChangeListener(null);
                sw.setChecked(prefs.getBoolean(folder.getAbsolutePath(), false));

                sw.setOnCheckedChangeListener((btn, isChecked) -> {
                    prefs.edit().putBoolean(folder.getAbsolutePath(), isChecked).apply();
                });

                return v;
            }
        });
    }
}