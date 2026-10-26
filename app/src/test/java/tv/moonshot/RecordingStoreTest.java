package tv.moonshot;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RecordingStoreTest {
  @Test
  public void claimAndCancellationCannotRaceIntoDoubleCapture() {
    Context c = ApplicationProvider.getApplicationContext();
    try (RecordingStore store = new RecordingStore(c)) {
      String first = store.add("home", "channel", "News", 100, 200);
      assertTrue(store.claim(first));
      assertFalse(store.claim(first));
      store.cancelScheduled(first);
      assertEquals("recording", store.get(first).state);
      assertFalse(store.delete(first));
      String second = store.add("home", "channel", "Later", 300, 400);
      store.cancelScheduled(second);
      assertFalse(store.claim(second));
      assertTrue(store.delete(second));
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsUnboundedRecording() {
    try (RecordingStore store = new RecordingStore(ApplicationProvider.getApplicationContext())) {
      store.add("home", "channel", "Too long", 1, 3 * 3600000);
    }
  }

  @Test
  public void rebootRearmsFutureSchedulesAndMarksMissedOnes() {
    Context c = ApplicationProvider.getApplicationContext();
    long now = System.currentTimeMillis();
    try (RecordingStore store = new RecordingStore(c)) {
      String future = store.add("home", "channel", "Later", now + 60000, now + 120000);
      String past = store.add("home", "channel", "Missed", now - 120000, now - 60000);
      RecordingBoot.reconcile(c, true);
      assertEquals("scheduled", store.get(future).state);
      assertEquals("missed", store.get(past).state);
      android.app.AlarmManager alarms =
          (android.app.AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
      assertFalse(org.robolectric.Shadows.shadowOf(alarms).getScheduledAlarms().isEmpty());
    }
  }
}
