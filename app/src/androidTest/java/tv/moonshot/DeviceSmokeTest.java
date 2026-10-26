package tv.moonshot;

import static org.junit.Assert.*;

import android.content.*;
import android.database.Cursor;
import androidx.media3.common.Player;
import androidx.media3.ui.PlayerView;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class DeviceSmokeTest {
  private Context context;
  private Library library;
  private ServerSocket server;
  private ExecutorService serving;
  private String base;
  private final AtomicReference<String> override = new AtomicReference<>();
  private final AtomicBoolean malformed = new AtomicBoolean();
  private byte[] movie;

  @Before
  public void setup() throws Exception {
    assertTrue(
        "Use an isolated Android TV emulator: these fixtures reset the app library, never a"
            + " personal TV",
        android.os.Build.HARDWARE.contains("ranchu")
            || android.os.Build.HARDWARE.contains("goldfish"));
    assertEquals(
        "Explicit data-reset opt-in is required on the dedicated emulator",
        "true",
        InstrumentationRegistry.getArguments().getString("moonshot.resetTestData"));
    context = ApplicationProvider.getApplicationContext();
    context.deleteDatabase("moonshot.db");
    context
        .getSharedPreferences("preferences", 0)
        .edit()
        .clear()
        .putBoolean("allowHttp", true)
        .commit();
    library = new Library(context);
    try (InputStream in =
        InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("smoke.mp4")) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) != -1) bytes.write(b, 0, n);
      movie = bytes.toByteArray();
    }
    server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
    base = "http://127.0.0.1:" + server.getLocalPort();
    serving = Executors.newSingleThreadExecutor();
    serving.submit(
        () -> {
          while (!Thread.currentThread().isInterrupted())
            try (Socket s = server.accept()) {
              BufferedReader r =
                  new BufferedReader(
                      new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
              String request = r.readLine(), line;
              while ((line = r.readLine()) != null && !line.isEmpty()) {}
              boolean video = request != null && request.contains("/movie/");
              boolean guide = request != null && request.contains("/guide");
              String playlist =
                  "#EXTM3U\n#EXTINF:-1 tvg-id=\"test\" group-title=\"News, World\",Sample News\n"
                      + base
                      + "/live/demo.m3u8\n#EXTINF:-1 group-title=\"Cinema\",Sample Movie\n"
                      + base
                      + "/movie/demo.mp4\n";
              String xml =
                  "<?xml version=\"1.0\"?><tv><programme channel=\"test\" start=\"20200101000000"
                      + " +0000\" stop=\"20990101000000 +0000\"><title>Smoke program</title><desc>A"
                      + " fixture program.</desc></programme></tv>";
              if (override.get() != null) playlist = override.get();
              byte[] body =
                  video
                      ? movie
                      : (guide ? xml : malformed.get() ? "<html>Denied</html>" : playlist)
                          .getBytes(StandardCharsets.UTF_8);
              OutputStream out = s.getOutputStream();
              out.write(
                  ("HTTP/1.1 200 OK\r\nContent-Type: "
                          + (video ? "video/mp4" : guide ? "application/xml" : "audio/x-mpegurl")
                          + "\r\nContent-Length: "
                          + body.length
                          + "\r\nConnection: close\r\n\r\n")
                      .getBytes(StandardCharsets.US_ASCII));
              out.write(body);
              out.flush();
            } catch (IOException e) {
              if (server.isClosed()) return;
            }
        });
  }

  @Test
  public void rotatedUrlsPreserveStateAndHiddenChannels() throws Exception {
    library.importSource("Fixture", base + "/playlist", true);
    Channel old = library.channels("live", null, "", false).get(0);
    library.favorite(old);
    library.position(old.id, 12000);
    library.hidden(old.id, true);
    override.set(
        "#EXTM3U\n#EXTINF:-1 tvg-id=\"test\" group-title=\"News, World\",Sample News\n"
            + base
            + "/live/rotated.m3u8\n");
    library.importSource("Fixture", base + "/playlist", true);
    assertEquals(0, library.channels(null, null, "", false).size());
    Channel retained = library.channel(old.id);
    assertTrue(retained.favorite);
    assertEquals(12000, retained.position);
    assertTrue(retained.url.endsWith("rotated.m3u8"));
    assertEquals(1, library.hiddenChannels().size());
    library.hidden(old.id, false);
    assertEquals(old.id, library.channels(null, null, "", true).get(0).id);
  }

  @Test
  public void duplicateMetadataDoesNotStealExactUrlState() throws Exception {
    library.importSource("Fixture", base + "/playlist", true);
    Channel old = library.channels("live", null, "", false).get(0);
    library.favorite(old);
    String entry = "#EXTINF:-1 tvg-id=\"test\" group-title=\"News, World\",Sample News\n";
    override.set(
        "#EXTM3U\n" + entry + base + "/live/new.m3u8\n" + entry + base + "/live/demo.m3u8\n");
    library.importSource("Fixture", base + "/playlist", true);
    assertEquals(2, library.channels("live", null, "", false).size());
    assertEquals(1, library.channels(null, null, "", true).size());
    assertEquals(old.id, library.channels(null, null, "", true).get(0).id);
    assertTrue(library.channel(old.id).url.endsWith("demo.m3u8"));
  }

  @Test
  public void upgradesVersionOneWithoutLosingState() throws Exception {
    library.close();
    context.deleteDatabase("moonshot.db");
    android.database.sqlite.SQLiteDatabase db =
        context.openOrCreateDatabase("moonshot.db", 0, null);
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
          "old",
          "source",
          "News",
          "News",
          "test",
          library.vault.seal(base + "/live/demo.m3u8"),
          "live",
          1,
          12000,
          1
        });
    db.setVersion(1);
    db.close();
    library = new Library(context);
    assertTrue(library.channel("old").favorite);
    assertEquals(12000, library.channel("old").position);
    assertEquals(6, library.getReadableDatabase().getVersion());
    library.hidden("old", true);
    assertEquals(1, library.hiddenChannels().size());
  }

  @After
  public void teardown() throws Exception {
    server.close();
    serving.shutdownNow();
    serving.awaitTermination(5, TimeUnit.SECONDS);
    library.close();
  }

  @Test
  public void vaultRoundTripAndTamperRejection() throws Exception {
    String secret = "https://example.invalid/private-credential";
    String sealed = library.vault.seal(secret);
    assertFalse(sealed.contains(secret));
    assertEquals(secret, library.vault.open(sealed));
    assertNotEquals(sealed, library.vault.seal(secret));
    String[] parts = sealed.split(":");
    byte[] encrypted = android.util.Base64.decode(parts[1], 0);
    encrypted[0] ^= 1;
    try {
      library.vault.open(parts[0] + ":" + android.util.Base64.encodeToString(encrypted, 2));
      fail("Tampering must fail");
    } catch (javax.crypto.AEADBadTagException expected) {
    }
  }

  @Test
  public void importsEncryptsAndRollsBackFailedRefresh() throws Exception {
    assertEquals(2, library.importSource("Fixture", base + "/playlist", true));
    Channel news = library.channels("live", null, "", false).get(0);
    library.favorite(news);
    try (Cursor c = library.getReadableDatabase().rawQuery("SELECT url FROM channels", null)) {
      while (c.moveToNext()) assertFalse(c.getString(0).contains("http"));
    }
    try (Cursor c = library.getReadableDatabase().rawQuery("SELECT url FROM sources", null)) {
      assertTrue(c.moveToFirst());
      assertFalse(c.getString(0).contains(base));
    }
    malformed.set(true);
    try {
      library.importSource("Fixture", base + "/playlist", true);
      fail("Invalid import must fail");
    } catch (IOException expected) {
    }
    assertEquals(2, library.channels(null, null, "", false).size());
    assertEquals(1, library.channels(null, null, "", true).size());
    malformed.set(false);
    library.importSource("Fixture", base + "/playlist", true);
    assertEquals(1, library.channels(null, null, "", true).size());
    assertEquals(1, XmlTv.importGuide(library, base + "/guide", true));
    assertEquals("Smoke program", library.now("test"));
    assertEquals(1, library.guide("test").size());
    library.removeSource(library.sources().get(0).id);
    assertEquals(0, library.channels(null, null, "", false).size());
  }

  @Test
  public void httpBlockedUnlessExplicitlyEnabled() throws Exception {
    try (InputStream ignored = Network.fetch(base + "/playlist", false)) {
      fail("HTTP must be blocked");
    } catch (IOException expected) {
    }
    assertEquals(0, library.sources().size());
  }

  @Test
  public void remoteCanOpenPlaylistDialog() throws Exception {
    UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      assertTrue(device.wait(Until.hasObject(By.text("Your next favorite starts here.")), 30000));
      UiObject2 settings = device.findObject(By.text("Settings"));
      assertNotNull(settings);
      settings.click();
      assertTrue(device.wait(Until.hasObject(By.text("Add M3U playlist")), 10000));
      device.findObject(By.text("Add M3U playlist")).click();
      assertTrue(device.wait(Until.hasObject(By.text("Add your playlist")), 10000));
      device.pressDPadDown();
      device.pressDPadRight();
      device.pressBack();
      assertTrue(device.wait(Until.hasObject(By.text("Your next favorite starts here.")), 10000));
    }
  }

  @Test
  public void browsesFavoritesWithRemote() throws Exception {
    library.importSource("Fixture", base + "/playlist", true);
    Channel news = library.channels("live", null, "", false).get(0);
    library.favorite(news);
    UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      assertTrue(device.wait(Until.hasObject(By.text("Sample News")), 30000));
      device.pressDPadDown();
      device.pressDPadCenter();
      assertTrue(
          device.wait(Until.hasObject(By.text("The channels you always come back to.")), 15000));
      assertNotNull(device.findObject(By.text("Sample News")));
      device.pressDPadRight();
      java.io.File screen =
          new java.io.File(context.getExternalFilesDir(null), "moonshot-library.png");
      assertTrue(device.takeScreenshot(screen));
    }
  }

  @Test
  public void decodesVideoAndSavesResumePosition() throws Exception {
    library.importSource("Fixture", base + "/playlist", true);
    Channel movie = library.channels("movie", null, "", false).get(0);
    Intent intent =
        new Intent(context, PlaybackActivity.class)
            .putExtra("channel", movie.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    AtomicBoolean played = new AtomicBoolean();
    try (ActivityScenario<PlaybackActivity> scenario = ActivityScenario.launch(intent)) {
      long deadline = System.currentTimeMillis() + 60000;
      while (!played.get() && System.currentTimeMillis() < deadline) {
        scenario.onActivity(
            a -> {
              Player p = ((PlayerView) a.findViewById(tv.moonshot.R.id.player_view)).getPlayer();
              if (p != null
                  && p.getPlaybackState() == Player.STATE_READY
                  && p.getCurrentPosition() > 300
                  && p.getVideoSize().width == 320) played.set(true);
            });
        if (!played.get()) Thread.sleep(200);
      }
      assertTrue("Media3 must decode and advance the generated H.264/AAC fixture", played.get());
      scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
    }
    long deadline = System.currentTimeMillis() + 5000;
    while (library.channel(movie.id).position == 0 && System.currentTimeMillis() < deadline)
      Thread.sleep(50);
    assertTrue("VOD resume position persists", library.channel(movie.id).position > 0);
  }
}
