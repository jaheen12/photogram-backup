package com.photogram.backup;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
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
    ArrayList<File> folders = new ArrayList<>();
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
            Toast.makeText(this, "Settings Page Coming in Next Sprint!", Toast.LENGTH_SHORT).show();
        });

        handlePermissions();
    }

    private void handlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ logic
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES}, PERM_CODE);
            } else {
                startAppLogic();
            }
        } else {
            // Android 12 and below logic
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, PERM_CODE);
            } else {
                startAppLogic();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startAppLogic();
        } else {
            Toast.makeText(this, "Permission Denied. Please enable in App Info.", Toast.LENGTH_LONG).show();
        }
    }

    private void startAppLogic() {
        folders.clear();
        // Look in DCIM and Pictures
        File dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        File pics = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        
        if (dcim.exists()) addFoldersRecursive(dcim);
        if (pics.exists()) addFoldersRecursive(pics);
        
        setupAdapter();
    }

    // This makes the app find ACTUAL folders inside DCIM (like Camera, Screenshots)
    private void addFoldersRecursive(File folder) {
        if (folder.isDirectory()) {
            folders.add(folder);
            File[] files = folder.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory() && !f.getName().startsWith(".")) {
                        addFoldersRecursive(f);
                    }
                }
            }
        }
    }

    void setupAdapter() {
        listView.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() { return folders.size(); }
            @Override
            public Object getItem(int i) { return folders.get(i); }
            @Override
            public long getItemId(int i) { return i; }
            @Override
            public View getView(int i, View v, ViewGroup p) {
                if (v == null) v = LayoutInflater.from(MainActivity.this).inflate(R.layout.folder_item, null);
                
                File folder = folders.get(i);
                TextView name = v.findViewById(R.id.folderName);
                TextView path = v.findViewById(R.id.folderPath);
                Switch sw = v.findViewById(R.id.backupSwitch);

                name.setText(folder.getName());
                path.setText(folder.getAbsolutePath());
                
                // Remove previous listener to prevent recycling bugs
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