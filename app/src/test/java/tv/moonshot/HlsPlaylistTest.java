package tv.moonshot;

import static org.junit.Assert.*;

import java.io.*;
import org.junit.Test;

public class HlsPlaylistTest {
  HlsPlaylist parse(String s) throws Exception {
    return HlsPlaylist.parse(new StringReader(s), "https://example.invalid/hls/list.m3u8");
  }

  @Test
  public void resolvesRelativeSegmentsAndSequence() throws Exception {
    HlsPlaylist p =
        parse(
            "#EXTM3U\n"
                + "#EXT-X-MEDIA-SEQUENCE:42\n"
                + "#EXT-X-TARGETDURATION:4\n"
                + "#EXTINF:3.5,\n"
                + "a.ts\n"
                + "#EXT-X-ENDLIST\n");
    assertEquals(42, p.segments.get(0).sequence);
    assertEquals("https://example.invalid/hls/a.ts", p.segments.get(0).url);
    assertTrue(p.ended);
  }

  @Test
  public void selectsBoundedBandwidth() throws Exception {
    HlsPlaylist p =
        parse(
            "#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=2000000\n"
                + "small.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=20000000\n"
                + "large.m3u8\n");
    assertTrue(p.variant().endsWith("small.m3u8"));
  }

  @Test
  public void aes128UsesSequenceIv() throws Exception {
    HlsPlaylist p = parse("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:4,\na.ts\n");
    assertEquals("https://example.invalid/hls/key", p.segments.get(0).key.url);
    assertEquals(42, HlsPlaylist.iv(42)[15]);
  }

  @Test(expected = IOException.class)
  public void rejectsDrmEncryption() throws Exception {
    parse("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\"\n#EXTINF:4,\na.ts\n");
  }

  @Test(expected = IOException.class)
  public void rejectsNonWebSegments() throws Exception {
    parse("#EXTM3U\n#EXTINF:4,\nfile:///private\n");
  }

  @Test(expected = IOException.class)
  public void rejectsMissingDurations() throws Exception {
    parse("#EXTM3U\na.ts\n");
  }
}
