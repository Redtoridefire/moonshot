package tv.moonshot;

import static org.junit.Assert.*;

import org.junit.Test;

public class XmlTvTest {
  @Test
  public void timezoneOffsetsReferToSameInstant() {
    assertEquals(XmlTv.time("20260101120000 +0000"), XmlTv.time("20260101070000 -0500"));
  }

  @Test
  public void missingOffsetUsesUtc() {
    assertEquals(XmlTv.time("20260101120000 +0000"), XmlTv.time("20260101120000"));
  }

  @Test
  public void malformedTimesReturnZero() {
    assertEquals(0, XmlTv.time("bad"));
    assertEquals(0, XmlTv.time(null));
  }

  @Test
  public void rejectsImpossibleDates() {
    assertEquals(0, XmlTv.time("20260230120000 +0000"));
    assertEquals(0, XmlTv.time("20260101250000 +0000"));
  }
}
