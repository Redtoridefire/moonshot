package tv.moonshot;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import java.io.*;

final class Recorder {
  static File root(Context c) {
    return new File(c.getFilesDir(), "recordings");
  }

  static File directory(Context c, String id) throws IOException {
    if (!id.matches("[a-f0-9-]{36}")) throw new IOException("Invalid recording");
    File root = root(c).getCanonicalFile(), dir = new File(root, id).getCanonicalFile();
    if (!dir.getPath().startsWith(root.getPath() + File.separator))
      throw new IOException("Invalid recording path");
    return dir;
  }

  static String recover(Context context, String id) throws IOException {
    File directory = directory(context, id),
        manifest = new File(directory, "capture.m3u8"),
        ts = new File(directory, "capture.ts");
    if (manifest.isFile() && manifest.length() > 0 && manifest.length() < 2 * 1024 * 1024) {
      byte[] bytes = java.nio.file.Files.readAllBytes(manifest.toPath());
      String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
      if (!text.contains("#EXT-X-ENDLIST"))
        try (FileOutputStream out = new FileOutputStream(manifest, true)) {
          out.write("\n#EXT-X-ENDLIST\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
      return "capture.m3u8";
    }
    return ts.isFile() && ts.length() > 0 ? "capture.ts" : "";
  }

  static long size(File file) {
    if (!file.exists()) return 0;
    if (file.isFile()) return file.length();
    long total = 0;
    File[] children = file.listFiles();
    if (children != null) for (File child : children) total += size(child);
    return total;
  }

  static void delete(File file) throws IOException {
    File[] children = file.listFiles();
    if (children != null) for (File child : children) delete(child);
    if (file.exists() && !file.delete()) throw new IOException("Could not delete recording");
  }

  static Intent intent(Context c, String id) {
    return new Intent(c, RecordingAlarm.class)
        .setData(Uri.parse("moonshot:recording/" + id))
        .putExtra("id", id);
  }

  static PendingIntent alarm(Context c, String id) {
    return PendingIntent.getBroadcast(
        c, 0, intent(c, id), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  static void choose(FamilyActivity activity, Channel channel) {
    activity.requireAdmin(
        () ->
            new AlertDialog.Builder(activity)
                .setTitle("Record " + channel.name)
                .setMessage(
                    "Recording uses an additional provider connection and private device storage,"
                        + " up to 2 GiB total. Only MPEG-TS and supported HLS are captured. Other"
                        + " formats and separate HLS audio renditions may fail.")
                .setItems(
                    new String[] {"15 minutes", "30 minutes", "60 minutes", "120 minutes"},
                    (d, w) ->
                        schedule(
                            activity,
                            channel,
                            System.currentTimeMillis(),
                            System.currentTimeMillis() + new int[] {15, 30, 60, 120}[w] * 60000L))
                .setNegativeButton("Cancel", null)
                .show());
  }

  static void schedule(FamilyActivity activity, Channel channel, long start, long end) {
    activity.requireAdmin(
        () -> {
          long now = System.currentTimeMillis();
          if (end <= now || end - Math.max(now, start) > 2 * 3600000L) {
            new AlertDialog.Builder(activity)
                .setMessage("Choose a program ending within two hours of the recording start.")
                .setPositiveButton("Close", null)
                .show();
            return;
          }
          AlarmManager manager = (AlarmManager) activity.getSystemService(Context.ALARM_SERVICE);
          if (start > now + 10000
              && Build.VERSION.SDK_INT >= 31
              && !manager.canScheduleExactAlarms()) {
            new AlertDialog.Builder(activity)
                .setTitle("Allow scheduled recordings")
                .setMessage(
                    "Enable exact alarm access for Moonshot, then return and schedule again. Device"
                        + " sleep, reboot and provider availability can still interrupt a"
                        + " recording.")
                .setPositiveButton(
                    "Open settings",
                    (d, w) -> {
                      try {
                        activity.startActivity(
                            new Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:" + activity.getPackageName())));
                      } catch (ActivityNotFoundException e) {
                        android.widget.Toast.makeText(
                                activity,
                                "This device does not expose exact alarm settings.",
                                android.widget.Toast.LENGTH_LONG)
                            .show();
                      }
                    })
                .setNegativeButton("Cancel", null)
                .show();
            return;
          }
          try (RecordingStore store = new RecordingStore(activity)) {
            String id =
                store.add(
                    Family.active(activity), channel.id, channel.name, Math.max(start, now), end);
            try {
              if (start <= now + 10000)
                activity.startForegroundService(
                    new Intent(activity, RecordingService.class).putExtra("id", id));
              else
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, start, alarm(activity, id));
              android.widget.Toast.makeText(
                      activity,
                      start <= now + 10000 ? "Recording requested." : "Recording scheduled.",
                      android.widget.Toast.LENGTH_LONG)
                  .show();
            } catch (RuntimeException e) {
              store.state(id, "failed", "", 0);
              android.widget.Toast.makeText(
                      activity,
                      "The device did not permit recording to start or schedule.",
                      android.widget.Toast.LENGTH_LONG)
                  .show();
            }
          }
        });
  }
}
