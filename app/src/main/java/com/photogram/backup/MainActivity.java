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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout; // NEW IMPORT
import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    ListView listView;
    SwipeRefreshLayout swipeRefresh; // NEW WIDGET
    ArrayList<File> imageFolders = new ArrayList<>();
    BaseAdapter adapter;
    SharedPreferences prefs;
    DatabaseHelper dbHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        prefs = getSharedPreferences("BackupPrefs", Context.MODE_PRIVATE);
        dbHelper = new DatabaseHelper(this);
        listView = findViewById(R.id.folderListView);
        swipeRefresh = findViewById(R.id.swipeRefresh); // INITIALIZE

        // Setup the Swipe-to-Refresh color (Telegram Blue)
        swipeRefresh.setColorSchemeColors(0xFF0088CC);
        
        // --- THE SWIPE LISTENER ---
        swipeRefresh.setOnRefreshListener(() -> {
            Toast.makeText(this, "Refreshing folder list...", Toast.LENGTH_SHORT).show();
            startAppLogic(); // Re-run the scanner
        });

        findViewById(R.id.btnSettings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnStartBackup).setOnClickListener(v -> scheduleBackup(true));

        setupAdapter();
        
        // INSTANT LOAD
        imageFolders.addAll(dbHelper.getSavedFolders());
        adapter.notifyDataSetChanged();

        handlePermissions();
        checkBatteryOptimization();
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
        // Start showing the refreshing circle
        runOnUiThread(() -> swipeRefresh.setRefreshing(true));

        new Thread(() -> {
            ArrayList<File> freshlyScanned = new ArrayList<>();
            recursiveScan(Environment.getExternalStorageDirectory(), freshlyScanned);
            dbHelper.saveFolders(freshlyScanned);
            
            runOnUiThread(() -> {
                imageFolders.clear();
                imageFolders.addAll(freshlyScanned);
                adapter.notifyDataSetChanged();
                // STOP the refreshing circle
                swipeRefresh.setRefreshing(false);
            });
        }).start();
        
        scheduleBackup(false);
    }

    private void recursiveScan(File dir, ArrayList<File> list) {
        File[] files = dir.listFiles();
        if (files == null) return;
        boolean hasImg = false;
        for (File f : files) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".") && !f.getName().equalsIgnoreCase("Android")) {
                    recursiveScan(f, list);
                }
            } else if (!hasImg) {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".jpg") || n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".heic")) {
                    hasImg = true;
                }
            }
        }
        if (hasImg) list.add(dir);
    }

    private void scheduleBackup(boolean immediate) {
        int interval = prefs.getInt("sync_interval", 60);
        NetworkType networkType = prefs.getBoolean("only_wifi", false) ? NetworkType.UNMETERED : NetworkType.CONNECTED;
        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(networkType).build();

        if (immediate) {
            Data data = new Data.Builder().putBoolean("is_manual", true).build();
            OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(BackupWorker.class).setConstraints(constraints).setInputData(data).build();
            WorkManager.getInstance(this).enqueue(req);
        }

        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(BackupWorker.class, interval, TimeUnit.MINUTES).setConstraints(constraints).build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("PhotogramSync", ExistingPeriodicWorkPolicy.KEEP, periodic);
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

    void setupAdapter() {
        adapter = new BaseAdapter() {
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
        };
        listView.setAdapter(adapter);
    }
}