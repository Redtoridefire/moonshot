package tv.moonshot;

import android.util.JsonReader;
import android.util.JsonToken;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reads category-sized API responses without building a whole JSON object tree. */
final class XtreamCatalog {
  static final class Account {
    String server, user, password, source, name;
  }

  static final class Entry {
    String image = "";
    String id = "", name = "", extension = "mp4", plot = "", genre = "", year = "", guide = "";
    int season, episode;
  }

  static Account account(Library.Source source) throws Exception {
    URI uri = URI.create(source.url);
    if (!uri.getPath().endsWith("/get.php") || uri.getRawQuery() == null)
      throw new IOException("Not an Xtream endpoint");
    Map<String, String> query = new HashMap<>();
    for (String pair : uri.getRawQuery().split("&")) {
      String[] parts = pair.split("=", 2);
      String key = URLDecoder.decode(parts[0], "UTF-8");
      if (query.put(key, parts.length == 2 ? URLDecoder.decode(parts[1], "UTF-8") : "") != null)
        throw new IOException("Ambiguous account query");
    }
    Account account = new Account();
    account.server =
        uri.getScheme()
            + "://"
            + uri.getRawAuthority()
            + uri.getRawPath().substring(0, uri.getRawPath().length() - 8);
    account.user = query.get("username");
    account.password = query.get("password");
    account.source = source.id;
    account.name = source.name;
    if (account.user == null || account.password == null) throw new IOException("Missing account");
    Xtream.endpoint(account.server, account.user, account.password, "player_api.php");
    return account;
  }

  static String api(Account account, String action, String argument, String id) throws Exception {
    String address =
        Xtream.endpoint(account.server, account.user, account.password, "player_api.php")
            + "&action="
            + action;
    if (argument != null) {
      if (!id.matches("[0-9]{1,18}")) throw new IOException("Invalid catalog ID");
      address += "&" + argument + "=" + id;
    }
    return address;
  }

  static List<Entry> fetchList(
      Account account, String action, String argument, String id, boolean http) throws Exception {
    try (Network.Resource response =
            Network.open(api(account, action, argument, id), http, 32L * 1024 * 1024);
        JsonReader reader =
            new JsonReader(new InputStreamReader(response.input, StandardCharsets.UTF_8))) {
      List<Entry> items = new ArrayList<>();
      reader.beginArray();
      while (reader.hasNext()) {
        if (items.size() >= 20000) throw new IOException("Category exceeds 20,000 entries");
        items.add(entry(reader));
      }
      reader.endArray();
      return items;
    }
  }

  static String value(JsonReader reader, int max) throws IOException {
    if (reader.peek() == JsonToken.NULL) {
      reader.nextNull();
      return "";
    }
    String value = reader.nextString();
    if (value.length() > max) throw new IOException("Catalog field exceeds limit");
    return value;
  }

  static Entry entry(JsonReader reader) throws IOException {
    Entry entry = new Entry();
    int priority = 0;
    reader.beginObject();
    while (reader.hasNext()) {
      String key = reader.nextName();
      switch (key) {
        case "category_id":
        case "stream_id":
        case "series_id":
        case "id":
          String identity = value(reader, 18);
          int rank =
              key.equals("stream_id") ? 4 : key.equals("id") ? 3 : key.equals("series_id") ? 2 : 1;
          if (rank > priority) {
            entry.id = identity;
            priority = rank;
          }
          break;
        case "category_name":
        case "name":
        case "title":
          entry.name = value(reader, 512);
          break;
        case "stream_icon":
        case "cover":
        case "movie_image":
          entry.image = value(reader, 4096);
          break;
        case "container_extension":
          entry.extension = value(reader, 8).toLowerCase(Locale.ROOT);
          break;
        case "plot":
        case "description":
          entry.plot = value(reader, 8192);
          break;
        case "genre":
          entry.genre = value(reader, 512);
          break;
        case "releaseDate":
        case "release_date":
        case "year":
          entry.year = value(reader, 64);
          break;
        case "epg_channel_id":
          entry.guide = value(reader, 256);
          break;
        case "info":
          metadata(reader, entry);
          break;
        case "episode_num":
          String number = value(reader, 8);
          try {
            entry.episode = Integer.parseInt(number);
          } catch (NumberFormatException ignored) {
          }
          break;
        default:
          reader.skipValue();
      }
    }
    reader.endObject();
    if (!entry.id.matches("[0-9]{1,18}") || entry.name.isEmpty())
      throw new IOException("Invalid catalog entry");
    return entry;
  }

  static void metadata(JsonReader reader, Entry entry) throws IOException {
    if (reader.peek() != JsonToken.BEGIN_OBJECT) {
      reader.skipValue();
      return;
    }
    reader.beginObject();
    while (reader.hasNext()) {
      switch (reader.nextName()) {
        case "movie_image":
        case "cover":
          entry.image = value(reader, 4096);
          break;
        case "plot":
        case "description":
          entry.plot = value(reader, 8192);
          break;
        case "genre":
          entry.genre = value(reader, 512);
          break;
        case "releasedate":
        case "release_date":
        case "year":
          entry.year = value(reader, 64);
          break;
        default:
          reader.skipValue();
      }
    }
    reader.endObject();
  }

  static Entry movieInfo(Account account, Entry item, boolean http) throws Exception {
    try (Network.Resource response =
            Network.open(api(account, "get_vod_info", "vod_id", item.id), http, 2L * 1024 * 1024);
        JsonReader reader =
            new JsonReader(new InputStreamReader(response.input, StandardCharsets.UTF_8))) {
      reader.beginObject();
      while (reader.hasNext()) {
        if (reader.nextName().equals("info")) metadata(reader, item);
        else reader.skipValue();
      }
      reader.endObject();
      return item;
    }
  }

  static List<Entry> episodes(Account account, String series, boolean http) throws Exception {
    try (Network.Resource response =
            Network.open(
                api(account, "get_series_info", "series_id", series), http, 32L * 1024 * 1024);
        JsonReader reader =
            new JsonReader(new InputStreamReader(response.input, StandardCharsets.UTF_8))) {
      List<Entry> items = new ArrayList<>();
      reader.beginObject();
      while (reader.hasNext()) {
        if (reader.nextName().equals("episodes")) {
          reader.beginObject();
          while (reader.hasNext()) {
            String season = reader.nextName();
            if (!season.matches("[0-9]{1,4}")) throw new IOException("Invalid season");
            reader.beginArray();
            while (reader.hasNext()) {
              if (items.size() >= 20000) throw new IOException("Series exceeds limit");
              Entry entry = entry(reader);
              entry.season = Integer.parseInt(season);
              items.add(entry);
            }
            reader.endArray();
          }
          reader.endObject();
        } else reader.skipValue();
      }
      reader.endObject();
      items.sort(
          Comparator.comparingInt((Entry e) -> e.season)
              .thenComparingInt(e -> e.episode)
              .thenComparing(e -> e.name));
      return items;
    }
  }

  static String path(String value) throws Exception {
    return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
  }

  static String stream(Account account, Entry item, String kind) throws Exception {
    if (!item.id.matches("[0-9]{1,18}")
        || !item.extension.matches("[a-z0-9]{1,8}")
        || !Arrays.asList("live", "movie", "series").contains(kind))
      throw new IOException("Invalid stream metadata");
    return account.server.replaceAll("/+$", "")
        + "/"
        + kind
        + "/"
        + path(account.user)
        + "/"
        + path(account.password)
        + "/"
        + item.id
        + "."
        + (kind.equals("live") ? "ts" : item.extension);
  }
}
