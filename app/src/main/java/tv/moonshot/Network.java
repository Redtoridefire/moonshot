package tv.moonshot;

import java.io.*;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import okhttp3.*;

/**
 * Verified TLS, explicit redirect policy and cancellable response ownership. No request logging.
 */
final class Network {
  static final String DEFAULT_AGENT = "Moonshot/0.5 (Android TV)";

  /** Player identities a person may choose in Settings. Index 0 is the default. */
  static final String[] AGENT_LABELS = {
    "Moonshot (default)", "VLC media player", "ExoPlayer / Media3", "Web browser"
  };

  static final String[] AGENTS = {
    DEFAULT_AGENT,
    "VLC/3.0.21 LibVLC/3.0.21",
    "ExoPlayerLib/2.19.1",
    "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko)"
        + " Chrome/124.0 Safari/537.36"
  };

  /** Secure DNS: 0 = automatic fallback when the network's resolver fails, 1 = always, 2 = off. */
  static final int DNS_AUTO = 0, DNS_ALWAYS = 1, DNS_OFF = 2;

  private static volatile String agent = DEFAULT_AGENT;
  private static volatile int dnsMode = DNS_AUTO;

  static void configure(String userAgent, int secureDns) {
    agent = userAgent == null || userAgent.trim().isEmpty() ? DEFAULT_AGENT : userAgent.trim();
    dnsMode = secureDns < DNS_AUTO || secureDns > DNS_OFF ? DNS_AUTO : secureDns;
    SecureDns.CACHE.clear();
  }

  static void configure(android.content.Context c) {
    android.content.SharedPreferences p = c.getSharedPreferences("preferences", 0);
    configure(p.getString("userAgent", DEFAULT_AGENT), p.getInt("secureDns", DNS_AUTO));
  }

  static String userAgent() {
    return agent;
  }

  static int dnsMode() {
    return dnsMode;
  }

  private static final OkHttpClient BOOTSTRAP =
      new OkHttpClient.Builder()
          .followRedirects(false)
          .followSslRedirects(false)
          .connectTimeout(8, TimeUnit.SECONDS)
          .readTimeout(8, TimeUnit.SECONDS)
          .build();

  private static final OkHttpClient CLIENT =
      BOOTSTRAP
          .newBuilder()
          .dns(new SecureDns())
          .retryOnConnectionFailure(true)
          .connectTimeout(15, TimeUnit.SECONDS)
          .readTimeout(30, TimeUnit.SECONDS)
          .build();

  static OkHttpClient playbackClient() {
    return playbackClient(false);
  }

  /**
   * Playback follows provider redirects, including between HTTP and HTTPS. Many IPTV services
   * answer a stream request with a redirect to a different CDN host or scheme; refusing those made
   * otherwise valid playlists unplayable. A redirect to plain HTTP is still refused unless the
   * person has allowed HTTP.
   */
  static OkHttpClient playbackClient(boolean allowHttp) {
    return CLIENT
        .newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .addNetworkInterceptor(
            chain -> {
              if (!allowHttp && !chain.request().url().isHttps())
                throw new ProviderException(
                    ProviderException.Reason.NEEDS_HTTP,
                    "The stream redirected to an unencrypted HTTP address.");
              return chain.proceed(chain.request());
            })
        .build();
  }

  static final class Resource implements AutoCloseable {
    final InputStream input;
    final String address;
    final String contentType;

    Resource(InputStream input, String address, String contentType) {
      this.input = input;
      this.address = address;
      this.contentType = contentType;
    }

    @Override
    public void close() throws IOException {
      input.close();
    }
  }

  /** Observes each request hop without exposing it to logs; used by the link tester. */
  interface Trace {
    void hop(String address, int status);
  }

  static InputStream fetch(String address, boolean allowHttp) throws IOException {
    return open(address, allowHttp, 256L * 1024 * 1024).input;
  }

  static Resource open(String address, boolean allowHttp, long limit) throws IOException {
    return open(address, allowHttp, limit, agent, null);
  }

