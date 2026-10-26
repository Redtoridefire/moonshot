package tv.moonshot;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.util.concurrent.*;

public final class RecordingService extends Service {
  static volatile String activeId;
  static RecordingService owner;
  volatile boolean began, ended;
  volatile boolean cancelled;
  volatile CaptureEngine currentCapture;
  ConnectionBudget.Lease lease;
  ExecutorService worker;
  Future<?> task;
  PowerManager.WakeLock wake;

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    String id = intent == null ? null : intent.getStringExtra("id");
    if (intent != null && "stop".equals(intent.getAction())) {
      if (id != null && id.equals(activeId)) {
        cancelled = true;
        if (currentCapture != null) currentCapture.abort();
        if (task != null) task.cancel(true);
      } else if (activeId == null) stopSelf();
      return START_NOT_STICKY;
    }
    if (id == null) {
      if (activeId == null) stopSelf();
      return START_NOT_STICKY;
    }
    if (activeId != null) {
      if (!activeId.equals(id))
        try (RecordingStore store = new RecordingStore(this)) {
          store.state(id, "busy", "", 0);
        }
      if (owner != this) stopSelf();
      return START_NOT_STICKY;
    }
    RecordingStore.Item item;
    try (RecordingStore store = new RecordingStore(this)) {
      item = store.get(id);
    }
    if (item != null && item.state.equals("scheduled") && item.end <= System.currentTimeMillis())
      try (RecordingStore store = new RecordingStore(this)) {
        store.state(id, "missed", "", 0);
      }
    if (item == null
        || !item.state.equals("scheduled")
        || item.end <= System.currentTimeMillis()
        || item.start > System.currentTimeMillis() + 10000) {
      stopSelf();
      return START_NOT_STICKY;
    }
    try (RecordingStore store = new RecordingStore(this)) {
      if (!store.claim(id)) {
        stopSelf();
        return START_NOT_STICKY;
      }
    }
    lease =
        ConnectionBudget.acquire(
            getSharedPreferences("preferences", 0).getInt("connections", 1), 1);
    if (lease == null) {
      try (RecordingStore store = new RecordingStore(this)) {
        store.state(id, "busy", "", 0);
      }
      stopSelf();
      return START_NOT_STICKY;
    }
    activeId = id;
    owner = this;
    cancelled = false;
    NotificationManager notifications =
        (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    notifications.createNotificationChannel(
        new NotificationChannel("recording", "Recordings", NotificationManager.IMPORTANCE_LOW));
    PendingIntent stop =
        PendingIntent.getService(
            this,
            0,
            new Intent(this, RecordingService.class).setAction("stop").putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification notification =
        new Notification.Builder(this, "recording")
            .setSmallIcon(R.drawable.ic_moonshot)
            .setContentTitle("Moonshot is recording")
            .setContentText("Open Recordings to manage storage and playback.")
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
            .build();
    try {
      startForeground(1702, notification);
    } catch (RuntimeException e) {
      if (lease != null) lease.close();
      activeId = null;
      owner = null;
      try (RecordingStore store = new RecordingStore(this)) {
        store.state(id, "failed", "", 0);
      }
      stopSelf();
      return START_NOT_STICKY;
    }
    long duration = Math.min(2 * 3600000L, item.end - System.currentTimeMillis());
    if (duration <= 0) {
      if (lease != null) lease.close();
      activeId = null;
      owner = null;
      try (RecordingStore store = new RecordingStore(this)) {
        store.state(id, "missed", "", 0);
      }
      stopForeground(true);
      stopSelf();
      return START_NOT_STICKY;
    }
    wake =
        ((PowerManager) getSystemService(POWER_SERVICE))
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "moonshot:recording");
    wake.setReferenceCounted(false);
    wake.acquire(duration + 60000);
    worker = Executors.newSingleThreadExecutor();
    task =
        worker.submit(
            () -> {
              began = true;
              CaptureEngine engine = null;
              boolean success = false;
              try (RecordingStore store = new RecordingStore(this);
                  Library library =
                      ((MoonshotApplication) getApplication()).openLibrary(item.profile)) {
                Channel channel = library.channel(item.channel);
                if (!channel.kind.equals("live"))
                  throw new IOException("Only live channels are recorded");
                File directory = Recorder.directory(this, id);
                long remaining = 2L * 1024 * 1024 * 1024 - Recorder.size(Recorder.root(this));
                if (remaining < 64L * 1024 * 1024) throw new IOException("Recording quota reached");
                engine =
                    new CaptureEngine(
                        directory,
                        getSharedPreferences("preferences", 0).getBoolean("allowHttp", false),
                        SystemClock.elapsedRealtime() + duration,
                        remaining,
                        () -> cancelled,
                        SystemClock::elapsedRealtime);
                currentCapture = engine;
                store.state(id, "recording", "", 0);
                engine.capture(channel.url);
                success = !cancelled && !engine.output.isEmpty() && !engine.sourceEnded;
              } catch (Exception ignored) {
              } finally {
                if (engine != null
                    && !cancelled
                    && SystemClock.elapsedRealtime() >= engine.deadline
                    && !engine.output.isEmpty()) success = true;
                String file = engine == null ? "" : engine.output;
                long bytes = engine == null ? 0 : engine.bytes;
                try (RecordingStore store = new RecordingStore(this)) {
                  store.state(
                      id,
                      success ? "completed" : file.isEmpty() ? "failed" : "partial",
                      file,
                      bytes);
                }
                ended = true;
                if (lease != null) lease.close();
                if (wake != null && wake.isHeld()) wake.release();
                worker.shutdown();
                new Handler(getMainLooper())
                    .post(
                        () -> {
                          if (id.equals(activeId)) {
                            activeId = null;
                            owner = null;
                            stopForeground(true);
                            stopSelf();
                          }
                        });
              }
            });
    return START_NOT_STICKY;
  }

  @Override
  public void onTimeout(int id, int type) {
    cancelled = true;
    if (currentCapture != null) currentCapture.abort();
    if (task != null) task.cancel(true);
    stopForeground(true);
    stopSelf();
  }

  @Override
  public void onDestroy() {
    cancelled = true;
    if (currentCapture != null) currentCapture.abort();
    if (task != null) task.cancel(true);
    if (worker != null) worker.shutdown();
    if (lease != null && (!began || ended)) lease.close();
    if (!began && owner == this) {
      activeId = null;
      owner = null;
    }
    if (wake != null && wake.isHeld()) wake.release();
    super.onDestroy();
  }
}
