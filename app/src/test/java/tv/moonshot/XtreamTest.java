package tv.moonshot;

import static org.junit.Assert.*;

import org.junit.Test;

public class XtreamTest {
  @Test
  public void encodesCredentialsAndPreservesPort() {
    assertEquals(
        "https://example.invalid:443/get.php?username=a%26b&password=c%3Fd%2Fe&type=m3u_plus&output=ts",
        Xtream.playlist("https://example.invalid:443/", "a&b", "c?d/e"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsCredentialsInServerAddress() {
    Xtream.playlist("https://user:secret@example.invalid", "a", "b");
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsQueryInServer() {
    Xtream.playlist("https://example.invalid/?token=x", "a", "b");
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsNonWebSchemes() {
    Xtream.playlist("file:///etc/passwd", "a", "b");
  }
}
