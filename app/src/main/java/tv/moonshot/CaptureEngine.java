package tv.moonshot;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Bounded, sequential capture. Never writes provider URLs or AES keys into local playlists. */
final class CaptureEngine {
  static final class Saved {
    String file, map;
    double duration;
    boolean discontinuity;
  }

  final File directory;
  final boolean http;
  final long deadline, quota;
  final BooleanSupplier cancelled;
  final LongSupplier clock;
  final List<Saved> saved = new ArrayList<>();
  boolean sourceEnded;
  volatile InputStream activeInput;

  void abort() {
    InputStream input = activeInput;
    if (input != null)
      new Thread(
              () -> {
                try {
                  input.close();
                } catch (IOException ignored) {
                }
              },
              "moonshot-capture-stop")
          .start();
  }

  long bytes;
  String output = "";

  CaptureEngine(
      File directory,
      boolean http,
      long deadline,
      long quota,
      BooleanSupplier cancelled,
      LongSupplier clock) {
    this.directory = directory;
    this.http = http;
    this.deadline = deadline;
    this.quota = quota;
    this.cancelled = cancelled;
    this.clock = clock;
  }

  void check() throws IOException {
    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
      throw new InterruptedIOException("Capture stopped");
    if (bytes >= quota || directory.getUsableSpace() < 64L * 1024 * 1024)
      throw new IOException("Recording storage limit reached");
  }

  void capture(String address) throws Exception {
    if (!directory.isDirectory() && !directory.mkdirs())
      throw new IOException("Cannot create recording directory");
    try (Network.Resource resource = Network.open(address, http, Long.MAX_VALUE)) {
      activeInput = resource.input;
      PushbackInputStream input = new PushbackInputStream(resource.input, 564);
      byte[] prefix = new byte[564];
      int count = 0;
      while (count < 7) {
        int n = input.read(prefix, count, 7 - count);
        if (n < 0) break;
        count += n;
      }
      input.unread(prefix, 0, count);
      if (count == 7 && new String(prefix, 0, 7, StandardCharsets.US_ASCII).equals("#EXTM3U")) {
        HlsPlaylist playlist =
            HlsPlaylist.parse(
                new InputStreamReader(BackupCrypto.bounded(input), StandardCharsets.UTF_8),
                resource.address);
        String playlistAddress = resource.address;
        for (int depth = 0; !playlist.variants.isEmpty(); depth++) {
          if (depth >= 3) throw new IOException("Nested HLS master exceeds limit");
          playlistAddress = playlist.variant();
          try (Network.Resource variant = Network.open(playlistAddress, http, 2L * 1024 * 1024)) {
            playlistAddress = variant.address;
            playlist =
                HlsPlaylist.parse(
                    new InputStreamReader(variant.input, StandardCharsets.UTF_8), variant.address);
          }
        }
        captureHls(playlist, playlistAddress);
      } else {
        count = 0;
        while (count < 564) {
          int n = input.read(prefix, count, 564 - count);
          if (n < 0) break;
          count += n;
        }
        if (count < 564 || prefix[0] != 0x47 || prefix[188] != 0x47 || prefix[376] != 0x47)
          throw new IOException("Only MPEG-TS or supported HLS can be recorded");
        output = "capture.ts";
        try (FileOutputStream out = new FileOutputStream(new File(directory, output))) {
          write(out, prefix, 0, count);
          byte[] buffer = new byte[32768];
          while (clock.getAsLong() < deadline) {
            check();
            int n = input.read(buffer);
            if (n < 0) {
              sourceEnded = true;
              break;
            }
            write(out, buffer, 0, n);
          }
          out.getFD().sync();
        }
      }
    } finally {
      if (!saved.isEmpty()) manifest(true);
    }
  }

  void write(OutputStream out, byte[] data, int offset, int count) throws IOException {
    check();
    if (bytes + count > quota) throw new IOException("Recording quota reached");
    out.write(data, offset, count);
    bytes += count;
  }

