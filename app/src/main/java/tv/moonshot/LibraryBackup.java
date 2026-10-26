package tv.moonshot;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.JsonReader;
import android.util.JsonWriter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Portable library state; no device Keystore key, PIN, or guide cache is exported. */
final class LibraryBackup {
  static final String[] SOURCE = {"id", "name", "url"};
  static final String[] CHANNEL = {
    "id",
    "source",
    "name",
    "grp",
    "tvg",
    "url",
    "kind",
    "favorite",
    "position",
    "hidden",
    "custom_group",
    "sort_order",
    "catchup_mode",
    "catchup_source",
    "catchup_days",
    "catalog_owned"
  };

  static boolean secret(String column) {
    return column.equals("url") || column.equals("catchup_source");
  }

  static void exportLibrary(Library library, OutputStream output, char[] password)
      throws Exception {
    SQLiteDatabase db = library.getReadableDatabase();
    db.beginTransaction();
    try (javax.crypto.CipherOutputStream encrypted = BackupCrypto.encrypt(output, password);
        JsonWriter writer =
            new JsonWriter(
                new OutputStreamWriter(
                    new FilterOutputStream(encrypted) {
                      long count;

                      @Override
                      public void write(byte[] b, int o, int n) throws IOException {
                        if ((count += n) > BackupCrypto.LIMIT-49)
                          throw new IOException("Backup exceeds limit");
                        out.write(b, o, n);
                      }

                      @Override
                      public void write(int b) throws IOException {
                        if (++count > BackupCrypto.LIMIT-49)
                          throw new IOException("Backup exceeds limit");
                        out.write(b);
                      }
                    },
                    StandardCharsets.UTF_8))) {
      writer.beginObject().name("version").value(1);
      exportRows(library, writer, "sources", SOURCE);
      exportRows(library, writer, "channels", CHANNEL);
      writer.endObject();
      writer.flush();
    } finally {
      db.endTransaction();
    }
  }

  static void exportRows(Library library, JsonWriter writer, String table, String[] columns)
      throws Exception {
    writer.name(table).beginArray();
    try (Cursor rows =
        library.getReadableDatabase().query(table, columns, null, null, null, null, "id")) {
      while (rows.moveToNext()) {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
        writer.beginObject();
        for (int i = 0; i < columns.length; i++) {
          writer.name(columns[i]);
          if (rows.isNull(i)) writer.nullValue();
          else if (rows.getType(i) == Cursor.FIELD_TYPE_INTEGER) writer.value(rows.getLong(i));
          else {
            String value = rows.getString(i);
            writer.value(
                secret(columns[i]) && !value.isEmpty() ? library.vault.open(value) : value);
          }
        }
        writer.endObject();
      }
    }
    writer.endArray();
  }

