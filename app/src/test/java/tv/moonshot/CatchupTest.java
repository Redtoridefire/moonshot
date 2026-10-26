package tv.moonshot;

import static org.junit.Assert.*;

import org.junit.Test;

public class CatchupTest {
  long now = 1800000000000L, start = now - 3600000, end = now - 1800000;

  Channel channel() {
    Channel c = new Channel("News", "News", "id", "https://example.com/live", "live");
    c.catchupDays = 7;
    c.catchupMode = "append";
    c.catchupSource = "?utc={utc}&end={utcend}&duration={duration}";
    return c;
  }

  @Test
  public void substitutesExplicitUnixTemplate() {
    String address = Catchup.address(channel(), start, end, now);
    assertEquals(
        "https://example.com/live?utc=" + start / 1000 + "&end=" + end / 1000 + "&duration=1800",
        address);
  }

  @Test
  public void rejectsFutureAndExpiredPrograms() {
    assertNull(Catchup.address(channel(), now, now + 3600000, now));
    assertNull(Catchup.address(channel(), now - 8 * 86400000L, now - 7 * 86400000L, now));
  }

  @Test
  public void rejectsUnknownTokensAndSchemes() {
    Channel c = channel();
    c.catchupSource = "?s={utc}&other={unknown}";
    assertNull(Catchup.address(c, start, end, now));
    c.catchupMode = "default";
    c.catchupSource = "file:///tmp/{utc}";
    assertNull(Catchup.address(c, start, end, now));
  }

  @Test
  public void requiresExplicitProviderCapability() {
    Channel c = channel();
    c.catchupMode = "shift";
    assertNull(Catchup.address(c, start, end, now));
    c.catchupMode = "append";
    c.catchupDays = 0;
    assertNull(Catchup.address(c, start, end, now));
  }
}
