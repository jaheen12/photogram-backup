package com.photogram.backup;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import androidx.work.*;
import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    ListView listView;
    ArrayList<File> imageFolders = new ArrayList<>();
    SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        prefs = getSharedPreferences("BackupPrefs", Context.MODE_PRIVATE);
        listView = findViewById(R.id.folderListView);
        
        findViewById(R.id.btnSettings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        Button btnBackup = findViewById(R.id.btnStartBackup);
        btnBackup.setOnClickListener(v -> {
            scheduleBackup(true); // Trigger immediate run
            Toast.makeText(this, "Checking network and starting sync...", Toast.LENGTH_SHORT).show();
        });

        handlePermissions();
        checkBatteryOptimization();
    }

    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }
    }

    private void handlePermissions() {
        ArrayList<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES);
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }

        boolean needsRequest = false;
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) needsRequest = true;
        }

        if (needsRequest) requestPermissions(perms.toArray(new String[0]), 101);
        else startAppLogic();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        startAppLogic();
    }

    private void startAppLogic() {
        new Thread(() -> {
            imageFolders.clear();
            recursiveScan(Environment.getExternalStorageDirectory());
            runOnUiThread(this::setupAdapter);
        }).start();
        scheduleBackup(false); // Initialize periodic scheduler
    }

    private void recursiveScan(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        boolean hasImg = false;
        for (File f : files) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".") && !f.getName().equalsIgnoreCase("Android")) recursiveScan(f);
            } else {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".jpg") || n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".heic")) hasImg = true;
            }
        }
        if (hasImg) imageFolders.add(dir);
    }

    private void scheduleBackup(boolean immediate) {
        int interval = prefs.getInt("sync_interval", 60);
        boolean onlyWifi = prefs.getBoolean("only_wifi", false);

        // --- NEW LOGIC: WI-FI vs DATA ---
        // UNMETERED means Wi-Fi only. CONNECTED means any internet.
        NetworkType networkType = onlyWifi ? NetworkType.UNMETERED : NetworkType.CONNECTED;

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .build();

        if (immediate) {
            Data inputData = new Data.Builder().putBoolean("is_manual", true).build();
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(BackupWorker.class)
                    .setConstraints(constraints).setInputData(inputData).build();
            WorkManager.getInstance(this).enqueue(request);
        }

        PeriodicWorkRequest periodicRequest = new PeriodicWorkRequest.Builder(BackupWorker.class, interval, TimeUnit.MINUTES)
                .setConstraints(constraints).build();

        // Using UPDATE so that if user changes network settings, it applies immediately
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("PhotogramSync", ExistingPeriodicWorkPolicy.UPDATE, periodicRequest);
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