package com.photogram.backup;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "photogram_history.db";
    private static final int DATABASE_VERSION = 1;

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // Table to store uploaded file details
        db.execSQL("CREATE TABLE history (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "file_path TEXT," +
                "last_modified LONG," +
                "upload_date LONG)");
        
        // Indexing the file_path makes searching 100x faster
        db.execSQL("CREATE INDEX idx_path ON history (file_path)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS history");
        onCreate(db);
    }

    // Check if file is already backed up
    public boolean isFileUploaded(String path, long modified) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query("history", new String[]{"id"}, 
                "file_path = ? AND last_modified = ?", 
                new String[]{path, String.valueOf(modified)}, 
                null, null, null);
        
        boolean exists = cursor.getCount() > 0;
        cursor.close();
        return exists;
    }

    // Mark file as backed up
    public void markAsUploaded(String path, long modified) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("file_path", path);
        values.put("last_modified", modified);
        values.put("upload_date", System.currentTimeMillis());
        
        // Update if exists, otherwise insert
        db.insertWithOnConflict("history", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }
}