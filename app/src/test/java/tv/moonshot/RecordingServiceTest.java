package tv.moonshot;

import static org.junit.Assert.*;

import android.content.*;
import android.os.Looper;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = ScreenTest.TestApplication.class)
public class RecordingServiceTest {
  @Test
  public void foregroundCaptureProducesPartialFileAndReleasesBudget() throws Exception {
    ScreenTest.TestApplication app =
        (ScreenTest.TestApplication) RuntimeEnvironment.getApplication();
    app.getSharedPreferences("preferences", 0)
        .edit()
        .putBoolean("allowHttp", true)
        .putInt("connections", 1)
        .commit();
    ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
    ExecutorService serving = Executors.newSingleThreadExecutor();
    String base = "http://localhost:" + server.getLocalPort();
    serving.submit(
        () -> {
          while (!server.isClosed())
            try (Socket socket = server.accept()) {
              BufferedReader reader =
                  new BufferedReader(new InputStreamReader(socket.getInputStream()));
              String request = reader.readLine(), line;
              while ((line = reader.readLine()) != null && !line.isEmpty()) {}
              byte[] body;
              if (request.contains("/list"))
                body =
                    ("#EXTM3U\n#EXTINF:-1,Fixture\n" + base + "/direct.ts\n")
                        .getBytes(StandardCharsets.UTF_8);
              else {
                body = new byte[564];
                body[0] = body[188] = body[376] = 0x47;
              }
              OutputStream out = socket.getOutputStream();
              out.write(
                  ("HTTP/1.1 200 OK\r\nContent-Length: "
                          + body.length
                          + "\r\nConnection: close\r\n\r\n")
                      .getBytes(StandardCharsets.US_ASCII));
              out.write(body);
            } catch (Exception e) {
              if (server.isClosed()) return;
            }
        });
    ServiceController<RecordingService> controller =
        Robolectric.buildService(RecordingService.class).create();
    try (Library library = app.openLibrary();
        RecordingStore store = new RecordingStore(app)) {
      library.importSource("Test", base + "/list", true);
      String channel = library.channels("live", null, "", false).get(0).id;
      long now = System.currentTimeMillis();
      String id = store.add("home", channel, "Fixture", now, now + 60000);
      RecordingService service = controller.get();
      service.onStartCommand(new Intent(app, RecordingService.class).putExtra("id", id), 0, 1);
      assertNotNull(service.task);
      service.task.get(10, TimeUnit.SECONDS);
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      RecordingStore.Item item = store.get(id);
      assertEquals("partial", item.state);
      assertEquals("capture.ts", item.file);
      assertEquals(564, new File(Recorder.directory(app, id), item.file).length());
      assertNull(RecordingService.activeId);
      try (ConnectionBudget.Lease lease = ConnectionBudget.acquire(1, 1)) {
        assertNotNull(lease);
      }
    } finally {
      controller.destroy();
      server.close();
      serving.shutdownNow();
      serving.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
