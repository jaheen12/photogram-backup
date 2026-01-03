package com.photogram.backup;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import com.google.firebase.auth.FirebaseAuth;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {
    public static boolean active = false;
    private ListView listView;
    private SwipeRefreshLayout swipeRefresh;
    private ArrayList<File> allFolders = new ArrayList<>();
    private ArrayList<File> filteredFolders = new ArrayList<>();
    private BaseAdapter adapter;
    private SharedPreferences prefs;
    private DatabaseHelper dbHelper;
    private TextView tvTotalStats, tvSyncStatus, tvCurrentFile;
    private ProgressBar pbSync;
    private static final int PERM_CODE = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        active = true;

        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

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

        swipeRefresh.setOnRefreshListener(() -> startAppLogic());
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
        observeSyncProgress();
        checkBatteryOptimization();
    }

    private void observeSyncProgress() {
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData("PhotogramSync")
            .observe(this, workInfos -> {
                if (workInfos == null || workInfos.isEmpty()) return;
                WorkInfo info = workInfos.get(0);
                if (info.getState() == WorkInfo.State.RUNNING) {
                    pbSync.setVisibility(View.VISIBLE);
                    tvCurrentFile.setVisibility(View.VISIBLE);
                    Data progress = info.getProgress();
                    String fileName = progress.getString("current_file");
                    if (fileName != null) {
                        tvCurrentFile.setText("Syncing: " + fileName);
                        tvSyncStatus.setText("Backup in progress...");
                        pbSync.setProgress(progress.getInt("progress_percent", 0));
                    }
                } else {
                    pbSync.setVisibility(View.GONE);
                    tvCurrentFile.setVisibility(View.GONE);
                    refreshDashboard();
                }
            });
    }

    private void refreshDashboard() {
        tvTotalStats.setText(dbHelper.getTotalBackupCount() + " Items Saved");
        long last = prefs.getLong("last_sync_timestamp", 0);
        tvSyncStatus.setText(last > 0 ? "Last Sync: " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(last)) : "Cloud Ready");
    }

    private void filterFolders(String query) {
        filteredFolders.clear();
        for (File f : allFolders) {
            if (f.getName().toLowerCase().contains(query.toLowerCase())) filteredFolders.add(f);
        }
        adapter.notifyDataSetChanged();
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
        swipeRefresh.setRefreshing(true);
        new Thread(() -> {
            ArrayList<File> fresh = scanMediaStore();
            dbHelper.saveFolders(fresh);
            runOnUiThread(() -> {
                allFolders.clear(); allFolders.addAll(fresh);
                filterFolders(""); swipeRefresh.setRefreshing(false); refreshDashboard();
            });
        }).start();
        scheduleBackup(false);
    }

    private ArrayList<File> scanMediaStore() {
        HashSet<String> paths = new HashSet<>();
        ArrayList<File> list = new ArrayList<>();
        ContentResolver cr = getContentResolver();
        try (Cursor c = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, new String[]{MediaStore.Images.Media.DATA}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                do {
                    String path = c.getString(0);
                    if (path == null) continue;
                    File folder = new File(path).getParentFile();
                    if (folder != null && !folder.getName().startsWith(".") && !folder.getAbsolutePath().contains("/Android/")) {
                        if (!paths.contains(folder.getAbsolutePath())) {
                            paths.add(folder.getAbsolutePath());
                            list.add(folder);
                        }
                    }
                } while (c.moveToNext());
            }
        }
        Collections.sort(list, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return list;
    }

    private void scheduleBackup(boolean immediate) {
        boolean onlyWifi = prefs.getBoolean("only_wifi", false);
        NetworkType nt = onlyWifi ? NetworkType.UNMETERED : NetworkType.CONNECTED;
        Constraints con = new Constraints.Builder().setRequiredNetworkType(nt).build();

        if (immediate) {
            OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(BackupWorker.class)
                .setConstraints(con)
                .setInputData(new Data.Builder().putBoolean("is_manual", true).build())
                .build();
            WorkManager.getInstance(this).enqueue(req);
        }

        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(BackupWorker.class, prefs.getInt("sync_interval", 60), TimeUnit.MINUTES)
            .setConstraints(con).build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("PhotogramSync", ExistingPeriodicWorkPolicy.REPLACE, periodic);
    }

    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        active = false;
    }
}