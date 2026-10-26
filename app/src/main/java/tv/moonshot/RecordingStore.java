package tv.moonshot;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

final class RecordingStore extends SQLiteOpenHelper {
  static final class Item {
    String id, profile, channel, name, state, file;
    long start, end, bytes;
  }

  RecordingStore(Context context) {
    super(context, "recordings.db", null, 1);
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE recordings(id TEXT PRIMARY KEY,profile TEXT,channel TEXT,name TEXT,state"
            + " TEXT,start INTEGER,end INTEGER,file TEXT,bytes INTEGER DEFAULT 0)");
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int old, int next) {
    throw new IllegalStateException("Unsupported recordings version");
  }

  String add(String profile, String channel, String name, long start, long end) {
    if (end <= start || end - start > 2 * 3600000L)
      throw new IllegalArgumentException("Recordings are limited to two hours");
    Item item = new Item();
    item.id = UUID.randomUUID().toString();
    ContentValues v = new ContentValues();
    v.put("id", item.id);
    v.put("profile", profile);
    v.put("channel", channel);
    v.put("name", name);
    v.put("start", start);
    v.put("end", end);
    v.put("state", "scheduled");
    v.put("file", "");
    getWritableDatabase().insertOrThrow("recordings", null, v);
    return item.id;
  }

  Item get(String id) {
    try (Cursor c =
        getReadableDatabase().rawQuery("SELECT * FROM recordings WHERE id=?", new String[] {id})) {
      return c.moveToFirst() ? read(c) : null;
    }
  }

  List<Item> items(String profile) {
    List<Item> out = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                profile == null
                    ? "SELECT * FROM recordings ORDER BY start DESC"
                    : "SELECT * FROM recordings WHERE profile=? ORDER BY start DESC",
                profile == null ? null : new String[] {profile})) {
      while (c.moveToNext()) out.add(read(c));
    }
    return out;
  }

  Item read(Cursor c) {
    Item r = new Item();
    r.id = c.getString(c.getColumnIndexOrThrow("id"));
    r.profile = c.getString(c.getColumnIndexOrThrow("profile"));
    r.channel = c.getString(c.getColumnIndexOrThrow("channel"));
    r.name = c.getString(c.getColumnIndexOrThrow("name"));
    r.state = c.getString(c.getColumnIndexOrThrow("state"));
    r.file = c.getString(c.getColumnIndexOrThrow("file"));
    r.start = c.getLong(c.getColumnIndexOrThrow("start"));
    r.end = c.getLong(c.getColumnIndexOrThrow("end"));
    r.bytes = c.getLong(c.getColumnIndexOrThrow("bytes"));
    return r;
  }

  void state(String id, String state, String file, long bytes) {
    ContentValues v = new ContentValues();
    v.put("state", state);
    v.put("file", file);
    v.put("bytes", bytes);
    getWritableDatabase().update("recordings", v, "id=?", new String[] {id});
  }

  boolean claim(String id) {
    ContentValues v = new ContentValues();
    v.put("state", "recording");
    return getWritableDatabase()
            .update("recordings", v, "id=? AND state='scheduled'", new String[] {id})
        == 1;
  }

  void cancelScheduled(String id) {
    ContentValues v = new ContentValues();
    v.put("state", "cancelled");
    getWritableDatabase().update("recordings", v, "id=? AND state='scheduled'", new String[] {id});
  }

  boolean delete(String id) {
    return getWritableDatabase()
            .delete("recordings", "id=? AND state<>'recording'", new String[] {id})
        == 1;
  }
}
