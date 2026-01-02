package com.photogram.backup;
import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.work.*;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.lifecycle.Observer;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    ListView listView;
    SwipeRefreshLayout swipeRefresh;
    ArrayList<File> allFolders = new ArrayList<>(), filteredFolders = new ArrayList<>();
    BaseAdapter adapter;
    SharedPreferences prefs;
    DatabaseHelper dbHelper;
    TextView tvTotalStats, tvSyncStatus, tvCurrentFile;
    ProgressBar pbSync;

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
        observeSyncProgress();
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
                    String file = progress.getString("current_file");
                    if (file != null) {
                        tvCurrentFile.setText("Syncing: " + file);
                        tvSyncStatus.setText("Cloud Sync active...");
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
        tvSyncStatus.setText(last > 0 ? "Last Sync: " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(last)) : "Cloud Sync Ready");
    }

    private void filterFolders(String q) {
        filteredFolders.clear();
        for (File f : allFolders) if (f.getName().toLowerCase().contains(q.toLowerCase())) filteredFolders.add(f);
        adapter.notifyDataSetChanged();
    }

    private void handlePermissions() {
        String p = (Build.VERSION.SDK_INT >= 33) ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{p, Manifest.permission.POST_NOTIFICATIONS}, 101);
        else startAppLogic();
    }

    @Override
    public void onRequestPermissionsResult(int r, String[] p, int[] g) { startAppLogic(); }

    private void startAppLogic() {
        swipeRefresh.setRefreshing(true);
        new Thread(() -> {
            ArrayList<File> fresh = new ArrayList<>();
            recursiveScan(Environment.getExternalStorageDirectory(), fresh);
            dbHelper.saveFolders(fresh);
            runOnUiThread(() -> {
                allFolders.clear(); allFolders.addAll(fresh);
                filterFolders(""); swipeRefresh.setRefreshing(false); refreshDashboard();
            });
        }).start();
    }

    private void recursiveScan(File dir, ArrayList<File> list) {
        File[] files = dir.listFiles();
        if (files == null) return;
        boolean hasImg = false;
        for (File f : files) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".") && !f.getName().equalsIgnoreCase("Android")) recursiveScan(f, list);
            } else if (f.getName().toLowerCase().endsWith(".jpg") || f.getName().toLowerCase().endsWith(".png")) hasImg = true;
        }
        if (hasImg) list.add(dir);
    }

    private void scheduleBackup(boolean immediate) {
        Constraints c = new Constraints.Builder().setRequiredNetworkType(prefs.getBoolean("only_wifi", false) ? NetworkType.UNMETERED : NetworkType.CONNECTED).build();
        if (immediate) WorkManager.getInstance(this).enqueue(new OneTimeWorkRequest.Builder(BackupWorker.class).setConstraints(c).setInputData(new Data.Builder().putBoolean("is_manual", true).build()).build());
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("PhotogramSync", ExistingPeriodicWorkPolicy.KEEP, new PeriodicWorkRequest.Builder(BackupWorker.class, prefs.getInt("sync_interval", 60), java.util.concurrent.TimeUnit.MINUTES).setConstraints(c).build());
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