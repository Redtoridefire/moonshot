package tv.moonshot;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

final class Library extends SQLiteOpenHelper {
  final Vault vault;

  Library(Context context) throws Exception {
    this(context, new Vault());
  }

  Library(Context context, String profile) throws Exception {
    this(context, new Vault(), profile);
  }

  Library(Context context, Vault vault) {
    this(context, vault, Family.active(context));
  }

  Library(Context context, Vault vault, String profile) {
    super(context, Family.database(profile), null, 6);
    this.vault = vault;
    setWriteAheadLoggingEnabled(true);
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE sources(id TEXT PRIMARY KEY,name TEXT NOT NULL,url TEXT NOT NULL,updated"
            + " INTEGER,url_key TEXT)");
    db.execSQL("CREATE UNIQUE INDEX source_url ON sources(url_key)");
    db.execSQL(
        "CREATE TABLE channels(id TEXT PRIMARY KEY,source TEXT,name TEXT,grp TEXT,tvg TEXT,url"
            + " TEXT,kind TEXT,favorite INTEGER DEFAULT 0,position INTEGER DEFAULT 0,seen INTEGER"
            + " DEFAULT 0,hidden INTEGER DEFAULT 0,url_key TEXT,custom_group TEXT,sort_order"
            + " INTEGER,catchup_mode TEXT DEFAULT '',catchup_source TEXT DEFAULT '',catchup_days"
            + " INTEGER DEFAULT 0,catalog_owned INTEGER DEFAULT 0)");
    db.execSQL("CREATE INDEX channel_filter ON channels(kind,grp)");
    db.execSQL("CREATE INDEX channel_url ON channels(source,url_key)");
    db.execSQL("CREATE INDEX channel_identity ON channels(source,kind,tvg,name,grp,seen)");
    db.execSQL(
        "CREATE TABLE programs(channel TEXT,start INTEGER,stop INTEGER,title TEXT,description"
            + " TEXT)");
    db.execSQL("CREATE INDEX program_time ON programs(channel,start,stop)");
    db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT)");
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
    if (oldV < 2) {
      db.execSQL("ALTER TABLE channels ADD COLUMN hidden INTEGER DEFAULT 0");
      db.execSQL("ALTER TABLE channels ADD COLUMN url_key TEXT");
      try (Cursor c = db.rawQuery("SELECT id,url FROM channels", null)) {
        while (c.moveToNext()) {
          ContentValues v = new ContentValues();
          try {
            v.put("url_key", hash(vault.open(c.getString(1))));
          } catch (Exception e) {
            throw new SQLiteException("Unable to migrate encrypted library");
          }
          db.update("channels", v, "id=?", new String[] {c.getString(0)});
        }
      }
      db.execSQL("CREATE INDEX channel_url ON channels(source,url_key)");
      db.execSQL("CREATE INDEX channel_identity ON channels(source,kind,tvg,name,grp,seen)");
    }
    if (oldV < 3) {
      db.execSQL("ALTER TABLE channels ADD COLUMN custom_group TEXT");
      db.execSQL("ALTER TABLE channels ADD COLUMN sort_order INTEGER");
    }
    if (oldV < 4) {
      db.execSQL("ALTER TABLE channels ADD COLUMN catchup_mode TEXT DEFAULT ''");
      db.execSQL("ALTER TABLE channels ADD COLUMN catchup_source TEXT DEFAULT ''");
      db.execSQL("ALTER TABLE channels ADD COLUMN catchup_days INTEGER DEFAULT 0");
      db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT)");
    }
    if (oldV < 5) {
      db.execSQL("ALTER TABLE sources ADD COLUMN url_key TEXT");
      try (Cursor rows = db.rawQuery("SELECT id,url FROM sources", null)) {
        while (rows.moveToNext()) {
          ContentValues v = new ContentValues();
          try {
            v.put("url_key", hash(vault.open(rows.getString(1))));
          } catch (Exception e) {
            throw new SQLiteException("Unable to migrate source");
          }
          db.update("sources", v, "id=?", new String[] {rows.getString(0)});
        }
      }
      db.execSQL("CREATE UNIQUE INDEX source_url ON sources(url_key)");
    }
    if (oldV < 6) db.execSQL("ALTER TABLE channels ADD COLUMN catalog_owned INTEGER DEFAULT 0");
  }

  static String hash(String s) throws Exception {
    byte[] bytes = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    StringBuilder b = new StringBuilder();
    for (byte a : bytes) b.append(String.format(Locale.ROOT, "%02x", a));
    return b.toString();
  }

  synchronized int importSource(String name, String address, boolean allowHttp) throws Exception {
    return replaceSource(null, name, address, allowHttp);
  }

  synchronized int replaceSource(String existing, String name, String address, boolean allowHttp)
      throws Exception {
    if (name.trim().isEmpty() || name.length() > 512)
      throw new IllegalArgumentException("Invalid source name");
    String key = hash(address), found = null;
    try (Cursor row =
        getReadableDatabase()
            .rawQuery("SELECT id FROM sources WHERE url_key=?", new String[] {key})) {
      if (row.moveToFirst()) found = row.getString(0);
    }
    if (existing != null && found != null && !existing.equals(found))
      throw new IOException("Source address already exists");
    String candidate = existing != null ? existing : found != null ? found : key;
    if (existing == null
        && found == null
        && android.database.DatabaseUtils.longForQuery(
                getReadableDatabase(),
                "SELECT COUNT(*) FROM sources WHERE id=?",
                new String[] {candidate})
            > 0) candidate = hash(key + UUID.randomUUID());
    final String source = candidate;
    if (existing != null
        && android.database.DatabaseUtils.longForQuery(
                getReadableDatabase(),
                "SELECT COUNT(*) FROM sources WHERE id=?",
                new String[] {existing})
            != 1) throw new IOException("Missing source");
    try (InputStream stream = Network.fetch(address, allowHttp);
        Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
      SQLiteDatabase db = getWritableDatabase();
      db.beginTransaction();
      try {
        db.execSQL(
            "UPDATE channels SET seen=0 WHERE source=? AND catalog_owned=0", new Object[] {source});
        int count =
            M3uParser.parse(
                reader,
                ch -> {
                  String urlKey = hash(ch.url);
                  String id = existingId(db, source, urlKey, ch);
                  if (id == null) {
                    id = hash(source + "\n" + ch.url);
                    if (android.database.DatabaseUtils.longForQuery(
                            db, "SELECT COUNT(*) FROM channels WHERE id=?", new String[] {id})
                        != 0) id = hash(source + "\n" + ch.url + UUID.randomUUID());
                  }
                  ContentValues v = new ContentValues();
                  v.put("id", id);
                  v.put("source", source);
                  v.put("name", ch.name);
                  v.put("grp", ch.group);
                  v.put("tvg", ch.tvgId);
                  v.put("url", vault.seal(ch.url));
                  v.put("url_key", urlKey);
                  v.put("catchup_mode", ch.catchupMode);
                  v.put(
                      "catchup_source",
                      ch.catchupSource.isEmpty() ? "" : vault.seal(ch.catchupSource));
                  v.put("catchup_days", ch.catchupDays);
                  v.put("kind", ch.kind);
                  v.put("seen", 1);
                  int updated = db.update("channels", v, "id=?", new String[] {id});
                  if (updated == 0) {
                    v.put("seen", 2);
                    db.insertOrThrow("channels", null, v);
                  }
                });
        reconcile(db, source);
        db.delete("channels", "source=? AND seen=0 AND catalog_owned=0", new String[] {source});
        ContentValues s = new ContentValues();
        s.put("id", source);
        s.put("name", name);
        s.put("url", vault.seal(address));
        s.put("updated", System.currentTimeMillis());
        s.put("url_key", key);
        if (db.update("sources", s, "id=?", new String[] {source}) == 0)
          db.insertOrThrow("sources", null, s);
        db.setTransactionSuccessful();
        return count;
      } finally {
        db.endTransaction();
      }
    }
  }

  // Prefer exact stream identity. Only inherit state from a unique, unmatched
  // metadata candidate: duplicate channel labels must never merge arbitrarily.
  private String existingId(SQLiteDatabase db, String source, String urlKey, Channel ch) {
    try (Cursor c =
        db.rawQuery(
            "SELECT id FROM channels WHERE source=? AND url_key=? LIMIT 1",
            new String[] {source, urlKey})) {
      return c.moveToFirst() ? c.getString(0) : null;
    }
  }

  private void reconcile(SQLiteDatabase db, String source) {
    if (android.database.DatabaseUtils.longForQuery(
            db, "SELECT COUNT(*) FROM channels WHERE source=? AND seen=0", new String[] {source})
        == 0) {
      db.execSQL("UPDATE channels SET seen=1 WHERE source=? AND seen=2", new Object[] {source});
      return;
    }
    List<String> pending = new ArrayList<>();
    try (Cursor ids =
        db.rawQuery("SELECT id FROM channels WHERE source=? AND seen=2", new String[] {source})) {
      while (ids.moveToNext()) pending.add(ids.getString(0));
    }
    for (String pendingId : pending) {
      try (Cursor fresh =
          db.rawQuery("SELECT * FROM channels WHERE id=?", new String[] {pendingId})) {
        while (fresh.moveToNext()) {
          String id = fresh.getString(fresh.getColumnIndexOrThrow("id"));
          String[] identity = {
            source,
            fresh.getString(fresh.getColumnIndexOrThrow("kind")),
            fresh.getString(fresh.getColumnIndexOrThrow("tvg")),
            fresh.getString(fresh.getColumnIndexOrThrow("name")),
            fresh.getString(fresh.getColumnIndexOrThrow("grp"))
          };
          String match = "source=? AND kind=? AND tvg=? AND name=? AND grp=?";
          // Reconcile only after reading the complete playlist. This prevents a new
          // duplicate stealing the state of an exact URL that appears later.
          try (Cursor incoming =
                  db.rawQuery(
                      "SELECT id FROM channels WHERE " + match + " AND seen<>0 LIMIT 2", identity);
              Cursor previous =
                  db.rawQuery(
                      "SELECT id FROM channels WHERE " + match + " AND seen=0 LIMIT 2", identity)) {
            if (incoming.getCount() != 1 || previous.getCount() != 1) continue;
            previous.moveToFirst();
            String oldId = previous.getString(0);
            ContentValues values = new ContentValues();
            for (String column :
                new String[] {
                  "name", "grp", "tvg", "url", "kind", "url_key", "catchup_mode", "catchup_source"
                }) values.put(column, fresh.getString(fresh.getColumnIndexOrThrow(column)));
            values.put("catchup_days", fresh.getInt(fresh.getColumnIndexOrThrow("catchup_days")));
            values.put("seen", 1);
            db.update("channels", values, "id=?", new String[] {oldId});
            db.delete("channels", "id=?", new String[] {id});
          }
        }
      }
    }
    db.execSQL("UPDATE channels SET seen=1 WHERE source=? AND seen=2", new Object[] {source});
  }

  String catalogStream(
      XtreamCatalog.Account account, XtreamCatalog.Entry entry, String kind, String group)
      throws Exception {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      if (android.database.DatabaseUtils.longForQuery(
              db, "SELECT COUNT(*) FROM sources WHERE id=?", new String[] {account.source})
          != 1) throw new IOException("Missing account");
      String address = XtreamCatalog.stream(account, entry, kind), key = hash(address), id = null;
      try (Cursor c =
          db.rawQuery(
              "SELECT id FROM channels WHERE source=? AND url_key=?",
              new String[] {account.source, key})) {
        if (c.moveToFirst()) id = c.getString(0);
      }
      if (id == null) id = hash(account.source + address + UUID.randomUUID());
      ContentValues values = new ContentValues();
      values.put("id", id);
      values.put("source", account.source);
      values.put("name", entry.name);
      values.put("grp", group);
      values.put("tvg", entry.guide);
      values.put("url", vault.seal(address));
      values.put("url_key", key);
      values.put("kind", kind);
      values.put("seen", 1);
      values.put("catalog_owned", 1);
      if (db.update("channels", values, "id=?", new String[] {id}) == 0)
        db.insertOrThrow("channels", null, values);
      db.setTransactionSuccessful();
      return id;
    } finally {
      db.endTransaction();
    }
  }

  void hidden(String id, boolean hidden) {
    ContentValues v = new ContentValues();
    v.put("hidden", hidden ? 1 : 0);
    getWritableDatabase().update("channels", v, "id=?", new String[] {id});
  }

  List<Channel> hiddenChannels() {
    List<Channel> result = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .query("channels", null, "hidden=1", null, null, null, "name COLLATE NOCASE", "5000")) {
      while (c.moveToNext()) result.add(read(c, false));
    }
    return result;
  }

  static final class Source {
    String id, name, url;

    Source(String id, String name, String url) {
      this.id = id;
      this.name = name;
      this.url = url;
    }
  }

  List<Source> sources() throws Exception {
    List<Source> out = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase().rawQuery("SELECT id,name,url FROM sources ORDER BY name", null)) {
      while (c.moveToNext())
        out.add(new Source(c.getString(0), c.getString(1), vault.open(c.getString(2))));
    }
    return out;
  }

  void removeSource(String id) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      db.delete("channels", "source=?", new String[] {id});
      db.delete("sources", "id=?", new String[] {id});
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  List<String> groups(String kind) {
    List<String> out = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT DISTINCT grp FROM channels WHERE hidden=0 AND kind=? ORDER BY grp COLLATE"
                    + " NOCASE",
                new String[] {kind})) {
      while (c.moveToNext()) out.add(c.getString(0));
    }
    return out;
  }

  List<Channel> channels(String kind, String group, String query, boolean favorites) {
    return channels(kind, group, query, favorites, null);
  }

  List<Channel> channels(
      String kind, String group, String query, boolean favorites, String custom) {
    List<Channel> out = new ArrayList<>();
    List<String> args = new ArrayList<>();
    String where = "hidden=0";
    if (kind != null) {
      where += " AND kind=?";
      args.add(kind);
    }
    if (group != null) {
      where += " AND grp=?";
      args.add(group);
    }
    if (query != null && !query.isEmpty()) {
      where += " AND name LIKE ? ESCAPE '\\'";
      args.add("%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
    }
    if (custom != null) {
      where += " AND custom_group=?";
      args.add(custom);
    }
    if (favorites) where += " AND favorite=1";
    try (Cursor c =
        getReadableDatabase()
            .query(
                "channels",
                null,
                where,
                args.toArray(new String[0]),
                null,
                null,
                "sort_order IS NULL,sort_order,name COLLATE NOCASE",
                "5000")) {
      while (c.moveToNext()) out.add(read(c, false));
    }
    return out;
  }

  Channel channel(String id) throws Exception {
    try (Cursor c =
        getReadableDatabase()
            .query("channels", null, "id=?", new String[] {id}, null, null, null)) {
      if (!c.moveToFirst()) throw new IOException("Missing channel");
      Channel ch = read(c, true);
      ch.url = vault.open(ch.url);
      ch.catchupMode = c.getString(c.getColumnIndexOrThrow("catchup_mode"));
      ch.catchupDays = c.getInt(c.getColumnIndexOrThrow("catchup_days"));
      String template = c.getString(c.getColumnIndexOrThrow("catchup_source"));
      ch.catchupSource = template.isEmpty() ? "" : vault.open(template);
      return ch;
    }
  }

  private Channel read(Cursor c, boolean url) {
    Channel ch =
        new Channel(
            c.getString(c.getColumnIndexOrThrow("name")),
            c.getString(c.getColumnIndexOrThrow("grp")),
            c.getString(c.getColumnIndexOrThrow("tvg")),
            url ? c.getString(c.getColumnIndexOrThrow("url")) : "",
            c.getString(c.getColumnIndexOrThrow("kind")));
    ch.id = c.getString(c.getColumnIndexOrThrow("id"));
    ch.source = c.getString(c.getColumnIndexOrThrow("source"));
    ch.favorite = c.getInt(c.getColumnIndexOrThrow("favorite")) != 0;
    ch.position = c.getLong(c.getColumnIndexOrThrow("position"));
    return ch;
  }

  List<String> customGroups() {
    List<String> out = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT DISTINCT custom_group FROM channels WHERE custom_group IS NOT NULL ORDER BY"
                    + " custom_group COLLATE NOCASE",
                null)) {
      while (c.moveToNext()) out.add(c.getString(0));
    }
    return out;
  }

  void customGroup(String id, String group) {
    if (group != null && (group.trim().isEmpty() || group.length() > 80))
      throw new IllegalArgumentException("Invalid group name");
    ContentValues v = new ContentValues();
    if (group == null) v.putNull("custom_group");
    else v.put("custom_group", group.trim());
    getWritableDatabase().update("channels", v, "id=?", new String[] {id});
  }

  void deleteCustomGroup(String group) {
    ContentValues v = new ContentValues();
    v.putNull("custom_group");
    getWritableDatabase().update("channels", v, "custom_group=?", new String[] {group});
  }

  void reorder(List<Channel> scope, String id, int delta) {
    List<Channel> ordered = new ArrayList<>(scope);
    int at = -1;
    for (int i = 0; i < ordered.size(); i++)
      if (id.equals(ordered.get(i).id)) {
        at = i;
        break;
      }
    if (at < 0 || at + delta < 0 || at + delta >= ordered.size()) return;
    Collections.swap(ordered, at, at + delta);
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      for (int i = 0; i < ordered.size(); i++) {
        ContentValues v = new ContentValues();
        v.put("sort_order", i);
        db.update("channels", v, "id=?", new String[] {ordered.get(i).id});
      }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  void resetOrder() {
    getWritableDatabase().execSQL("UPDATE channels SET sort_order=NULL");
  }

  void setting(String key, String value) {
    ContentValues v = new ContentValues();
    v.put("key", key);
    v.put("value", value);
    getWritableDatabase()
        .insertWithOnConflict("settings", null, v, SQLiteDatabase.CONFLICT_REPLACE);
  }

  String setting(String key) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT value FROM settings WHERE key=?", new String[] {key})) {
      return c.moveToFirst() ? c.getString(0) : null;
    }
  }

  void favorite(Channel ch) {
    ContentValues v = new ContentValues();
    v.put("favorite", ch.favorite ? 0 : 1);
    getWritableDatabase().update("channels", v, "id=?", new String[] {ch.id});
  }

  void position(String id, long ms) {
    ContentValues v = new ContentValues();
    v.put("position", ms);
    getWritableDatabase().update("channels", v, "id=?", new String[] {id});
  }

  String now(String tvg) {
    long t = System.currentTimeMillis();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT title FROM programs WHERE channel=? AND start<=? AND stop>? ORDER BY start"
                    + " DESC LIMIT 1",
                new String[] {tvg, Long.toString(t), Long.toString(t)})) {
      return c.moveToFirst() ? c.getString(0) : "";
    }
  }

  List<String> guide(String tvg) {
    List<String> out = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT start,stop,title,description FROM programs WHERE channel=? AND stop>? ORDER"
                    + " BY start LIMIT 30",
                new String[] {tvg, Long.toString(System.currentTimeMillis())})) {
      java.text.DateFormat f = new java.text.SimpleDateFormat("EEE HH:mm", Locale.getDefault());
      while (c.moveToNext())
        out.add(
            f.format(new Date(c.getLong(0)))
                + " — "
                + f.format(new Date(c.getLong(1)))
                + "\n"
                + c.getString(2)
                + "\n"
                + c.getString(3));
    }
    return out;
  }
}
