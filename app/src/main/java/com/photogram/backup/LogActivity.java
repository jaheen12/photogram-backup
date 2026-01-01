package com.photogram.backup;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;

public class LogActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logs);
        
        DatabaseHelper db = new DatabaseHelper(this);
        ListView lv = findViewById(R.id.logListView);
        
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, 
            android.R.layout.simple_list_item_1, db.getRecentLogs());
        lv.setAdapter(adapter);
    }
}