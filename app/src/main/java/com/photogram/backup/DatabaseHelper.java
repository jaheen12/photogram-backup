package com.photogram.backup;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.File;
import java.util.ArrayList;

public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "photogram_history.db";
    private static final int DATABASE_VERSION = 2; // Incremented version

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // File History Table
        db.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, file_path TEXT, last_modified LONG, upload_date LONG)");
        db.execSQL("CREATE INDEX idx_path ON history (file_path)");

        // NEW: Folders Table
        db.execSQL("CREATE TABLE folders (path TEXT PRIMARY KEY, name TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE folders (path TEXT PRIMARY KEY, name TEXT)");
        }
    }

    // --- FOLDER PERSISTENCE METHODS ---

    public void saveFolders(ArrayList<File> folderList) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.beginTransaction();
        try {
            // We don't delete all, we use REPLACE to keep it efficient
            for (File f : folderList) {
                ContentValues v = new ContentValues();
                v.put("path", f.getAbsolutePath());
                v.put("name", f.getName());
                db.insertWithOnConflict("folders", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public ArrayList<File> getSavedFolders() {
        ArrayList<File> list = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query("folders", null, null, null, null, null, "name ASC");
        if (cursor.moveToFirst()) {
            do {
                list.add(new File(cursor.getString(0)));
            } while (cursor.moveToNext());
        }
        cursor.close();
        return list;
    }

    // --- EXISTING HISTORY METHODS ---
    public boolean isFileUploaded(String path, long modified) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query("history", new String[]{"id"}, "file_path = ? AND last_modified = ?", new String[]{path, String.valueOf(modified)}, null, null, null);
        boolean exists = cursor.getCount() > 0;
        cursor.close();
        return exists;
    }

    public void markAsUploaded(String path, long modified) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("file_path", path);
        values.put("last_modified", modified);
        values.put("upload_date", System.currentTimeMillis());
        db.insertWithOnConflict("history", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }
}