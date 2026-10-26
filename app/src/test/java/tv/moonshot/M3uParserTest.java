package tv.moonshot;

import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import org.junit.Test;

public class M3uParserTest {
  private List<Channel> parse(String s) throws Exception {
    List<Channel> out = new ArrayList<>();
    M3uParser.parse(new StringReader(s), out::add);
    return out;
  }

  @Test
  public void quotedCommasAndMetadata() throws Exception {
    Channel c =
        parse(
                "\uFEFF#EXTM3U\n"
                    + "#EXTINF:-1 tvg-id=\"news.one\" group-title=\"News, World\",News, One\n"
                    + "https://example.com/live/1.m3u8\n")
            .get(0);
    assertEquals("News, One", c.name);
    assertEquals("News, World", c.group);
    assertEquals("news.one", c.tvgId);
    assertEquals("live", c.kind);
  }

  @Test
  public void handlesCrLfSingleQuotesAndExtgrp() throws Exception {
    Channel c =
        parse(
                "#EXTM3U\r\n"
                    + "#EXTINF:-1 tvg-name='My Channel',\r\n"
                    + "#EXTGRP:Sports\r\n"
                    + "https://example.com/stream\r\n")
            .get(0);
    assertEquals("My Channel", c.name);
    assertEquals("Sports", c.group);
  }

  @Test
  public void vodAndSeriesClassification() throws Exception {
    List<Channel> c =
        parse(
            "#EXTINF:-1,Film\n"
                + "https://example.com/movie/u/p/1.mp4\n"
                + "#EXTINF:-1,Episode\n"
                + "https://example.com/series/u/p/2.mkv\n");
    assertEquals("movie", c.get(0).kind);
    assertEquals("series", c.get(1).kind);
  }

  @Test
  public void metadataDoesNotLeakToNextEntry() throws Exception {
    List<Channel> c =
        parse(
            "#EXTINF:-1 group-title=\"News\" tvg-id=\"a\",A\n"
                + "https://example.com/a\n"
                + "https://example.com/b\n");
    assertEquals("Ungrouped", c.get(1).group);
    assertEquals("", c.get(1).tvgId);
  }

  @Test
  public void rejectsNonWebSchemesAndMalformedAddresses() throws Exception {
    List<Channel> c =
        parse("#EXTINF:-1,Unsafe\nfile:///etc/passwd\n#EXTINF:-1,Safe\nhttps://example.com/a\n");
    assertEquals(1, c.size());
    assertEquals("Safe", c.get(0).name);
    assertFalse(M3uParser.isWebUrl("javascript:alert(1)"));
    assertFalse(M3uParser.isWebUrl("https://"));
  }

  @Test(expected = IOException.class)
  public void htmlIsNotAPlaylist() throws Exception {
    parse("<html>Unauthorized</html>");
  }

  @Test(expected = IOException.class)
  public void rejectsOversizedLines() throws Exception {
    parse("x".repeat(65537));
  }

  @Test
  public void acceptsBareUrlsAndUppercaseScheme() throws Exception {
    assertEquals(1, parse("HTTPS://example.com/channel").size());
  }

  @Test
  public void boundsMemoryBeforeConsumingOversizedLine() throws Exception {
    final int[] consumed = {0};
    Reader oversized =
        new Reader() {
          public int read(char[] b, int offset, int length) {
            int n = Math.min(length, 1_000_000 - consumed[0]);
            if (n == 0) return -1;
            java.util.Arrays.fill(b, offset, offset + n, 'x');
            consumed[0] += n;
            return n;
          }

          public void close() {}
        };
    try {
      M3uParser.parse(oversized, c -> {});
      fail("Oversized line must fail");
    } catch (IOException expected) {
    }
    assertTrue("Parser must stop reading before allocating the entire line", consumed[0] < 80000);
  }

  @Test
  public void supportsClassicCarriageReturns() throws Exception {
    assertEquals(2, parse("#EXTM3U\rhttps://example.com/one\rhttps://example.com/two\r").size());
  }
}
