package tv.moonshot;

import android.content.*;

public final class RecordingAlarm extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    String id = intent.getStringExtra("id");
    if (id == null) return;
    try (RecordingStore store = new RecordingStore(context)) {
      RecordingStore.Item item = store.get(id);
      if (item == null || !item.state.equals("scheduled")) return;
      long now = System.currentTimeMillis();
      if (now > item.start + 5 * 60000 || now >= item.end) {
        store.state(id, "missed", "", 0);
        return;
      }
      try {
        context.startForegroundService(
            new Intent(context, RecordingService.class).putExtra("id", id));
      } catch (RuntimeException e) {
        store.state(id, "failed", "", 0);
      }
    }
  }
}
