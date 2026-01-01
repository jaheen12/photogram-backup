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
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.work.*;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    ListView listView;
    SwipeRefreshLayout swipeRefresh;
    ArrayList<File> allFolders = new ArrayList<>();
    ArrayList<File> filteredFolders = new ArrayList<>();
    BaseAdapter adapter;
    SharedPreferences prefs;
    DatabaseHelper dbHelper;
    TextView tvTotalStats, tvLastSync;
    private static final int PERM_CODE = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        prefs = getSharedPreferences("BackupPrefs", Context.MODE_PRIVATE);
        dbHelper = new DatabaseHelper(this);
        
        listView = findViewById(R.id.folderListView);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        tvTotalStats = findViewById(R.id.tvTotalStats);
        tvLastSync = findViewById(R.id.tvLastSync);
        EditText etSearch = findViewById(R.id.etSearch);

        swipeRefresh.setColorSchemeColors(0xFF0088CC);
        swipeRefresh.setOnRefreshListener(this::startAppLogic);

        findViewById(R.id.btnSettings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnStartBackup).setOnClickListener(v -> scheduleBackup(true));

        etSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterFolders(s.toString());
            }
            public void afterTextChanged(Editable s) {}
        });

        setupAdapter();
        refreshDashboard();
        
        // Initial load from DB
        allFolders.addAll(dbHelper.getSavedFolders());
        filterFolders(""); 

        handlePermissions(); // This will now work correctly
        checkBatteryOptimization();
    }

    private void refreshDashboard() {
        int count = dbHelper.getTotalBackupCount();
        tvTotalStats.setText(count + " Photos Backed Up");

        long lastSync = prefs.getLong("last_sync_timestamp", 0);
        if (lastSync > 0) {
            SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault());
            tvLastSync.setText("Last Sync: " + sdf.format(new Date(lastSync)));
        } else {
            tvLastSync.setText("Last Sync: Never");
        }
    }

    private void filterFolders(String query) {
        filteredFolders.clear();
        for (File f : allFolders) {
            if (f.getName().toLowerCase().contains(query.toLowerCase())) {
                filteredFolders.add(f);
            }
        }
        adapter.notifyDataSetChanged();
    }

    // --- THE MISSING METHOD ---
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
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                needsRequest = true;
                break;
            }
        }

        if (needsRequest) {
            requestPermissions(perms.toArray(new String[0]), PERM_CODE);
        } else {
            startAppLogic();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERM_CODE) {
            startAppLogic();
        }
    }

    private void startAppLogic() {
        runOnUiThread(() -> swipeRefresh.setRefreshing(true));
        new Thread(() -> {
            ArrayList<File> fresh = new ArrayList<>();
            recursiveScan(Environment.getExternalStorageDirectory(), fresh);
            dbHelper.saveFolders(fresh);
            runOnUiThread(() -> {
                allFolders.clear();
                allFolders.addAll(fresh);
                filterFolders(""); 
                swipeRefresh.setRefreshing(false);
                refreshDashboard();
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
        boolean onlyWifi = prefs.getBoolean("only_wifi", false);
        NetworkType nt = onlyWifi ? NetworkType.UNMETERED : NetworkType.CONNECTED;
        
        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(nt).build();

        if (immediate) {
            Data data = new Data.Builder().putBoolean("is_manual", true).build();
            OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(BackupWorker.class)
                    .setConstraints(constraints).setInputData(data).build();
            WorkManager.getInstance(this).enqueue(req);
            Toast.makeText(this, "Sync Started...", Toast.LENGTH_SHORT).show();
        }

        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(BackupWorker.class, interval, TimeUnit.MINUTES)
                .setConstraints(constraints).build();
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
            public int getCount() { return filteredFolders.size(); }
            @Override
            public Object getItem(int i) { return filteredFolders.get(i); }
            @Override
            public long getItemId(int i) { return i; }
            @Override
            public View getView(int i, View v, ViewGroup p) {
                if (v == null) v = LayoutInflater.from(MainActivity.this).inflate(R.layout.folder_item, null);
                File folder = filteredFolders.get(i);
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