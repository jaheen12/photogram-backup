package com.photogram.backup;

import android.Manifest;
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

// --- CRITICAL FIXES ---
import androidx.appcompat.app.AppCompatActivity; // Use this instead of Activity
import androidx.work.*;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.lifecycle.Observer;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit; // FIXED: Missing import
// ----------------------

public class MainActivity extends AppCompatActivity { // FIXED: Changed to AppCompatActivity
    ListView listView;
    SwipeRefreshLayout swipeRefresh;
    ArrayList<File> allFolders = new ArrayList<>();
    ArrayList<File> filteredFolders = new ArrayList<>();
    BaseAdapter adapter;
    SharedPreferences prefs;
    DatabaseHelper dbHelper;
    TextView tvTotalStats, tvSyncStatus, tvCurrentFile;
    ProgressBar pbSync;
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
        tvSyncStatus = findViewById(R.id.tvSyncStatus);
        tvCurrentFile = findViewById(R.id.tvCurrentFile);
        pbSync = findViewById(R.id.pbSync);
        EditText etSearch = findViewById(R.id.etSearch);

        swipeRefresh.setOnRefreshListener(this::startAppLogic);

        findViewById(R.id.btnSettings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnStartBackup).setOnClickListener(v -> scheduleBackup(true));
        findViewById(R.id.btnLogs).setOnClickListener(v -> startActivity(new Intent(this, LogActivity.class)));

        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                public void onTextChanged(CharSequence s, int a, int b, int c) { filterFolders(s.toString()); }
                public void afterTextChanged(Editable s) {}
            });
        }

        setupAdapter();
        refreshDashboard();
        allFolders.addAll(dbHelper.getSavedFolders());
        filterFolders("");
        handlePermissions();
        
        // Start monitoring progress
        observeSyncProgress();
    }

    private void observeSyncProgress() {
        // FIXED: Added explicit List<WorkInfo> type to satisfy compiler
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData("PhotogramSync")
            .observe(this, new Observer<List<WorkInfo>>() {
                @Override
                public void onChanged(List<WorkInfo> workInfos) {
                    if (workInfos == null || workInfos.isEmpty()) return;
                    WorkInfo info = workInfos.get(0);
                    
                    if (info.getState() == WorkInfo.State.RUNNING) {
                        if (pbSync != null) pbSync.setVisibility(View.VISIBLE);
                        if (tvCurrentFile != null) tvCurrentFile.setVisibility(View.VISIBLE);
                        
                        Data progress = info.getProgress();
                        String fileName = progress.getString("current_file");
                        int percent = progress.getInt("progress_percent", 0);
                        
                        if (fileName != null && tvCurrentFile != null) {
                            tvCurrentFile.setText("Syncing: " + fileName);
                            tvSyncStatus.setText("Backup in progress...");
                            pbSync.setIndeterminate(percent == 0);
                            if (percent > 0) pbSync.setProgress(percent);
                        }
                    } else {
                        if (pbSync != null) pbSync.setVisibility(View.GONE);
                        if (tvCurrentFile != null) tvCurrentFile.setVisibility(View.GONE);
                        refreshDashboard();
                    }
                }
            });
    }

    private void refreshDashboard() {
        if (tvTotalStats != null) tvTotalStats.setText(dbHelper.getTotalBackupCount() + " Items Saved");
        long last = prefs.getLong("last_sync_timestamp", 0);
        if (tvSyncStatus != null) {
            if (last > 0) tvSyncStatus.setText("Last Sync: " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(last)));
            else tvSyncStatus.setText("Cloud Sync Ready");
        }
    }

    private void filterFolders(String query) {
        filteredFolders.clear();
        for (File f : allFolders) {
            if (f.getName().toLowerCase().contains(query.toLowerCase())) filteredFolders.add(f);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void handlePermissions() {
        String p = (Build.VERSION.SDK_INT >= 33) ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) 
            requestPermissions(new String[]{p, Manifest.permission.POST_NOTIFICATIONS}, PERM_CODE);
        else startAppLogic();
    }

    @Override
    public void onRequestPermissionsResult(int r, String[] p, int[] g) { startAppLogic(); }

    private void startAppLogic() {
        if (swipeRefresh != null) swipeRefresh.setRefreshing(true);
        new Thread(() -> {
            ArrayList<File> fresh = new ArrayList<>();
            recursiveScan(Environment.getExternalStorageDirectory(), fresh);
            dbHelper.saveFolders(fresh);
            runOnUiThread(() -> {
                allFolders.clear(); allFolders.addAll(fresh);
                filterFolders(""); 
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false); 
                refreshDashboard();
            });
        }).start();
    }

    private void recursiveScan(File dir, ArrayList<File> list) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".") && !f.getName().equalsIgnoreCase("Android")) recursiveScan(f, list);
            } else if (f.getName().toLowerCase().endsWith(".jpg")) {
                list.add(dir);
                break;
            }
        }
    }

    private void scheduleBackup(boolean immediate) {
        Constraints c = new Constraints.Builder().setRequiredNetworkType(prefs.getBoolean("only_wifi", false) ? NetworkType.UNMETERED : NetworkType.CONNECTED).build();
        if (immediate) {
            WorkManager.getInstance(this).enqueue(new OneTimeWorkRequest.Builder(BackupWorker.class).setConstraints(c).setInputData(new Data.Builder().putBoolean("is_manual", true).build()).build());
        }
        PeriodicWorkRequest p = new PeriodicWorkRequest.Builder(BackupWorker.class, prefs.getInt("sync_interval", 60), TimeUnit.MINUTES).setConstraints(c).build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("PhotogramSync", ExistingPeriodicWorkPolicy.KEEP, p);
    }

    void setupAdapter() {
        adapter = new BaseAdapter() {
            public int getCount() { return filteredFolders.size(); }
            public Object getItem(int i) { return filteredFolders.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View v, ViewGroup p) {
                if (v == null) v = LayoutInflater.from(MainActivity.this).inflate(R.layout.folder_item, null);
                File f = filteredFolders.get(i);
                ((TextView)v.findViewById(R.id.folderName)).setText(f.getName());
                ((TextView)v.findViewById(R.id.folderPath)).setText(f.getAbsolutePath());
                Switch s = v.findViewById(R.id.backupSwitch);
                s.setOnCheckedChangeListener(null);
                s.setChecked(prefs.getBoolean(f.getAbsolutePath(), false));
                s.setOnCheckedChangeListener((b, val) -> prefs.edit().putBoolean(f.getAbsolutePath(), val).apply());
                return v;
            }
        };
        listView.setAdapter(adapter);
    }
}