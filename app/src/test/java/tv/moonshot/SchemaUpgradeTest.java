package tv.moonshot;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;
import javax.crypto.KeyGenerator;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SchemaUpgradeTest {
  @Test
  public void everyPreviousSchemaUpgradesWithoutDroppingUserState() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    Vault vault = new Vault(KeyGenerator.getInstance("AES").generateKey());
    for (int version = 1; version <= 5; version++) {
      context.deleteDatabase("moonshot.db");
      SQLiteDatabase db = context.openOrCreateDatabase("moonshot.db", 0, null);
      db.execSQL(
          "CREATE TABLE sources(id TEXT PRIMARY KEY,name TEXT,url TEXT,updated INTEGER"
              + (version >= 5 ? ",url_key TEXT" : "")
              + ")");
      db.execSQL(
          "CREATE TABLE channels(id TEXT PRIMARY KEY,source TEXT,name TEXT,grp TEXT,tvg TEXT,url"
              + " TEXT,kind TEXT,favorite INTEGER DEFAULT 0,position INTEGER DEFAULT 0,seen INTEGER"
              + " DEFAULT 0"
              + (version >= 2 ? ",hidden INTEGER DEFAULT 0,url_key TEXT" : "")
              + (version >= 3 ? ",custom_group TEXT,sort_order INTEGER" : "")
              + (version >= 4
                  ? ",catchup_mode TEXT DEFAULT '',catchup_source TEXT DEFAULT '',catchup_days"
                      + " INTEGER DEFAULT 0"
                  : "")
              + ")");
      db.execSQL(
          "CREATE TABLE programs(channel TEXT,start INTEGER,stop INTEGER,title TEXT,description"
              + " TEXT)");
      if (version >= 4) db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT)");
      db.execSQL(
          "INSERT INTO sources(id,name,url) VALUES(?,?,?)",
          new Object[] {"source", "Test", vault.seal("https://example.invalid/list")});
      db.execSQL(
          "INSERT INTO channels(id,source,name,grp,tvg,url,kind,favorite,position)"
              + " VALUES(?,?,?,?,?,?,?,?,?)",
          new Object[] {
            "channel",
            "source",
            "Test",
            "News",
            "test",
            vault.seal("https://example.invalid/stream"),
            "live",
            1,
            1234
          });
      db.setVersion(version);
      db.close();
      try (Library library = new Library(context, vault)) {
        Channel channel = library.channel("channel");
        assertTrue(channel.favorite);
        assertEquals(1234, channel.position);
        assertEquals(6, library.getReadableDatabase().getVersion());
        library.customGroup(channel.id, "Family");
        assertEquals(1, library.channels(null, null, "", true, "Family").size());
      }
    }
  }
}
