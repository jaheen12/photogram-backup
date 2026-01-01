package com.photogram.backup;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Button;
import java.util.ArrayList;

public class LogActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logs);
        
        DatabaseHelper db = new DatabaseHelper(this);
        ListView lv = findViewById(R.id.logListView);
        Button btnClose = findViewById(R.id.btnCloseLogs);
        
        // Get the last 50 logs from our new database
        ArrayList<String> logs = db.getRecentLogs();
        
        if (logs.isEmpty()) {
            logs.add("No logs found yet. Start a sync to see events.");
        }

        // Standard adapter for a simple list
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, 
            android.R.layout.simple_list_item_1, logs) {
            @Override
            public android.view.View getView(int position, android.view.View convertView, android.view.ViewGroup parent) {
                android.view.View view = super.getView(position, convertView, parent);
                android.widget.TextView text = (android.widget.TextView) view.findViewById(android.R.id.text1);
                text.setTextColor(0xFFCCCCCC); // Light gray text for dark mode
                text.setTextSize(13f);
                return view;
            }
        };

        lv.setAdapter(adapter);

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> finish());
        }
    }
}