  static Resource open(String address, boolean allowHttp, long limit, String userAgent, Trace trace)
      throws IOException {
    String next = address;
    for (int redirects = 0; redirects < 8; redirects++) {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
      if (!M3uParser.isWebUrl(next))
        throw new ProviderException(
            ProviderException.Reason.OTHER,
            redirects == 0
                ? "Enter a complete address starting with https:// or http://."
                : "The provider redirected to an unsupported address.");
      if (!allowHttp && !"https".equalsIgnoreCase(URI.create(next).getScheme()))
        throw new ProviderException(
            ProviderException.Reason.NEEDS_HTTP,
            redirects == 0
                ? "This address uses unencrypted HTTP, which is turned off."
                : "Your provider redirected the playlist to an unencrypted HTTP address, which is"
                    + " turned off.");
      Request request;
      try {
        request =
            new Request.Builder()
                .url(next)
                .header("User-Agent", userAgent)
                .header("Accept", "*/*")
                .build();
      } catch (IllegalArgumentException e) {
        throw new ProviderException(
            ProviderException.Reason.OTHER, "The address is not a valid web address.");
      }
      Call call = CLIENT.newCall(request);
      Response response;
      try {
        response = call.execute();
      } catch (IOException e) {
        call.cancel();
        throw e;
      }
      int status = response.code();
      if (trace != null) trace.hop(next, status);
      if (status >= 300 && status < 400) {
        String location = response.header("Location");
        String base = response.request().url().toString();
        call.cancel();
        response.close();
        if (location == null)
          throw new ProviderException(
              ProviderException.Reason.OTHER, "The provider sent an invalid redirect.");
        try {
          next = URI.create(base).resolve(location.trim()).toString();
        } catch (IllegalArgumentException e) {
          throw new ProviderException(
              ProviderException.Reason.OTHER, "The provider sent an invalid redirect.");
        }
        continue;
      }
      if (status < 200 || status >= 300 || response.body() == null) {
        call.cancel();
        response.close();
        throw ProviderException.status(status);
      }
      InputStream raw = response.body().byteStream();
      InputStream bounded =
          new FilterInputStream(raw) {
            long bytes;

            void check(int count) throws IOException {
              if (count > 0) bytes += count;
              if (bytes > limit) throw new IOException("Download exceeds configured size limit");
              if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            }

            @Override
            public int read() throws IOException {
              int n = in.read();
              check(n < 0 ? 0 : 1);
              return n;
            }

            @Override
            public int read(byte[] b, int offset, int length) throws IOException {
              int n = in.read(b, offset, length);
              check(n);
              return n;
            }

            @Override
            public void close() throws IOException {
              call.cancel();
              response.close();
            }
          };
      return new Resource(
          bounded, response.request().url().toString(), response.header("Content-Type"));
    }
    throw new ProviderException(
        ProviderException.Reason.OTHER, "The provider redirected too many times.");
  }

  /**
   * Returns a stream positioned at playlist text, transparently unwrapping a gzip-compressed body
   * that was sent without a Content-Encoding header. Fails early, with a specific explanation, when
   * the provider returned a web page, JSON, an XMLTV guide or nothing at all.
   */
  static InputStream playlist(InputStream body) throws IOException {
    BufferedInputStream in = new BufferedInputStream(body, 16384);
    in.mark(16384);
    byte[] head = new byte[2];
    int n = readFully(in, head);
    in.reset();
    InputStream text = in;
    if (n == 2 && (head[0] & 0xff) == 0x1f && (head[1] & 0xff) == 0x8b) {
      text = new BufferedInputStream(new GZIPInputStream(in, 16384), 16384);
      text.mark(16384);
    }
    byte[] sample = new byte[4096];
    int length = readFully(text, sample);
    text.reset();
    String start = new String(sample, 0, Math.max(0, length), StandardCharsets.UTF_8);
    ProviderException problem = classify(start);
    if (problem != null) {
      text.close();
      throw problem;
    }
    return text;
  }

  /** Null when the sample looks like an M3U playlist; otherwise a specific explanation. */
  static ProviderException classify(String start) {
    String trimmed = start.replace("﻿", "").trim();
    String lower = trimmed.toLowerCase(Locale.ROOT);
    if (trimmed.isEmpty())
      return new ProviderException(
          ProviderException.Reason.FORMAT,
          "The provider returned an empty playlist. The link may be expired, the subscription"
              + " inactive, or the provider may only serve the playlist to approved apps.");
    if (lower.startsWith("#extm3u") || lower.startsWith("#extinf") || lower.startsWith("http"))
      return null;
    if (lower.startsWith("{") || lower.startsWith("[")) {
      if (lower.contains("\"user_info\"") || lower.contains("\"server_info\""))
        return new ProviderException(
            ProviderException.Reason.FORMAT,
            "This is an Xtream account address, not an M3U playlist. Use Settings → Add Xtream"
                + " login with the server, username and password instead.");
      String said = jsonMessage(trimmed);
      return new ProviderException(
          ProviderException.Reason.FORMAT,
          "The provider returned data (JSON) instead of an M3U playlist"
              + (said == null ? "." : ": “" + said + "”.")
              + " The link may require an app sign-in, or the account may not include M3U"
              + " access.");
    }
    if (lower.startsWith("<")) {
      if (lower.contains("<tv") && !lower.contains("<html"))
        return new ProviderException(
            ProviderException.Reason.FORMAT,
            "This address is an XMLTV program guide. Add it with Settings → Import XMLTV guide,"
                + " and use your M3U link for the playlist.");
      return new ProviderException(
          ProviderException.Reason.FORMAT,
          "The provider returned a web page, not a playlist. The link may need a browser"
              + " sign-in or verification, be blocked on this network, or have expired.");
    }
    // Some providers omit the #EXTM3U header and start with comments or blank lines.
    if (lower.contains("#extinf") || lower.contains("\nhttp")) return null;
    return new ProviderException(
        ProviderException.Reason.FORMAT,
        "The response is not an M3U playlist. Check that you copied the M3U link, not a portal"
            + " or web-player address.");
  }

  private static final Pattern JSON_MESSAGE =
      Pattern.compile("\"(message|error|msg|detail|status_message)\"\\s*:\\s*\"([^\"]{1,160})\"");

