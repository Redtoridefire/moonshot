package tv.moonshot;

import android.app.job.*;
import android.content.*;
import java.util.concurrent.*;

/** Periodic, OS-managed work; never assumes a background process remains alive. */
public final class RefreshService extends JobService {
  static final int JOB = 1701;

  private static final class Run {
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    Future<?> task;
    volatile boolean cancelled;
  }

  private Run current;

  static void schedule(Context context, boolean enabled) {
    JobScheduler scheduler = (JobScheduler) context.getSystemService(JOB_SCHEDULER_SERVICE);
    if (!enabled) {
      scheduler.cancel(JOB);
      return;
    }
    scheduler.schedule(
        new JobInfo.Builder(JOB, new ComponentName(context, RefreshService.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setPeriodic(6 * 60 * 60 * 1000L, 60 * 60 * 1000L)
            .build());
  }

  @Override
  public boolean onStartJob(JobParameters params) {
    if (!getSharedPreferences("preferences", 0).getBoolean("automaticRefresh", false)) return false;
    Run run = new Run();
    current = run;
    run.task =
        run.worker.submit(
            () -> {
              boolean failed = false;
              try {
                Library library = ((MoonshotApplication) getApplication()).openLibrary();
                try {
                  boolean http =
                      getSharedPreferences("preferences", 0).getBoolean("allowHttp", false);
                  for (Library.Source source : library.sources()) {
                    if (Thread.currentThread().isInterrupted()) return;
                    try {
                      library.importSource(source.name, source.url, http);
                    } catch (Exception e) {
                      failed = true;
                    }
                  }
                  String guide = library.setting("guideUrl");
                  if (guide != null && !Thread.currentThread().isInterrupted())
                    try {
                      XmlTv.importGuide(library, library.vault.open(guide), http);
                    } catch (Exception e) {
                      failed = true;
                    }
                  getSharedPreferences("refresh", 0)
                      .edit()
                      .putLong(failed ? "lastFailure" : "lastSuccess", System.currentTimeMillis())
                      .apply();
                } finally {
                  library.close();
                }
              } catch (Exception e) {
                failed = true;
              } finally {
                boolean retry = failed;
                new android.os.Handler(getMainLooper())
                    .post(
                        () -> {
                          if (current == run && !run.cancelled) {
                            current = null;
                            jobFinished(params, retry);
                          }
                        });
                run.worker.shutdown();
              }
            });
    return true;
  }

  @Override
  public boolean onStopJob(JobParameters params) {
    Run run = current;
    current = null;
    if (run != null) {
      run.cancelled = true;
      if (run.task != null) run.task.cancel(true);
      run.worker.shutdown();
    }
    return true;
  }
}
