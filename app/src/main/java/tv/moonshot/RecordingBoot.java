package tv.moonshot;

import android.app.AlarmManager;
import android.content.*;
import android.os.Build;

public final class RecordingBoot extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) reconcile(context, true);
  }

  static void reconcile(Context context, boolean boot) {
    AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    long now = System.currentTimeMillis();
    try (RecordingStore store = new RecordingStore(context)) {
      for (RecordingStore.Item item : store.items(null)) {
        if (item.state.equals("recording") && RecordingService.activeId == null) {
          String file = item.file;
          try {
            file = Recorder.recover(context, item.id);
          } catch (java.io.IOException ignored) {
          }
          store.state(item.id, "interrupted", file, item.bytes);
        } else if (item.state.equals("scheduled")) {
          if (item.end <= now || (boot && item.start <= now) || now - item.start > 5 * 60000)
            store.state(item.id, "missed", "", 0);
          else if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms())
            try {
              alarms.setExactAndAllowWhileIdle(
                  AlarmManager.RTC_WAKEUP,
                  Math.max(item.start, now + 1000),
                  Recorder.alarm(context, item.id));
            } catch (RuntimeException e) {
              store.state(item.id, "failed", "", 0);
            }
          else store.state(item.id, "failed", "", 0);
        }
      }
    }
  }
}
