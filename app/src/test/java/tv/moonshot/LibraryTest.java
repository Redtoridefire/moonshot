package tv.moonshot;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import javax.crypto.KeyGenerator;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LibraryTest {
  Context context;
  Library library;
  Vault vault;
  ServerSocket server;
  ExecutorService worker;
  String base;
  volatile String playlist;

  String entry(String url) {
    return "#EXTINF:-1 tvg-id=\"news\" group-title=\"News\",News\n" + url + "\n";
  }

  @Before
  public void setup() throws Exception {
    context = ApplicationProvider.getApplicationContext();
    context.deleteDatabase("moonshot.db");
    vault = new Vault(KeyGenerator.getInstance("AES").generateKey());
    library = new Library(context, vault);
    server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
    base = "http://localhost:" + server.getLocalPort();
    worker = Executors.newSingleThreadExecutor();
    worker.submit(
        () -> {
          while (!server.isClosed())
            try (Socket socket = server.accept()) {
              BufferedReader in =
                  new BufferedReader(new InputStreamReader(socket.getInputStream()));
              String line;
              while ((line = in.readLine()) != null && !line.isEmpty()) {}
              byte[] b = playlist.getBytes(StandardCharsets.UTF_8);
              OutputStream out = socket.getOutputStream();
              out.write(
                  ("HTTP/1.1 200 OK\r\nContent-Length: "
                          + b.length
                          + "\r\nConnection: close\r\n\r\n")
                      .getBytes(StandardCharsets.US_ASCII));
              out.write(b);
            } catch (IOException e) {
              if (server.isClosed()) return;
            }
        });
  }

  void refresh() throws Exception {
    library.importSource("Test", base + "/list", true);
  }

  @After
  public void teardown() throws Exception {
    server.close();
    worker.shutdownNow();
    worker.awaitTermination(5, TimeUnit.SECONDS);
    library.close();
  }

  @Test
  public void rotationKeepsState() throws Exception {
    playlist = "#EXTM3U\n" + entry(base + "/one");
    refresh();
    Channel c = library.channels(null, null, "", false).get(0);
    library.favorite(c);
    library.position(c.id, 1234);
    library.hidden(c.id, true);
    playlist = "#EXTM3U\n" + entry(base + "/two");
    refresh();
    assertEquals(1, library.hiddenChannels().size());
    assertTrue(library.channel(c.id).favorite);
    assertEquals(1234, library.channel(c.id).position);
    assertTrue(library.channel(c.id).url.endsWith("/two"));
  }

  @Test
  public void exactUrlWinsOverNewDuplicate() throws Exception {
    playlist = "#EXTM3U\n" + entry(base + "/one");
    refresh();
    Channel c = library.channels(null, null, "", false).get(0);
    library.favorite(c);
    playlist = "#EXTM3U\n" + entry(base + "/two") + entry(base + "/one");
    refresh();
    assertEquals(2, library.channels(null, null, "", false).size());
    assertEquals(c.id, library.channels(null, null, "", true).get(0).id);
    assertTrue(library.channel(c.id).url.endsWith("/one"));
  }

  @Test
  public void invalidRefreshRollsBackHiddenState() throws Exception {
    playlist = "#EXTM3U\n" + entry(base + "/one");
    refresh();
    Channel c = library.channels(null, null, "", false).get(0);
    library.hidden(c.id, true);
    playlist = "<html>Denied</html>";
    try {
      refresh();
      fail();
    } catch (IOException expected) {
    }
    assertEquals(1, library.hiddenChannels().size());
    library.hidden(c.id, false);
    assertEquals(c.id, library.channels(null, null, "", false).get(0).id);
  }

  @Test
  public void versionOneMigrates() throws Exception {
    library.close();
    context.deleteDatabase("moonshot.db");
    SQLiteDatabase db = context.openOrCreateDatabase("moonshot.db", 0, null);
    db.execSQL(
        "CREATE TABLE channels(id TEXT PRIMARY KEY,source TEXT,name TEXT,grp TEXT,tvg TEXT,url"
            + " TEXT,kind TEXT,favorite INTEGER DEFAULT 0,position INTEGER DEFAULT 0,seen INTEGER"
            + " DEFAULT 0)");
    db.execSQL("CREATE TABLE sources(id TEXT PRIMARY KEY,name TEXT,url TEXT,updated INTEGER)");
    db.execSQL(
        "CREATE TABLE programs(channel TEXT,start INTEGER,stop INTEGER,title TEXT,description"
            + " TEXT)");
    db.execSQL(
        "INSERT INTO channels VALUES(?,?,?,?,?,?,?,?,?,?)",
        new Object[] {
          "old", "src", "News", "News", "news", vault.seal(base + "/one"), "live", 1, 1234, 1
        });
    db.setVersion(1);
    db.close();
    library = new Library(context, vault);
    assertEquals(1234, library.channel("old").position);
    assertTrue(library.channel("old").favorite);
    assertEquals(6, library.getReadableDatabase().getVersion());
  }

  @Test
  public void orderingAndCustomGroupsSurviveRefresh() throws Exception {
    playlist =
        "#EXTM3U\n"
            + entry(base + "/one")
            + "#EXTINF:-1 tvg-id=\"two\" group-title=\"Other\",Zulu\n"
            + base
            + "/two\n";
    refresh();
    java.util.List<Channel> before = library.channels(null, null, "", false);
    Channel z = before.get(1);
    library.customGroup(z.id, "Family");
    library.reorder(before, z.id, -1);
    refresh();
    assertEquals(z.id, library.channels(null, null, "", false).get(0).id);
    assertEquals(1, library.channels(null, null, "", false, "Family").size());
    library.deleteCustomGroup("Family");
    assertTrue(library.customGroups().isEmpty());
    library.resetOrder();
    assertEquals("News", library.channels(null, null, "", false).get(0).name);
  }

  @Test
  public void guideRejectsDtdAndKeepsPreviousData() throws Exception {
    String date =
        java.time.format.DateTimeFormatter.ofPattern("uuuuMMddHHmmss xx")
            .withZone(java.time.ZoneOffset.UTC)
            .format(java.time.Instant.now());
    String end =
        java.time.format.DateTimeFormatter.ofPattern("uuuuMMddHHmmss xx")
            .withZone(java.time.ZoneOffset.UTC)
            .format(java.time.Instant.now().plusSeconds(3600));
    playlist =
        "<tv><programme channel=\"news\" start=\""
            + date
            + "\" stop=\""
            + end
            + "\"><title>Current</title></programme></tv>";
    assertEquals(1, XmlTv.importGuide(library, base + "/guide", true));
    assertEquals("Current", library.now("news"));
    playlist = "<!DOCTYPE tv [<!ENTITY attack SYSTEM \"file:///etc/passwd\">]><tv/>";
    try {
      XmlTv.importGuide(library, base + "/guide", true);
      fail("DTD must fail");
    } catch (org.xml.sax.SAXException expected) {
    }
    assertEquals("Current", library.now("news"));
    assertEquals(base + "/guide", vault.open(library.setting("guideUrl")));
  }

  @Test
  public void originalUrlReappearingDoesNotOverwriteRotatedIdentity() throws Exception {
    playlist = "#EXTM3U\n" + entry(base + "/one");
    refresh();
    Channel old = library.channels(null, null, "", false).get(0);
    library.favorite(old);
    playlist = "#EXTM3U\n" + entry(base + "/two");
    refresh();
    playlist = "#EXTM3U\n" + entry(base + "/one") + entry(base + "/two");
    refresh();
    assertEquals(2, library.channels(null, null, "", false).size());
    assertTrue(library.channel(old.id).url.endsWith("/two"));
    assertEquals(old.id, library.channels(null, null, "", true).get(0).id);
  }

  @Test
  public void encryptedBackupRestoresStateAndRejectsTampering() throws Exception {
    playlist = "#EXTM3U\n" + entry(base + "/one");
    refresh();
    Channel c = library.channels(null, null, "", false).get(0);
    library.favorite(c);
    library.customGroup(c.id, "Family");
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    char[] password = "long backup passphrase".toCharArray();
    LibraryBackup.exportLibrary(library, out, password);
    byte[] backup = out.toByteArray();
    library.removeSource(library.sources().get(0).id);
    assertTrue(library.channels(null, null, "", false).isEmpty());
    LibraryBackup.importLibrary(library, new java.io.ByteArrayInputStream(backup), password);
    assertEquals(c.id, library.channels(null, null, "", true, "Family").get(0).id);
    backup[backup.length - 1] ^= 1;
    try {
      LibraryBackup.importLibrary(library, new java.io.ByteArrayInputStream(backup), password);
      fail();
    } catch (Exception expected) {
    }
    assertEquals(c.id, library.channels(null, null, "", true).get(0).id);
  }

  @Test
  public void editingSourceRetainsStateAndAvoidsDuplicates() throws Exception {
    playlist = "#EXTM3U\n" + entry(base + "/one");
    refresh();
    Channel channel = library.channels(null, null, "", false).get(0);
    library.favorite(channel);
    String source = library.sources().get(0).id;
    playlist = "#EXTM3U\n" + entry(base + "/rotated");
    library.replaceSource(source, "Renamed", base + "/new-list", true);
    library.importSource("Renamed", base + "/new-list", true);
    assertEquals(1, library.sources().size());
    assertEquals(source, library.sources().get(0).id);
    assertEquals(channel.id, library.channels(null, null, "", true).get(0).id);
  }

  @Test
  public void providerApiEpisodesRemainAfterPlaylistRefresh() throws Exception {
    String m3u = "#EXTM3U\n" + entry(base + "/one");
    playlist = m3u;
    String address = Xtream.playlist(base, "fixture", "not-a-real-credential");
    library.importSource("Xtream", address, true);
    XtreamCatalog.Account account = XtreamCatalog.account(library.sources().get(0));
    playlist =
        "{\"episodes\":{\"2\":[{\"id\":88,\"title\":\"Second"
            + " season\",\"episode_num\":1,\"container_extension\":\"mkv\"}],\"1\":[{\"id\":77,\"title\":\"Pilot\",\"episode_num\":1}]}}";
    java.util.List<XtreamCatalog.Entry> episodes = XtreamCatalog.episodes(account, "1", true);
    assertEquals(1, episodes.get(0).season);
    assertEquals("77", episodes.get(0).id);
    String id = library.catalogStream(account, episodes.get(0), "series", "Fixture series");
    library.position(id, 1234);
    playlist = m3u;
    library.importSource("Xtream", address, true);
    assertEquals(1234, library.channel(id).position);
  }

  @Test
  public void xmlCommentsDoNotChangeAttributeQuoteState() throws Exception {
    String date =
        java.time.format.DateTimeFormatter.ofPattern("uuuuMMddHHmmss xx")
            .withZone(java.time.ZoneOffset.UTC)
            .format(java.time.Instant.now());
    String end =
        java.time.format.DateTimeFormatter.ofPattern("uuuuMMddHHmmss xx")
            .withZone(java.time.ZoneOffset.UTC)
            .format(java.time.Instant.now().plusSeconds(3600));
    playlist =
        "<tv><!-- Don't treat this as an attribute quote. -->"
            + "<channel id=\"other\"><display-name>Other</display-name></channel>".repeat(2000)
            + "<programme channel=\"news\" start=\""
            + date
            + "\" stop=\""
            + end
            + "\"><title>Current</title></programme></tv>";
    assertEquals(1, XmlTv.importGuide(library, base + "/guide", true));
  }

  @Test
  public void readdingOldPlaylistAddressDoesNotReplaceEditedSource() throws Exception {
    playlist = "#EXTM3U\n" + entry(base + "/one");
    refresh();
    String edited = library.sources().get(0).id;
    library.replaceSource(edited, "Edited", base + "/changed-list", true);
    refresh();
    assertEquals(2, library.sources().size());
    boolean retained = false;
    for (Library.Source source : library.sources())
      if (source.id.equals(edited)) {
        assertEquals(base + "/changed-list", source.url);
        retained = true;
      }
    assertTrue(retained);
  }
}
