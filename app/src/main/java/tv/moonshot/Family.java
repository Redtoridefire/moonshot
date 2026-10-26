package tv.moonshot;

import android.content.*;
import android.os.SystemClock;
import java.util.*;

final class Family {
  private static String unlocked;
  private static long profileUntil, adminUntil;

  static android.content.SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("family", 0);
  }

  static String active(Context c) {
    String id = prefs(c).getString("active", "home");
    return id.matches("home|[a-f0-9]{32}") ? id : "home";
  }

  static String database(Context c) {
    return database(active(c));
  }

  static String database(String id) {
    if (!id.matches("home|[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid profile");
    return id.equals("home") ? "moonshot.db" : "profile-" + id + ".db";
  }

  static boolean hasPin(Context c) {
    return prefs(c).contains("pin");
  }

  static boolean restricted(Context c, String id) {
    return prefs(c).getBoolean("restricted." + id, false);
  }

  static boolean needsUnlock(Context c) {
    return hasPin(c)
        && !restricted(c, active(c))
        && (!active(c).equals(unlocked) || SystemClock.elapsedRealtime() >= profileUntil);
  }

  static boolean admin(Context c) {
    return !hasPin(c) || SystemClock.elapsedRealtime() < adminUntil;
  }

  static synchronized void clearSession() {
    unlocked = null;
    profileUntil = adminUntil = 0;
  }

  static synchronized void grant(Context c, boolean administrator) {
    unlocked = active(c);
    profileUntil = SystemClock.elapsedRealtime() + 5 * 60 * 1000;
    if (administrator) adminUntil = profileUntil;
  }

  static synchronized boolean verify(Context c, char[] pin) throws Exception {
    android.content.SharedPreferences p = prefs(c);
    long now = System.currentTimeMillis();
    long elapsed = SystemClock.elapsedRealtime();
    int boot = android.provider.Settings.Global.getInt(c.getContentResolver(), "boot_count", -1);
    boolean sameBoot =
        p.getInt("lockedBoot", -2) == boot
            && elapsed >= p.getLong("lockSetElapsed", Long.MAX_VALUE);
    if (sameBoot) {
      if (p.getLong("lockedElapsed", 0) > elapsed) return false;
    } else if (p.getLong("lockedUntil", 0) > now) {
      if (p.getLong("lockedUntil", 0) - now > 30000)
        p.edit().putLong("lockedUntil", now + 30000).commit();
      return false;
    }
    boolean valid = PinHash.verify(pin, p.getString("pin", null));
    if (valid)
      p.edit().putInt("attempts", 0).putLong("lockedUntil", 0).putLong("lockedElapsed", 0).commit();
    else {
      int failures = p.getInt("attempts", 0) + 1;
      p.edit()
          .putInt("attempts", failures >= 5 ? 0 : failures)
          .putLong("lockedUntil", failures >= 5 ? now + 30000 : 0)
          .putLong("lockedElapsed", failures >= 5 ? elapsed + 30000 : 0)
          .putLong("lockSetElapsed", elapsed)
          .putInt("lockedBoot", boot)
          .commit();
    }
    return valid;
  }

  static synchronized void setPin(Context c, char[] pin) throws Exception {
    if (!admin(c)) throw new SecurityException("Parental authorization required");
    String record = PinHash.create(pin);
    prefs(c)
        .edit()
        .putString("pin", record)
        .putInt("attempts", 0)
        .putLong("lockedUntil", 0)
        .putLong("lockedElapsed", 0)
        .commit();
    clearSession();
    grant(c, true);
  }

  static List<String> profiles(Context c) {
    List<String> ids = new ArrayList<>(prefs(c).getStringSet("ids", Collections.emptySet()));
    Collections.sort(ids);
    ids.add(0, "home");
    return ids;
  }

  static String name(Context c, String id) {
    return prefs(c).getString("name." + id, id.equals("home") ? "Home" : "Profile");
  }

  static String create(Context c, String name, boolean restricted) {
    if (!admin(c)) throw new SecurityException("Parental authorization required");
    if (name.trim().isEmpty() || name.length() > 40)
      throw new IllegalArgumentException("Invalid profile name");
    String id = UUID.randomUUID().toString().replace("-", "");
    Set<String> ids = new HashSet<>(prefs(c).getStringSet("ids", Collections.emptySet()));
    if (ids.size() >= 7) throw new IllegalStateException("At most eight profiles");
    ids.add(id);
    prefs(c)
        .edit()
        .putStringSet("ids", ids)
        .putString("name." + id, name.trim())
        .putBoolean("restricted." + id, restricted)
        .commit();
    return id;
  }

  static void select(Context c, String id) {
    if (!profiles(c).contains(id)) throw new IllegalArgumentException("Unknown profile");
    prefs(c).edit().putString("active", id).commit();
    clearSession();
  }
}
