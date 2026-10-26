package tv.moonshot;

import static org.junit.Assert.*;

import android.app.job.*;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RefreshScheduleTest {
  @Test
  public void optInSchedulesPersistentNetworkBoundWorkAndOptOutCancels() {
    Context c = ApplicationProvider.getApplicationContext();
    RefreshService.schedule(c, true);
    JobScheduler jobs = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    JobInfo job = jobs.getPendingJob(RefreshService.JOB);
    assertNotNull(job);
    assertTrue(job.isPeriodic());
    assertTrue(job.isPersisted());
    assertEquals(6 * 3600000L, job.getIntervalMillis());
    assertEquals(JobInfo.NETWORK_TYPE_ANY, job.getNetworkType());
    RefreshService.schedule(c, false);
    assertNull(jobs.getPendingJob(RefreshService.JOB));
  }
}