  static void importLibrary(Library library, InputStream input, char[] password) throws Exception {
    // Keep the real replacement transactional until the authenticated stream reaches EOF.
    // A bad password, truncated GCM tag, or invalid row rolls everything back.
    SQLiteDatabase db = library.getWritableDatabase();
    db.beginTransaction();
    try (InputStream limited = BackupCrypto.bounded(input);
        InputStream decrypted = BackupCrypto.bounded(BackupCrypto.decrypt(limited, password));
        JsonReader reader =
            new JsonReader(new InputStreamReader(decrypted, StandardCharsets.UTF_8))) {
      reader.setLenient(false);
      reader.beginObject();
      boolean version = false, sources = false, channels = false;
      db.delete("channels", null, null);
      db.delete("sources", null, null);
      db.delete("programs", null, null);
      db.delete("settings", null, null);
      while (reader.hasNext()) {
        String name = reader.nextName();
        if (name.equals("version") && !version) {
          if (reader.nextInt() != 1) throw new IOException("Unsupported backup version");
          version = true;
        } else if (name.equals("sources") && !sources) {
          readRows(library, reader, "sources", SOURCE, 100);
          sources = true;
        } else if (name.equals("channels") && !channels) {
          if (!sources) throw new IOException("Invalid backup order");
          readRows(library, reader, "channels", CHANNEL, 150000);
          channels = true;
        } else throw new IOException("Unexpected backup field");
      }
      reader.endObject();
      if (reader.peek() != android.util.JsonToken.END_DOCUMENT || !version || !sources || !channels)
        throw new IOException("Incomplete backup");
      // Force final tag verification even if a parser implementation buffered the JSON terminator.
      while (decrypted.read() != -1) {}
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  static void readRows(
      Library library, JsonReader reader, String table, String[] columns, int limit)
      throws Exception {
    Set<String> allowed = new HashSet<>(Arrays.asList(columns));
    reader.beginArray();
    int count = 0;
    while (reader.hasNext()) {
      if (++count > limit) throw new IOException("Too many backup rows");
      reader.beginObject();
      ContentValues row = new ContentValues();
      while (reader.hasNext()) {
        String key = reader.nextName();
        if (!allowed.contains(key) || row.containsKey(key))
          throw new IOException("Invalid backup column");
        if (reader.peek() == android.util.JsonToken.NULL) {
          reader.nextNull();
          row.putNull(key);
        } else if (reader.peek() == android.util.JsonToken.NUMBER) {
          long number = reader.nextLong();
          if (number < 0 || number > Long.MAX_VALUE / 2) throw new IOException("Invalid number");
          row.put(key, number);
        } else {
          String value = reader.nextString();
          if (value.length() > 65536) throw new IOException("Backup field too long");
          if (secret(key) && !value.isEmpty()) {
            if (!key.equals("catchup_source") && !M3uParser.isWebUrl(value))
              throw new IOException("Invalid stream URL");
            value = library.vault.seal(value);
          }
          row.put(key, value);
        }
      }
      reader.endObject();
      for (String key : columns)
        if (!row.containsKey(key)) throw new IOException("Missing backup column");
      for (String key : new String[] {"id", "name", "url"})
        if (row.getAsString(key) == null || row.getAsString(key).isEmpty())
          throw new IOException("Missing identity");
      if (!row.getAsString("id").matches("[a-f0-9]{64}") || row.getAsString("name").length() > 512)
        throw new IOException("Invalid identity");
      if (table.equals("channels")) {
        for (String key :
            new String[] {"source", "grp", "tvg", "kind", "catchup_mode", "catchup_source"})
          if (row.getAsString(key) == null) throw new IOException("Null metadata");
        if (row.getAsString("grp").length() > 256 || row.getAsString("tvg").length() > 256)
          throw new IOException("Metadata exceeds limit");
        for (String key :
            new String[] {"favorite", "hidden", "position", "catchup_days", "catalog_owned"})
          if (row.getAsLong(key) == null) throw new IOException("Missing state");
        if (row.getAsLong("favorite") > 1
            || row.getAsLong("hidden") > 1
            || row.getAsLong("catalog_owned") > 1
            || row.getAsLong("catchup_days") > 30) throw new IOException("Invalid state");
        if (row.getAsString("custom_group") != null
            && (row.getAsString("custom_group").trim().isEmpty()
                || row.getAsString("custom_group").length() > 80))
          throw new IOException("Invalid group");
      }
      if (row.getAsString("id") == null
          || row.getAsString("name") == null
          || row.getAsString("url") == null) throw new IOException("Missing identity");
      if (table.equals("channels")) {
        String source = row.getAsString("source");
        if (source == null
            || android.database.DatabaseUtils.longForQuery(
                    library.getReadableDatabase(),
                    "SELECT COUNT(*) FROM sources WHERE id=?",
                    new String[] {source})
                != 1) throw new IOException("Missing source");
        String kind = row.getAsString("kind");
        if (!Arrays.asList("live", "movie", "series").contains(kind))
          throw new IOException("Invalid stream kind");
        row.put("url_key", Library.hash(library.vault.open(row.getAsString("url"))));
        row.put("seen", 1);
      }
      if (table.equals("sources"))
        row.put("url_key", Library.hash(library.vault.open(row.getAsString("url"))));
      library.getWritableDatabase().insertOrThrow(table, null, row);
    }
    reader.endArray();
  }
}
