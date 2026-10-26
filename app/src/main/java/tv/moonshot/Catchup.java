package tv.moonshot;

/** Explicit, bounded support for Unix-second M3U archive templates. */
final class Catchup {
  static String address(Channel channel, long start, long end, long now) {
    if (channel.catchupDays <= 0
        || channel.catchupDays > 30
        || start <= 0
        || end <= start
        || end > now
        || end - start > 12 * 3600000L
        || start < now - channel.catchupDays * 86400000L) return null;
    if (!channel.catchupMode.equals("default") && !channel.catchupMode.equals("append"))
      return null;
    String template = channel.catchupSource;
    if (template == null || !template.contains("{utc}")) return null;
    String resolved =
        template
            .replace("{utc}", Long.toString(start / 1000))
            .replace("{utcend}", Long.toString(end / 1000))
            .replace("{duration}", Long.toString((end - start) / 1000))
            .replace("{lutc}", Long.toString(now / 1000));
    if (resolved.contains("{") || resolved.contains("}")) return null;
    String url = channel.catchupMode.equals("append") ? channel.url + resolved : resolved;
    return M3uParser.isWebUrl(url) ? url : null;
  }
}
