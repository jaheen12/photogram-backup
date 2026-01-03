package com.photogram.backup;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;

public class DatabaseHelper extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "photogram_v5.db";
    private static final int DATABASE_VERSION = 1;

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, file_path TEXT, last_modified LONG, upload_date LONG)");
        db.execSQL("CREATE INDEX idx_path ON history (file_path)");
        db.execSQL("CREATE TABLE folders (path TEXT PRIMARY KEY, name TEXT)");
        db.execSQL("CREATE TABLE logs (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp LONG, type TEXT, message TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int old, int next) {}

    public String exportHistoryToJson() {
        SQLiteDatabase db = this.getReadableDatabase();
        JSONArray array = new JSONArray();
        try (Cursor c = db.query("history", new String[]{"file_path", "last_modified"}, null, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                do {
                    JSONObject obj = new JSONObject();
                    obj.put("p", c.getString(0));
                    obj.put("m", c.getLong(1));
                    array.put(obj);
                } while (c.moveToNext());
            }
        } catch (Exception e) { e.printStackTrace(); }
        return array.toString();
    }

    public void importHistoryFromJson(String json) {
        SQLiteDatabase db = this.getWritableDatabase();
        try {
            JSONArray array = new JSONArray(json);
            db.beginTransaction();
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                ContentValues v = new ContentValues();
                v.put("file_path", obj.getString("p"));
                v.put("last_modified", obj.getLong("m"));
                v.put("upload_date", System.currentTimeMillis());
                db.insertWithOnConflict("history", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } catch (Exception e) { e.printStackTrace(); }
        finally { db.endTransaction(); }
    }

    public void addLog(String type, String message) {
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            ContentValues v = new ContentValues();
            v.put("timestamp", System.currentTimeMillis());
            v.put("type", type);
            v.put("message", message);
            db.insert("logs", null, v);
            db.execSQL("DELETE FROM logs WHERE id NOT IN (SELECT id FROM logs ORDER BY timestamp DESC LIMIT 50)");
        } catch (Exception e) {}
    }

    public ArrayList<String> getRecentLogs() {
        ArrayList<String> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("logs", null, null, null, null, null, "timestamp DESC")) {
            if (c != null && c.moveToFirst()) {
                do { list.add("[" + c.getString(2) + "] " + c.getString(3)); } while (c.moveToNext());
            }
        } catch (Exception e) {}
        return list;
    }

    public int getTotalBackupCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM history", null)) {
            if (c != null && c.moveToFirst()) return c.getInt(0);
        } catch (Exception e) {}
        return 0;
    }

    public void saveFolders(ArrayList<File> folderList) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.beginTransaction();
        try {
            for (File f : folderList) {
                ContentValues v = new ContentValues();
                v.put("path", f.getAbsolutePath());
                v.put("name", f.getName());
                db.insertWithOnConflict("folders", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public ArrayList<File> getSavedFolders() {
        ArrayList<File> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("folders", null, null, null, null, null, "name ASC")) {
            if (c != null && c.moveToFirst()) {
                do { list.add(new File(c.getString(0))); } while (c.moveToNext());
            }
        } catch (Exception e) {}
        return list;
    }

    public boolean isFileUploaded(String path, long modified) {
        try (Cursor c = getReadableDatabase().query("history", new String[]{"id"}, "file_path = ? AND last_modified = ?", new String[]{path, String.valueOf(modified)}, null, null, null)) {
            return c != null && c.getCount() > 0;
        } catch (Exception e) { return false; }
    }

    public void markAsUploaded(String path, long modified) {
        ContentValues v = new ContentValues();
        v.put("file_path", path);
        v.put("last_modified", modified);
        v.put("upload_date", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("history", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }
}