package tv.moonshot;

import static org.junit.Assert.*;

import android.util.JsonReader;
import java.io.StringReader;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class XtreamCatalogTest {
  @Test
  public void streamIdentityOverridesTrailingCategoryId() throws Exception {
    XtreamCatalog.Entry e =
        XtreamCatalog.entry(
            new JsonReader(
                new StringReader(
                    "{\"stream_id\":112,\"name\":\"Film\",\"category_id\":3,\"container_extension\":\"mkv\"}")));
    assertEquals("112", e.id);
    assertEquals("mkv", e.extension);
  }

  @Test
  public void nestedEpisodeMetadataIsRead() throws Exception {
    XtreamCatalog.Entry e =
        XtreamCatalog.entry(
            new JsonReader(
                new StringReader(
                    "{\"id\":77,\"title\":\"Pilot\",\"episode_num\":1,\"info\":{\"plot\":\"A"
                        + " fixture plot\",\"genre\":\"Drama\"}}")));
    assertEquals("77", e.id);
    assertEquals(1, e.episode);
    assertEquals("A fixture plot", e.plot);
  }

  @Test
  public void credentialsAreEncodedSeparatelyForPathsAndQueries() throws Exception {
    String url = Xtream.playlist("https://example.invalid:443/prefix", "a b/c", "d?e&f");
    XtreamCatalog.Account a = XtreamCatalog.account(new Library.Source("source", "Test", url));
    XtreamCatalog.Entry e = new XtreamCatalog.Entry();
    e.id = "11";
    e.name = "Film";
    assertEquals(
        "https://example.invalid:443/prefix/movie/a%20b%2Fc/d%3Fe%26f/11.mp4",
        XtreamCatalog.stream(a, e, "movie"));
  }

  @Test(expected = java.io.IOException.class)
  public void malformedIdsAreRejected() throws Exception {
    XtreamCatalog.entry(
        new JsonReader(new StringReader("{\"id\":\"../escape\",\"name\":\"Bad\"}")));
  }
}
