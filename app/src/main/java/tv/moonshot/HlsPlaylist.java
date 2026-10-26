package tv.moonshot;

import java.io.*;
import java.net.URI;
import java.util.*;

/** A strict recording subset of HLS: TS/fMP4, AES-128, explicit discontinuities. */
final class HlsPlaylist {
  static final class Key {
    String url;
    byte[] iv;
  }

  static final class Segment {
    String url, map;
    Key key, mapKey;
    double duration;
    long sequence;
    boolean discontinuity;
  }

  final List<Segment> segments = new ArrayList<>();
  final List<String> variants = new ArrayList<>();
  final List<Long> bandwidths = new ArrayList<>();
  double target = 4;
  boolean ended;

  static String resolve(String base, String value) throws IOException {
    try {
      String resolved = URI.create(base).resolve(value).toString();
      if (!M3uParser.isWebUrl(resolved)) throw new IOException("Unsupported URI");
      return resolved;
    } catch (IllegalArgumentException e) {
      throw new IOException("Invalid HLS URI");
    }
  }

  static HlsPlaylist parse(Reader source, String base) throws IOException {
    HlsPlaylist result = new HlsPlaylist();
    BufferedReader reader = new BufferedReader(source);
    String line;
    long sequence = 0, bandwidth = 0;
    double duration = -1;
    boolean variant = false, discontinuity = false, header = false;
    String map = null;
    Key key = null, mapKey = null;
    try {
      while ((line = M3uParser.boundedLine(reader)) != null) {
        line = line.trim();
        if (line.isEmpty()) continue;
        if (!header) {
          if (!line.equals("#EXTM3U")) throw new IOException("Invalid HLS header");
          header = true;
          continue;
        }
        if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:"))
          sequence = Long.parseLong(line.substring(22));
        else if (line.startsWith("#EXT-X-TARGETDURATION:")) {
          result.target = Double.parseDouble(line.substring(22));
          if (!Double.isFinite(result.target) || result.target <= 0 || result.target > 120)
            throw new IOException("Invalid target duration");
        } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
          variant = true;
          Map<String, String> attrs = M3uParser.attributes(line.substring(18));
          bandwidth = Long.parseLong(attrs.getOrDefault("bandwidth", "0"));
        } else if (line.startsWith("#EXT-X-MEDIA:")
            && M3uParser.attributes(line).containsKey("uri")
            && !"SUBTITLES".equals(M3uParser.attributes(line).get("type")))
          throw new IOException("Separate HLS renditions are not supported for recording");
        else if (line.startsWith("#EXT-X-BYTERANGE:"))
          throw new IOException("Byte-range HLS recording is not supported");
        else if (line.startsWith("#EXT-X-KEY:")) {
          Map<String, String> attrs = M3uParser.attributes(line.substring(11));
          String method = attrs.get("method");
          if ("NONE".equals(method)) key = null;
          else if ("AES-128".equals(method)
              && attrs.containsKey("uri")
              && "identity".equals(attrs.getOrDefault("keyformat", "identity"))) {
            key = new Key();
            key.url = resolve(base, attrs.get("uri"));
            if (attrs.containsKey("iv")) {
              String hex = attrs.get("iv");
              if (!hex.matches("0[xX][a-fA-F0-9]{32}")) throw new IOException("Invalid AES IV");
              key.iv = new byte[16];
              for (int i = 0; i < 16; i++)
                key.iv[i] = (byte) Integer.parseInt(hex.substring(2 + i * 2, 4 + i * 2), 16);
            }
          } else throw new IOException("Unsupported HLS encryption");
        } else if (line.startsWith("#EXT-X-MAP:")) {
          Map<String, String> attrs = M3uParser.attributes(line.substring(11));
          if (!attrs.containsKey("uri") || attrs.containsKey("byterange"))
            throw new IOException("Unsupported initialization segment");
          map = resolve(base, attrs.get("uri"));
          mapKey = key;
          if (mapKey != null && mapKey.iv == null)
            throw new IOException("Encrypted map requires explicit IV");
        } else if (line.startsWith("#EXTINF:")) {
          String value = line.substring(8).split(",", 2)[0];
          duration = Double.parseDouble(value);
          if (!Double.isFinite(duration) || duration <= 0 || duration > 120)
            throw new IOException("Invalid HLS duration");
        } else if (line.equals("#EXT-X-DISCONTINUITY")) discontinuity = true;
        else if (line.equals("#EXT-X-ENDLIST")) result.ended = true;
        else if (!line.startsWith("#")) {
          if (variant) {
            result.variants.add(resolve(base, line));
            result.bandwidths.add(bandwidth);
            variant = false;
            if (result.variants.size() > 100) throw new IOException("Too many variants");
          } else {
            if (duration < 0 || sequence < 0 || result.segments.size() >= 10000)
              throw new IOException("Invalid segment list");
            Segment segment = new Segment();
            segment.url = resolve(base, line);
            segment.key = key;
            segment.map = map;
            segment.mapKey = mapKey;
            segment.sequence = sequence++;
            segment.duration = duration;
            segment.discontinuity = discontinuity;
            result.segments.add(segment);
            duration = -1;
            discontinuity = false;
          }
        }
      }
    } catch (NumberFormatException e) {
      throw new IOException("Invalid HLS number");
    }
    if (!header || (!result.variants.isEmpty() && !result.segments.isEmpty()))
      throw new IOException("Invalid HLS playlist");
    return result;
  }

  String variant() {
    int selected = 0;
    long score = -1;
    for (int i = 0; i < variants.size(); i++) {
      long value = bandwidths.get(i);
      if (value <= 8_000_000 && value > score) {
        score = value;
        selected = i;
      }
    }
    if (score < 0)
      for (int i = 1; i < variants.size(); i++)
        if (bandwidths.get(i) < bandwidths.get(selected)) selected = i;
    return variants.get(selected);
  }

  static byte[] iv(long sequence) {
    byte[] iv = new byte[16];
    for (int i = 15; i >= 8; i--) {
      iv[i] = (byte) sequence;
      sequence >>>= 8;
    }
    return iv;
  }
}
