package com.photogram.backup;

import android.content.Intent;
import android.os.Build; // Required for device model
import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.HashMap;

public class LoginActivity extends AppCompatActivity {
    private FirebaseAuth auth;
    private FirebaseDatabase db;
    private EditText etEmail, etPassword;
    private TextView tvInfo;

    private static final String DB_URL = "https://photogram-dd154-default-rtdb.asia-southeast1.firebasedatabase.app/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        auth = FirebaseAuth.getInstance();
        db = FirebaseDatabase.getInstance(DB_URL);
        
        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        tvInfo = findViewById(R.id.tvStatusInfo);

        findViewById(R.id.btnLogin).setOnClickListener(v -> loginUser());
        findViewById(R.id.btnRegister).setOnClickListener(v -> registerUser());
        
        if (auth.getCurrentUser() != null) checkApprovalStatus();
    }

    private void registerUser() {
        String email = etEmail.getText().toString().trim();
        String pass = etPassword.getText().toString().trim();

        if (email.isEmpty() || pass.length() < 6) {
            Toast.makeText(this, "Valid email and 6+ password required", Toast.LENGTH_SHORT).show();
            return;
        }

        tvInfo.setText("Registering in Singapore Cloud...");
        auth.createUserWithEmailAndPassword(email, pass).addOnSuccessListener(authResult -> {
            String uid = auth.getCurrentUser().getUid();
            
            HashMap<String, Object> userMap = new HashMap<>();
            userMap.put("email", email);
            userMap.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            userMap.put("status", "pending");
            userMap.put("daily_limit", 20);
            userMap.put("usage_count", 0);
            userMap.put("last_sync_date", "never");

            db.getReference("users").child(uid).setValue(userMap)
                .addOnSuccessListener(aVoid -> {
                    tvInfo.setText("Success! Ask admin to approve: " + email);
                    tvInfo.setTextColor(0xFFFFA500); 
                    auth.signOut();
                });
        }).addOnFailureListener(e -> {
            tvInfo.setText("Error: " + e.getMessage());
            tvInfo.setTextColor(android.graphics.Color.RED);
        });
    }

    private void loginUser() {
        String email = etEmail.getText().toString().trim();
        String pass = etPassword.getText().toString().trim();
        if (email.isEmpty() || pass.isEmpty()) return;

        tvInfo.setText("Verifying...");
        auth.signInWithEmailAndPassword(email, pass)
            .addOnSuccessListener(r -> checkApprovalStatus())
            .addOnFailureListener(e -> {
                tvInfo.setText("Login Failed: " + e.getMessage());
                tvInfo.setTextColor(android.graphics.Color.RED);
            });
    }

    private void checkApprovalStatus() {
        String uid = auth.getCurrentUser().getUid();
        db.getReference("users").child(uid).child("status")
            .addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                String status = snapshot.getValue(String.class);
                if ("approved".equals(status) || "limited".equals(status)) {
                    startActivity(new Intent(LoginActivity.this, MainActivity.class));
                    finish();
                } else {
                    tvInfo.setText("Denied: " + (status == null ? "pending" : status));
                    tvInfo.setTextColor(android.graphics.Color.RED);
                    auth.signOut();
                }
            }
            @Override public void onCancelled(DatabaseError error) {}
        });
    }
}