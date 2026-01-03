package com.photogram.backup;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
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

        findViewById(R.id.btnLogin).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loginUser();
            }
        });

        findViewById(R.id.btnRegister).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                registerUser();
            }
        });
        
        if (auth.getCurrentUser() != null) {
            checkApprovalStatus();
        }
    }

    private void registerUser() {
        final String email = etEmail.getText().toString().trim();
        String pass = etPassword.getText().toString().trim();

        if (email.isEmpty() || pass.length() < 6) {
            Toast.makeText(this, "Enter email and 6+ char password", Toast.LENGTH_SHORT).show();
            return;
        }

        tvInfo.setText("Creating secure account...");
        auth.createUserWithEmailAndPassword(email, pass).addOnSuccessListener(authResult -> {
            String uid = auth.getCurrentUser().getUid();
            HashMap<String, Object> userMap = new HashMap<>();
            userMap.put("email", email);
            userMap.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            userMap.put("status", "pending");
            userMap.put("daily_limit", 20);
            userMap.put("usage_count", 0);
            userMap.put("last_sync_date", "never");

            db.getReference("users").child(uid).setValue(userMap).addOnSuccessListener(aVoid -> {
                tvInfo.setText("Registered! Please ask Admin for approval.");
                auth.signOut();
            });
        }).addOnFailureListener(e -> {
            tvInfo.setText("Registration Failed: " + e.getMessage());
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
            });
    }

    private void checkApprovalStatus() {
        String uid = auth.getCurrentUser().getUid();
        // Real-time listener: The app opens instantly when you change the status in Firebase
        db.getReference("users").child(uid).addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (!snapshot.exists()) return;
                String status = snapshot.child("status").getValue(String.class);
                
                if ("approved".equals(status) || "limited".equals(status)) {
                    if (!MainActivity.active) {
                        Intent intent = new Intent(LoginActivity.this, MainActivity.class);
                        startActivity(intent);
                        finish();
                    }
                } else {
                    tvInfo.setText("Access Denied: Account is " + (status == null ? "pending" : status));
                    tvInfo.setTextColor(android.graphics.Color.RED);
                    auth.signOut();
                }
            }
            @Override public void onCancelled(DatabaseError error) {}
        });
    }
}