  void copy(String address, File file, HlsPlaylist.Key key, long sequence) throws Exception {
    try (Network.Resource resource = Network.open(address, http, 32L * 1024 * 1024);
        FileOutputStream out = new FileOutputStream(file)) {
      activeInput = resource.input;
      InputStream source = resource.input;
      if (key != null) {
        byte[] value = new byte[16];
        try (Network.Resource secret = Network.open(key.url, http, 17)) {
          activeInput = secret.input;
          new DataInputStream(secret.input).readFully(value);
          if (secret.input.read() != -1) throw new IOException("Invalid HLS key size");
        }
        activeInput = resource.input;
        try {
          Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
          cipher.init(
              Cipher.DECRYPT_MODE,
              new SecretKeySpec(value, "AES"),
              new IvParameterSpec(key.iv == null ? HlsPlaylist.iv(sequence) : key.iv));
          source = new CipherInputStream(source, cipher);
        } finally {
          Arrays.fill(value, (byte) 0);
        }
      }
      try (InputStream decoded = source) {
        byte[] buffer = new byte[32768];
        int n;
        while ((n = decoded.read(buffer)) != -1) {
          if (clock.getAsLong() >= deadline)
            throw new InterruptedIOException("Capture duration reached");
          write(out, buffer, 0, n);
        }
      }
      out.getFD().sync();
    } catch (Exception e) {
      file.delete();
      throw e;
    }
  }

  void captureHls(HlsPlaylist playlist, String address) throws Exception {
    Set<String> seen = new HashSet<>();
    Map<String, String> maps = new HashMap<>();
    while (clock.getAsLong() < deadline) {
      check();
      for (HlsPlaylist.Segment segment : playlist.segments) {
        if (clock.getAsLong() >= deadline) return;
        String identity = segment.sequence + "\n" + segment.url;
        if (seen.contains(identity)) continue;
        String init = null;
        if (segment.map != null) {
          String mapId =
              segment.map
                  + "\n"
                  + (segment.mapKey == null
                      ? ""
                      : segment.mapKey.url + Arrays.toString(segment.mapKey.iv));
          init = maps.get(mapId);
          if (init == null) {
            init = "init" + maps.size() + ".mp4";
            copy(segment.map, new File(directory, init), segment.mapKey, 0);
            maps.put(mapId, init);
          }
        }
        String filename = "segment" + saved.size() + (init == null ? ".ts" : ".m4s");
        copy(segment.url, new File(directory, filename), segment.key, segment.sequence);
        Saved item = new Saved();
        item.file = filename;
        item.map = init;
        item.duration = segment.duration;
        item.discontinuity = segment.discontinuity;
        saved.add(item);
        seen.add(identity);
        output = "capture.m3u8";
        manifest(false);
        if (saved.size() > 10000) throw new IOException("Segment limit reached");
      }
      if (playlist.ended) {
        sourceEnded = true;
        return;
      }
      long remaining = deadline - clock.getAsLong();
      if (remaining <= 0) return;
      Thread.sleep(
          Math.min(remaining, Math.max(500, Math.min(5000, (long) (playlist.target * 500)))));
      try (Network.Resource next = Network.open(address, http, 2L * 1024 * 1024)) {
        activeInput = next.input;
        address = next.address;
        playlist =
            HlsPlaylist.parse(new InputStreamReader(next.input, StandardCharsets.UTF_8), address);
        if (!playlist.variants.isEmpty())
          throw new IOException("Media playlist changed into a master");
      }
    }
  }

  void manifest(boolean ended) throws IOException {
    StringBuilder text = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-MEDIA-SEQUENCE:0\n");
    double target = 1;
    for (Saved item : saved) target = Math.max(target, item.duration);
    text.append("#EXT-X-TARGETDURATION:").append((long) Math.ceil(target)).append('\n');
    String map = null;
    for (Saved item : saved) {
      if (item.discontinuity) text.append("#EXT-X-DISCONTINUITY\n");
      if (item.map != null && !item.map.equals(map)) {
        map = item.map;
        text.append("#EXT-X-MAP:URI=\"").append(map).append("\"\n");
      }
      text.append("#EXTINF:")
          .append(String.format(Locale.ROOT, "%.6f", item.duration))
          .append(",\n")
          .append(item.file)
          .append('\n');
    }
    if (ended) text.append("#EXT-X-ENDLIST\n");
    File temp = new File(directory, "manifest.tmp");
    try (FileOutputStream out = new FileOutputStream(temp)) {
      out.write(text.toString().getBytes(StandardCharsets.UTF_8));
      out.getFD().sync();
    }
    if (!temp.renameTo(new File(directory, "capture.m3u8")))
      throw new IOException("Could not save recording manifest");
  }
}
