package com.photogram.backup;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.Toast;

public class SettingsActivity extends Activity {
    SharedPreferences prefs;
    EditText etToken, etChatId, etInterval;
    RadioButton rbWifi, rbAny;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("BackupPrefs", Context.MODE_PRIVATE);
        etToken = findViewById(R.id.etBotToken);
        etChatId = findViewById(R.id.etChatId);
        etInterval = findViewById(R.id.etInterval);
        rbWifi = findViewById(R.id.rbWifi);
        rbAny = findViewById(R.id.rbAny);
        Button btnSave = findViewById(R.id.btnSave);

        // Load existing values
        etToken.setText(prefs.getString("bot_token", ""));
        etChatId.setText(prefs.getString("chat_id", ""));
        etInterval.setText(String.valueOf(prefs.getInt("sync_interval", 60)));
        
        // Load network preference (default is Any)
        boolean onlyWifi = prefs.getBoolean("only_wifi", false);
        if (onlyWifi) rbWifi.setChecked(true);
        else rbAny.setChecked(true);

        btnSave.setOnClickListener(v -> {
            prefs.edit()
                .putString("bot_token", etToken.getText().toString())
                .putString("chat_id", etChatId.getText().toString())
                .putInt("sync_interval", Integer.parseInt(etInterval.getText().toString()))
                .putBoolean("only_wifi", rbWifi.isChecked())
                .apply();
            
            Toast.makeText(this, "Settings Saved!", Toast.LENGTH_SHORT).show();
            finish();
        });
    }
}