  /** A short provider-supplied message, stripped of anything that looks like an address. */
  static String jsonMessage(String json) {
    Matcher m = JSON_MESSAGE.matcher(json);
    if (!m.find()) return null;
    String said = m.group(2).replaceAll("(?i)\\b\\w+://\\S+", "").replaceAll("\\s+", " ").trim();
    return said.isEmpty() ? null : said;
  }

  private static int readFully(InputStream in, byte[] buffer) throws IOException {
    int total = 0;
    while (total < buffer.length) {
      int n = in.read(buffer, total, buffer.length - total);
      if (n < 0) break;
      total += n;
    }
    return total;
  }

  static String safeError(Exception e) {
    if (e instanceof ProviderException) return e.getMessage();
    if (e instanceof UnknownHostException)
      return "Could not find the provider's server (DNS lookup failed). Check the address. If it is"
          + " correct, your network may be blocking it: set Settings → Secure DNS to Always.";
    if (e instanceof java.net.SocketTimeoutException)
      return "The provider timed out. Check the connection and try again.";
    if (e instanceof java.net.ConnectException || e instanceof java.net.NoRouteToHostException)
      return "Could not connect to the provider's server. It may be down or blocked on this"
          + " network.";
    if (e instanceof javax.net.ssl.SSLException)
      return "Secure connection failed. Check the provider certificate and the device's date and"
          + " time.";
    if (e instanceof InterruptedIOException) return "The import was cancelled.";
    return "Import failed. Check the address, provider access, and HTTP setting. Your previous"
        + " library is unchanged.";
  }

  /**
   * Resolves host names with the network's resolver, falling back to DNS-over-HTTPS when that
   * fails or returns a sinkhole address. IPTV hosts are frequently blocked at the ISP's resolver;
   * this matches what Android's Private DNS does, without needing a system setting.
   */
  static final class SecureDns implements Dns {
    static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();
    private static final Pattern A_RECORD =
        Pattern.compile("\"data\"\\s*:\\s*\"(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\"");
    private static final String[] RESOLVERS = {
      "https://1.1.1.1/dns-query?type=A&name=", "https://8.8.8.8/resolve?type=A&name="
    };

    static final class Cached {
      final List<InetAddress> addresses;
      final long until;

      Cached(List<InetAddress> addresses, long until) {
        this.addresses = addresses;
        this.until = until;
      }
    }

    @Override
    public List<InetAddress> lookup(String host) throws UnknownHostException {
      int mode = dnsMode;
      if (mode == DNS_OFF || isLiteral(host) || host.equalsIgnoreCase("localhost"))
        return Dns.SYSTEM.lookup(host);
      if (mode == DNS_ALWAYS) {
        try {
          return secure(host);
        } catch (UnknownHostException e) {
          return Dns.SYSTEM.lookup(host);
        }
      }
      try {
        List<InetAddress> system = Dns.SYSTEM.lookup(host);
        if (!sinkhole(system)) return system;
      } catch (UnknownHostException ignored) {
        // Fall through to secure DNS.
      }
      return secure(host);
    }

    static boolean sinkhole(List<InetAddress> addresses) {
      for (InetAddress a : addresses)
        if (!a.isAnyLocalAddress() && !a.isLoopbackAddress()) return false;
      return true;
    }

    static boolean isLiteral(String host) {
      return host.matches("[0-9.]+") || host.contains(":");
    }

    static List<InetAddress> secure(String host) throws UnknownHostException {
      String key = host.toLowerCase(Locale.ROOT);
      Cached cached = CACHE.get(key);
      if (cached != null && cached.until > System.currentTimeMillis()) return cached.addresses;
      if (!key.matches("[a-z0-9._-]{1,253}")) throw new UnknownHostException("Invalid host");
      for (String resolver : RESOLVERS) {
        Request request =
            new Request.Builder()
                .url(resolver + key)
                .header("Accept", "application/dns-json")
                .build();
        try (Response response = BOOTSTRAP.newCall(request).execute()) {
          if (!response.isSuccessful() || response.body() == null) continue;
          String body = response.body().string();
          List<InetAddress> out = new ArrayList<>();
          Matcher m = A_RECORD.matcher(body);
          while (m.find() && out.size() < 8) {
            String[] parts = m.group(1).split("\\.");
            byte[] bytes = new byte[4];
            boolean valid = true;
            for (int i = 0; i < 4; i++) {
              int value = Integer.parseInt(parts[i]);
              if (value > 255) valid = false;
              bytes[i] = (byte) value;
            }
            if (valid) out.add(InetAddress.getByAddress(host, bytes));
          }
          if (!out.isEmpty() && !sinkhole(out)) {
            List<InetAddress> result = Collections.unmodifiableList(out);
            CACHE.put(key, new Cached(result, System.currentTimeMillis() + 5 * 60_000));
            return result;
          }
        } catch (IOException | RuntimeException ignored) {
          // Try the next resolver.
        }
      }
      throw new UnknownHostException("Host could not be resolved");
    }
  }
}
