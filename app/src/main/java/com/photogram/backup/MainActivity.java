package com.photogram.backup;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;

public class MainActivity extends Activity {
    ListView listView;
    ArrayList<File> folders = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Simple UI directly in Java for first launch
        listView = new ListView(this);
        setContentView(listView);
        
        scanFolders();
        
        listView.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() { return folders.size(); }
            @Override
            public Object getItem(int i) { return folders.get(i); }
            @Override
            public long getItemId(int i) { return i; }
            @Override
            public View getView(int i, View v, ViewGroup p) {
                if (v == null) v = LayoutInflater.from(MainActivity.this).inflate(android.R.layout.simple_list_item_2, null);
                TextView t1 = v.findViewById(android.R.id.text1);
                TextView t2 = v.findViewById(android.R.id.text2);
                t1.setText(folders.get(i).getName());
                t2.setText(folders.get(i).getAbsolutePath());
                return v;
            }
        });
    }

    void scanFolders() {
        File[] roots = {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        };
        for (File root : roots) {
            if (root.exists()) folders.add(root);
        }
    }
}