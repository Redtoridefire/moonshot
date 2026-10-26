package tv.moonshot;

import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.regex.*;

/** Streaming parser: never interprets playlist text as HTML or executes playlist directives. */
public final class M3uParser {
  private static final Pattern ATTR =
      Pattern.compile("([\\w-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s,]+))");

  public interface Sink {
    void accept(Channel channel) throws Exception;
  }

  public static int parse(Reader input, Sink sink) throws Exception {
    BufferedReader reader = new BufferedReader(input);
    String name = null, group = "", tvgId = "";
    Map<String, String> pending = Collections.emptyMap();
    int count = 0;
    String line;
    while ((line = boundedLine(reader)) != null) {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
      line = line.trim().replace("\uFEFF", "");
      if (line.startsWith("#EXTINF:")) {
        int comma = delimiter(line);
        Map<String, String> attrs = attributes(comma < 0 ? line : line.substring(0, comma));
        pending = attrs;
        name =
            comma < 0
                ? attrs.getOrDefault("tvg-name", "Unnamed channel")
                : line.substring(comma + 1).trim();
        if (name.isEmpty()) name = attrs.getOrDefault("tvg-name", "Unnamed channel");
        group = attrs.getOrDefault("group-title", "Ungrouped");
        tvgId = attrs.getOrDefault("tvg-id", "");
      } else if (line.startsWith("#EXTGRP:")) {
        group = line.substring(8).trim();
      } else if (!line.isEmpty() && !line.startsWith("#")) {
        if (!isWebUrl(line)) {
          name = null;
          group = "";
          tvgId = "";
          pending = Collections.emptyMap();
          continue;
        }
        String path = URI.create(line).getPath().toLowerCase(Locale.ROOT);
        String kind =
            path.contains("/movie/") ? "movie" : path.contains("/series/") ? "series" : "live";
        Channel channel =
            new Channel(
                name == null ? "Channel " + (count + 1) : cap(name, 512),
                group.isEmpty() ? "Ungrouped" : cap(group, 256),
                cap(tvgId, 256),
                line,
                kind);
        channel.catchupMode = pending.getOrDefault("catchup", "");
        channel.catchupSource = pending.getOrDefault("catchup-source", "");
        try {
          channel.catchupDays =
              Math.max(
                  0, Math.min(30, Integer.parseInt(pending.getOrDefault("catchup-days", "0"))));
        } catch (NumberFormatException ignored) {
        }
        sink.accept(channel);
        pending = Collections.emptyMap();
        name = null;
        group = "";
        tvgId = "";
        if (++count > 150000) throw new IOException("Playlist exceeds 150,000 entries");
      }
    }
    if (count == 0) throw new IOException("No playable HTTP or HTTPS entries found");
    return count;
  }

  private static String cap(String s, int limit) {
    return s.length() > limit ? s.substring(0, limit) : s;
  }

  static String boundedLine(BufferedReader reader) throws IOException {
    StringBuilder line = new StringBuilder();
    int c;
    while ((c = reader.read()) != -1 && c != '\n' && c != '\r') {
      if (line.length() == 65536) throw new IOException("Playlist line exceeds limit");
      line.append((char) c);
    }
    if (c == '\r') {
      reader.mark(1);
      if (reader.read() != '\n') reader.reset();
    }
    return c == -1 && line.length() == 0 ? null : line.toString();
  }

  static int delimiter(String s) {
    char quote = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (quote != 0) {
        if (c == quote) quote = 0;
      } else if (c == '\"' || c == '\'') quote = c;
      else if (c == ',') return i;
    }
    return -1;
  }

  static Map<String, String> attributes(String s) {
    Map<String, String> result = new HashMap<>();
    Matcher m = ATTR.matcher(s);
    while (m.find())
      result.put(
          m.group(1).toLowerCase(Locale.ROOT),
          m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4));
    return result;
  }

  public static boolean isWebUrl(String s) {
    try {
      URI u = URI.create(s);
      return ("https".equalsIgnoreCase(u.getScheme()) || "http".equalsIgnoreCase(u.getScheme()))
          && u.getHost() != null;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
