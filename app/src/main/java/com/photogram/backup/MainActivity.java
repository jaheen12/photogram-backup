package com.photogram.backup;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;

// AndroidX & Lifecycle
import androidx.appcompat.app.AppCompatActivity;
import androidx.work.*;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.lifecycle.Observer;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {
    ListView listView;
    SwipeRefreshLayout swipeRefresh;
    ArrayList<File> allFolders = new ArrayList<>();
    ArrayList<File> filteredFolders = new ArrayList<>();
    BaseAdapter adapter;
    SharedPreferences prefs;
    DatabaseHelper dbHelper;
    
    // Dashboard Components
    TextView tvTotalStats, tvSyncStatus, tvCurrentFile;
    ProgressBar pbSync;
    
    private static final int PERM_CODE = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        // 1. Initialize Components
        prefs = getSharedPreferences("BackupPrefs", Context.MODE_PRIVATE);
        dbHelper = new DatabaseHelper(this);
        
        listView = findViewById(R.id.folderListView);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        tvTotalStats = findViewById(R.id.tvTotalStats);
        tvSyncStatus = findViewById(R.id.tvSyncStatus);
        tvCurrentFile = findViewById(R.id.tvCurrentFile);
        pbSync = findViewById(R.id.pbSync);
        EditText etSearch = findViewById(R.id.etSearch);

        // 2. UI Listeners
        if (swipeRefresh != null) {
            swipeRefresh.setColorSchemeResources(android.R.color.holo_blue_dark);
            swipeRefresh.setOnRefreshListener(this::startAppLogic);
        }

        findViewById(R.id.btnSettings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnStartBackup).setOnClickListener(v -> scheduleBackup(true));
        findViewById(R.id.btnLogs).setOnClickListener(v -> startActivity(new Intent(this, LogActivity.class)));

        // 3. Real-time Search Logic
        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                public void onTextChanged(CharSequence s, int a, int b, int c) { filterFolders(s.toString()); }
                public void afterTextChanged(Editable s) {}
            });
        }

        setupAdapter();
        refreshDashboard();
        
        // 4. Load from Persistent Cache instantly
        allFolders.addAll(dbHelper.getSavedFolders());
        filterFolders("");
        
        // 5. System Readiness Checks
        handlePermissions();
        checkBatteryOptimization();
        observeSyncProgress();
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
        if (requestCode == PERM_CODE) startAppLogic();
    }

    private void startAppLogic() {
        if (swipeRefresh != null) swipeRefresh.setRefreshing(true);
        
        new Thread(() -> {
            ArrayList<File> fresh = scanFoldersWithMediaStore();
            dbHelper.saveFolders(fresh);
            
            runOnUiThread(() -> {
                allFolders.clear();
                allFolders.addAll(fresh);
                filterFolders(""); 
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false); 
                refreshDashboard();
            });
        }).start();
        
        scheduleBackup(false);
    }

    private ArrayList<File> scanFoldersWithMediaStore() {
        HashSet<String> folderPaths = new HashSet<>();
        ArrayList<File> folderList = new ArrayList<>();
        ContentResolver contentResolver = getContentResolver();
        Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = { MediaStore.Images.Media.DATA };
        
        try (Cursor cursor = contentResolver.query(uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int dataIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
                do {
                    String filePath = cursor.getString(dataIdx);
                    if (filePath != null) {
                        File file = new File(filePath);
                        File parent = file.getParentFile();
                        if (parent != null) {
                            String parentPath = parent.getAbsolutePath();
                            if (!parent.getName().startsWith(".") && !parentPath.contains("/Android/")) {
                                if (!folderPaths.contains(parentPath)) {
                                    folderPaths.add(parentPath);
                                    folderList.add(parent);
                                }
                            }
                        }
                    }
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            dbHelper.addLog("ERROR", "Scanner failed: " + e.getMessage());
        }
        Collections.sort(folderList, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return folderList;
    }

    private void observeSyncProgress() {
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
                            tvSyncStatus.setText("Cloud Sync active...");
                            if (percent > 0) pbSync.setProgress(percent);
                            else pbSync.setIndeterminate(true);
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
            if (last > 0) tvSyncStatus.setText("Last Sync: " + new SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(new Date(last)));
            else tvSyncStatus.setText("Cloud Sync Ready");
        }
    }

    private void filterFolders(String q) {
        filteredFolders.clear();
        for (File f : allFolders) {
            if (f.getName().toLowerCase().contains(q.toLowerCase())) filteredFolders.add(f);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void scheduleBackup(boolean immediate) {
        boolean onlyWifi = prefs.getBoolean("only_wifi", false);
        int interval = prefs.getInt("sync_interval", 60);
        
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(onlyWifi ? NetworkType.UNMETERED : NetworkType.CONNECTED)
                .build();

        if (immediate) {
            Data data = new Data.Builder().putBoolean("is_manual", true).build();
            OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(BackupWorker.class)
                    .setConstraints(constraints).setInputData(data).build();
            WorkManager.getInstance(this).enqueue(req);
            Toast.makeText(this, "Manual sync requested...", Toast.LENGTH_SHORT).show();
        }

        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(BackupWorker.class, interval, TimeUnit.MINUTES)
                .setConstraints(constraints).build();

        WorkManager.getInstance(this).enqueueUniquePeriodicWork("PhotogramSync", ExistingPeriodicWorkPolicy.REPLACE, periodic);
    }

    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                // FIXED: Using absolute path for android.provider.Settings to avoid conflict with SettingsActivity
                Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }
    }

    void setupAdapter() {
        adapter = new BaseAdapter() {
            public int getCount() { return filteredFolders.size(); }
            public Object getItem(int i) { return filteredFolders.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View v, ViewGroup p) {
                if (v == null) v = getLayoutInflater().inflate(R.layout.folder_item, null);
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