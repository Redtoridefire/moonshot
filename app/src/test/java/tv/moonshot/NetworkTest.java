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
              } else if (path.equals("/downgrade")) {
                status = "302 Found";
                headers = "Location: http://127.0.0.1:" + server.getLocalPort() + "/ok\r\n";
                body = new byte[0];
              } else if (path.equals("/page")) {
                body = "<!doctype html><html>Sign in</html>".getBytes(StandardCharsets.UTF_8);
              } else if (path.equals("/json")) {
                body =
                    "{\"status\":false,\"message\":\"Subscription expired\"}"
                        .getBytes(StandardCharsets.UTF_8);
              } else if (path.equals("/raw-gzip")) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
                  gzip.write("#EXTM3U\nhttps://example.invalid/a\n".getBytes(StandardCharsets.UTF_8));
                }
                body = out.toByteArray();
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

  private String playlist(String path) throws Exception {
    try (InputStream in = Network.playlist(Network.fetch(base + path, true))) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) != -1) out.write(b, 0, n);
      return out.toString("UTF-8");
    }
  }

  @Test
  public void unwrapsGzipBodyWithoutContentEncoding() throws Exception {
    assertTrue(playlist("/raw-gzip").startsWith("#EXTM3U"));
  }

  @Test
  public void explainsWebPageInsteadOfPlaylist() throws Exception {
    try {
      playlist("/page");
      fail();
    } catch (ProviderException e) {
      assertEquals(ProviderException.Reason.FORMAT, e.reason);
      assertTrue(e.getMessage().contains("web page"));
    }
  }

  @Test
  public void relaysShortProviderJsonMessage() throws Exception {
    try {
      playlist("/json");
      fail();
    } catch (ProviderException e) {
      assertTrue(e.getMessage().contains("Subscription expired"));
    }
  }

  @Test
  public void recognisesXtreamAndGuideAddresses() {
    assertTrue(
        Network.classify("{\"user_info\":{\"auth\":1}}").getMessage().contains("Xtream"));
    assertTrue(Network.classify("<?xml version=\"1.0\"?><tv>").getMessage().contains("XMLTV"));
    assertNull(Network.classify("\uFEFF#EXTM3U\n"));
    assertNotNull(Network.classify(""));
  }

  @Test
  public void blockedHttpExplainsTheFix() throws Exception {
    try {
      Network.open(base + "/downgrade", false, 1024);
      fail();
    } catch (ProviderException e) {
      assertEquals(ProviderException.Reason.NEEDS_HTTP, e.reason);
    }
  }

  @Test
  public void providerStatusIsExplainedWithoutAddress() throws Exception {
    try {
      fetch("/denied");
      fail();
    } catch (ProviderException e) {
      assertEquals(403, e.status);
      assertFalse(e.getMessage().contains("127.0.0.1"));
      assertEquals(e.getMessage(), Network.safeError(e));
    }
  }

  @Test
  public void dnsFailureSuggestsSecureDns() {
    assertTrue(Network.safeError(new java.net.UnknownHostException("x")).contains("Secure DNS"));
  }

  @Test
  public void sinkholeAddressesAreDetected() throws Exception {
    assertTrue(
        Network.SecureDns.sinkhole(
            java.util.Arrays.asList(InetAddress.getByName("0.0.0.0"), InetAddress.getLoopbackAddress())));
    assertFalse(
        Network.SecureDns.sinkhole(
            java.util.Arrays.asList(InetAddress.getByAddress(new byte[] {93, (byte) 184, (byte) 216, 34}))));
  }
}
