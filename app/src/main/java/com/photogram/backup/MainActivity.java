package com.photogram.backup;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.*;
import android.widget.*;
import android.view.*;
import androidx.work.*;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        prefs = getSharedPreferences("BackupPrefs", Context.MODE_PRIVATE);
        dbHelper = new DatabaseHelper(this);
        
        // Find Views with Safety Checks
        listView = findViewById(R.id.folderListView);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        tvTotalStats = findViewById(R.id.tvTotalStats);
        tvLastSync = findViewById(R.id.tvLastSync);
        EditText etSearch = findViewById(R.id.etSearch);

        if (swipeRefresh != null) {
            swipeRefresh.setColorSchemeColors(0xFF0088CC);
            swipeRefresh.setOnRefreshListener(this::startAppLogic);
        }

        // Button Listeners
        View btnSettings = findViewById(R.id.btnSettings);
        if (btnSettings != null) btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        View btnBackup = findViewById(R.id.btnStartBackup);
        if (btnBackup != null) btnBackup.setOnClickListener(v -> scheduleBackup(true));

        View btnLogs = findViewById(R.id.btnLogs);
        if (btnLogs != null) btnLogs.setOnClickListener(v -> startActivity(new Intent(this, LogActivity.class)));

        if (etSearch != null) {
            etSearch.addTextChangedListener(new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                public void onTextChanged(CharSequence s, int a, int b, int c) { filterFolders(s.toString()); }
                public void afterTextChanged(android.text.Editable s) {}
            });
        }

        setupAdapter();
        allFolders.addAll(dbHelper.getSavedFolders());
        filterFolders("");
        handlePermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDashboard();
    }

    private void refreshDashboard() {
        if (tvTotalStats != null) tvTotalStats.setText(dbHelper.getTotalBackupCount() + " Photos Backed Up");
        if (tvLastSync != null) {
            long last = prefs.getLong("last_sync_timestamp", 0);
            tvLastSync.setText(last > 0 ? "Last Sync: " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(last)) : "Last Sync: Never");
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
        if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{p, Manifest.permission.POST_NOTIFICATIONS}, 101);
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
                allFolders.clear();
                allFolders.addAll(fresh);
                filterFolders("");
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
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
                if (!f.getName().startsWith(".") && !f.getName().equalsIgnoreCase("Android")) recursiveScan(f, list);
            } else {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".jpg") || n.endsWith(".png") || n.endsWith(".webp")) hasImg = true;
            }
        }
        if (hasImg) list.add(dir);
    }

    private void scheduleBackup(boolean immediate) {
        Constraints c = new Constraints.Builder().setRequiredNetworkType(prefs.getBoolean("only_wifi", false) ? NetworkType.UNMETERED : NetworkType.CONNECTED).build();
        if (immediate) {
            WorkManager.getInstance(this).enqueue(new OneTimeWorkRequest.Builder(BackupWorker.class).setConstraints(c).setInputData(new Data.Builder().putBoolean("is_manual", true).build()).build());
            Toast.makeText(this, "Syncing...", Toast.LENGTH_SHORT).show();
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