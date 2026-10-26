package tv.moonshot;

import static org.junit.Assert.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.junit.*;

public class CaptureEngineTest {
  ServerSocket server;
  ExecutorService worker;
  String base;
  Path directory;
  byte[] segment;

  @Before
  public void setup() throws Exception {
    directory = Files.createTempDirectory("moonshot-capture");
    segment = new byte[564];
    segment[0] = segment[188] = segment[376] = 0x47;
    server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
    base = "http://localhost:" + server.getLocalPort();
    worker = Executors.newSingleThreadExecutor();
    worker.submit(
        () -> {
          while (!server.isClosed())
            try (Socket s = server.accept()) {
              BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
              String request = reader.readLine(), line;
              while ((line = reader.readLine()) != null && !line.isEmpty()) {}
              byte[] body;
              if (request.contains("list.m3u8"))
                body =
                    ("#EXTM3U\n"
                            + "#EXT-X-MEDIA-SEQUENCE:0\n"
                            + "#EXT-X-TARGETDURATION:4\n"
                            + "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n"
                            + "#EXTINF:4,\n"
                            + "encrypted.ts\n"
                            + "#EXT-X-ENDLIST\n")
                        .getBytes(StandardCharsets.UTF_8);
              else if (request.contains("key")) {
                body = new byte[16];
                Arrays.fill(body, (byte) 7);
              } else if (request.contains("encrypted.ts")) {
                byte[] key = new byte[16];
                Arrays.fill(key, (byte) 7);
                Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
                c.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(new byte[16]));
                body = c.doFinal(segment);
              } else body = segment;
              OutputStream out = s.getOutputStream();
              out.write(
                  ("HTTP/1.1 200 OK\r\nContent-Length: "
                          + (request.contains("/stall") ? 10000000 : body.length)
                          + "\r\nConnection: close\r\n\r\n")
                      .getBytes(StandardCharsets.US_ASCII));
              out.write(body);
              out.flush();
              if (request.contains("/stall")) Thread.sleep(10000);
            } catch (Exception e) {
              if (server.isClosed()) return;
            }
        });
  }

  @After
  public void teardown() throws Exception {
    server.close();
    worker.shutdownNow();
    worker.awaitTermination(5, TimeUnit.SECONDS);
    try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
              });
    }
  }

  CaptureEngine engine(long quota) {
    return new CaptureEngine(
        directory.toFile(),
        true,
        System.currentTimeMillis() + 10000,
        quota,
        () -> false,
        System::currentTimeMillis);
  }

  @Test
  public void recordsAndDecryptsHlsWithoutCredentialUrlsInManifest() throws Exception {
    CaptureEngine engine = engine(1024 * 1024);
    engine.capture(base + "/list.m3u8");
    assertArrayEquals(segment, Files.readAllBytes(directory.resolve("segment0.ts")));
    String manifest =
        new String(Files.readAllBytes(directory.resolve("capture.m3u8")), StandardCharsets.UTF_8);
    assertTrue(manifest.contains("#EXT-X-ENDLIST"));
    assertFalse(manifest.contains(base));
    assertFalse(manifest.contains("key"));
  }

  @Test
  public void capturesDirectTransportStream() throws Exception {
    CaptureEngine engine = engine(1024 * 1024);
    engine.capture(base + "/direct.ts");
    assertArrayEquals(segment, Files.readAllBytes(directory.resolve("capture.ts")));
  }

  @Test(expected = IOException.class)
  public void enforcesStorageQuota() throws Exception {
    engine(100).capture(base + "/direct.ts");
  }

  @Test
  public void connectedCaptureCanBeStoppedWithoutWaitingForReadTimeout() throws Exception {
    java.util.concurrent.atomic.AtomicBoolean stop =
        new java.util.concurrent.atomic.AtomicBoolean();
    CaptureEngine engine =
        new CaptureEngine(
            directory.toFile(),
            true,
            System.currentTimeMillis() + 10000,
            1024 * 1024,
            stop::get,
            System::currentTimeMillis);
    ExecutorService capture = Executors.newSingleThreadExecutor();
    Future<?> future =
        capture.submit(
            () -> {
              try {
                engine.capture(base + "/stall.ts");
              } catch (Exception ignored) {
              }
            });
    try {
      long timeout = System.currentTimeMillis() + 2000;
      while (!Files.exists(directory.resolve("capture.ts")) && System.currentTimeMillis() < timeout)
        Thread.sleep(10);
      assertTrue(Files.exists(directory.resolve("capture.ts")));
      Thread.sleep(50);
      assertFalse("Fixture must still be blocking before cancellation", future.isDone());
      stop.set(true);
      engine.abort();
      future.get(3, TimeUnit.SECONDS);
    } finally {
      stop.set(true);
      engine.abort();
      capture.shutdownNow();
    }
  }
}
