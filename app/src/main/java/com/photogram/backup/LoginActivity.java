package com.photogram.backup;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        auth = FirebaseAuth.getInstance();
        db = FirebaseDatabase.getInstance();
        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        tvInfo = findViewById(R.id.tvStatusInfo);

        findViewById(R.id.btnLogin).setOnClickListener(v -> loginUser());
        findViewById(R.id.btnRegister).setOnClickListener(v -> registerUser());
        
        // If user is already logged in, check their status immediately
        if (auth.getCurrentUser() != null) {
            checkApprovalStatus();
        }
    }

    private void registerUser() {
        String email = etEmail.getText().toString().trim();
        String pass = etPassword.getText().toString().trim();

        if (email.isEmpty() || pass.length() < 6) {
            Toast.makeText(this, "Enter valid email and 6+ char password", Toast.LENGTH_SHORT).show();
            return;
        }

        tvInfo.setText("Creating account...");
        auth.createUserWithEmailAndPassword(email, pass).addOnSuccessListener(authResult -> {
            String uid = auth.getCurrentUser().getUid();
            
            // Create initial user record in Cloud Database
            HashMap<String, Object> userMap = new HashMap<>();
            userMap.put("status", "pending");
            userMap.put("daily_limit", 20); // Default limit for new users
            userMap.put("usage_count", 0);
            userMap.put("last_sync_date", "never");

            db.getReference("users").child(uid).setValue(userMap)
                .addOnSuccessListener(aVoid -> {
                    tvInfo.setText("Registration successful! Wait for admin approval.");
                    tvInfo.setTextColor(0xFFFFA500); // Orange
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

        tvInfo.setText("Authenticating...");
        auth.signInWithEmailAndPassword(email, pass)
            .addOnSuccessListener(r -> checkApprovalStatus())
            .addOnFailureListener(e -> {
                tvInfo.setText("Login Failed: " + e.getMessage());
                tvInfo.setTextColor(android.graphics.Color.RED);
            });
    }

    private void checkApprovalStatus() {
        String uid = auth.getCurrentUser().getUid();
        tvInfo.setText("Checking access permissions...");

        db.getReference("users").child(uid).child("status")
            .addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                String status = snapshot.getValue(String.class);
                
                // Allow entry only if status is "approved" or "limited"
                if ("approved".equals(status) || "limited".equals(status)) {
                    startActivity(new Intent(LoginActivity.this, MainActivity.class));
                    finish();
                } else {
                    tvInfo.setText("Access Denied: Account is " + (status == null ? "pending" : status));
                    tvInfo.setTextColor(android.graphics.Color.RED);
                    auth.signOut();
                }
            }
            @Override public void onCancelled(DatabaseError error) {
                tvInfo.setText("Database error. Try again.");
            }
        });
    }
}