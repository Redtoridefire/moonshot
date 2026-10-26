package tv.moonshot;

import static org.junit.Assert.*;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.*;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import java.io.*;
import okhttp3.mockwebserver.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class PlaybackSourceTest {
  @Test
  public void media3BridgeUsesGuardedCancellableClient() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
      String address = "http://127.0.0.1:" + server.getPort() + "/fixture.ts";
      DataSource blocked =
          new PlaybackActivity.GuardedSource(
              new OkHttpDataSource.Factory(Network.playbackClient()).createDataSource(), false);
      try {
        blocked.open(new DataSpec(Uri.parse(address)));
        fail("HTTP must be opt-in for playback too");
      } catch (IOException expected) {
      } finally {
        blocked.close();
      }
      assertEquals(0, server.getRequestCount());
      server.enqueue(new MockResponse().setBody("fixture"));
      DataSource allowed =
          new PlaybackActivity.GuardedSource(
              new OkHttpDataSource.Factory(Network.playbackClient()).createDataSource(), true);
      try {
        assertEquals(7, allowed.open(new DataSpec(Uri.parse(address))));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[32];
        int n;
        while ((n = allowed.read(buffer, 0, buffer.length)) != C.RESULT_END_OF_INPUT)
          out.write(buffer, 0, n);
        assertEquals("fixture", out.toString("UTF-8"));
      } finally {
        allowed.close();
      }
    }
  }
}
