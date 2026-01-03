package com.photogram.backup;

import android.app.Application;
import com.google.firebase.database.FirebaseDatabase;

public class PhotogramApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // This allows the app to remember your "Approved" status even when offline
        FirebaseDatabase.getInstance("https://photogram-dd154-default-rtdb.asia-southeast1.firebasedatabase.app/")
            .setPersistenceEnabled(true);
    }
}