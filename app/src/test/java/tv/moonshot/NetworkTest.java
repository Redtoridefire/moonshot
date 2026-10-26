package tv.moonshot;

import static org.junit.Assert.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.zip.GZIPOutputStream;
import org.junit.*;

public class NetworkTest {
  private ServerSocket server;
  private ExecutorService worker;
  private String base;

  @Before
  public void setup() throws Exception {
    server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
    base = "http://127.0.0.1:" + server.getLocalPort();
    worker = Executors.newSingleThreadExecutor();
    worker.submit(
        () -> {
          while (!Thread.currentThread().isInterrupted())
            try (Socket s = server.accept()) {
              BufferedReader in =
                  new BufferedReader(
                      new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
              String request = in.readLine(), line;
              while ((line = in.readLine()) != null && !line.isEmpty()) {}
              String path = request.split(" ")[1], headers = "", status = "200 OK";
              byte[] body =
                  "#EXTM3U\nhttps://example.invalid/video\n".getBytes(StandardCharsets.UTF_8);
              if (path.equals("/redirect") || path.equals("/loop") || path.equals("/unsafe")) {
                status = "302 Found";
                headers =
                    "Location: "
                        + (path.equals("/redirect")
                            ? "/ok"
                            : path.equals("/loop") ? "/loop" : "file:///etc/passwd")
                        + "\r\n";
                body = new byte[0];
              } else if (path.equals("/denied")) {
                status = "403 Forbidden";
                body = new byte[0];
              } else if (path.equals("/gzip")) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
                  gzip.write("guide fixture".getBytes(StandardCharsets.UTF_8));
                }
                body = out.toByteArray();
                headers = "Content-Encoding: gzip\r\n";
              }
              OutputStream out = s.getOutputStream();
              out.write(
                  ("HTTP/1.1 "
                          + status
                          + "\r\n"
                          + headers
                          + "Content-Length: "
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

  @After
  public void teardown() throws Exception {
    server.close();
    worker.shutdownNow();
    worker.awaitTermination(5, TimeUnit.SECONDS);
  }

  private String fetch(String path) throws Exception {
    try (InputStream in = Network.fetch(base + path, true)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) != -1) out.write(b, 0, n);
      return out.toString("UTF-8");
    }
  }

  @Test
  public void followsRelativeRedirects() throws Exception {
    assertTrue(fetch("/redirect").startsWith("#EXTM3U"));
  }

  @Test
  public void decompressesGzip() throws Exception {
    assertEquals("guide fixture", fetch("/gzip"));
  }

  @Test(expected = IOException.class)
  public void boundsRedirectLoops() throws Exception {
    fetch("/loop");
  }

  @Test(expected = IOException.class)
  public void rejectsRedirectToLocalFile() throws Exception {
    fetch("/unsafe");
  }

  @Test(expected = IOException.class)
  public void httpRequiresOptIn() throws Exception {
    Network.fetch(base + "/ok", false);
  }

  @Test(expected = IOException.class)
  public void rejectsProviderErrors() throws Exception {
    fetch("/denied");
  }

  @Test
  public void errorsNeverEchoPrivateUrl() {
    String privateUrl = "https://example.invalid/private-user/private-key";
    assertFalse(Network.safeError(new IOException(privateUrl)).contains(privateUrl));
  }
}
