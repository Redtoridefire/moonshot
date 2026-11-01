package tv.moonshot;

import java.io.*;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Step-by-step playlist link diagnosis: address, DNS (network vs. secure), each redirect hop,
 * response format, playlist contents and a sample stream. Reports only host names and status codes;
 * paths, query strings and credentials are never shown or logged.
 */
final class PlaylistProbe {
  enum Fix {
    NONE,
    ALLOW_HTTP,
    SECURE_DNS,
    AGENT,
    XTREAM
  }

  static final class Step {
    final String state, title, detail;

    Step(String state, String title, String detail) {
      this.state = state;
      this.title = title;
      this.detail = detail;
    }
  }

  static final class Result {
    final List<Step> steps = new ArrayList<>();
    boolean ok;
    String verdict = "";
    Fix fix = Fix.NONE;
    int agent = -1;

    void add(String state, String title, String detail) {
      steps.add(new Step(state, title, detail));
    }
  }

  static Result run(String address, boolean allowHttp) {
    Result r = new Result();
    URI uri;
    try {
      uri = URI.create(address.trim());
    } catch (IllegalArgumentException e) {
      uri = null;
    }
    if (uri == null || !M3uParser.isWebUrl(address.trim())) {
      r.add("fail", "Address", "This is not a complete web address. It must start with https://");
      r.verdict = "Paste the full M3U link, including https:// at the start.";
      return r;
    }
    address = address.trim();
    String host = uri.getHost();
    boolean https = "https".equalsIgnoreCase(uri.getScheme());
    r.add(
        https ? "ok" : allowHttp ? "warn" : "fail",
        "Address · " + host,
        https
            ? "Encrypted HTTPS link."
            : allowHttp
                ? "Unencrypted HTTP link. It works, but your network can see it."
                : "Unencrypted HTTP link, and HTTP is turned off in Settings.");
    if (!https && !allowHttp) {
      r.fix = Fix.ALLOW_HTTP;
      r.verdict = "Allow unencrypted HTTP to use this provider's link.";
      return r;
    }

    // DNS: compare the network's resolver with secure DNS.
    String systemDns;
    boolean systemOk = false;
    try {
      InetAddress[] found = InetAddress.getAllByName(host);
      List<InetAddress> list = Arrays.asList(found);
      systemOk = !Network.SecureDns.sinkhole(list) || host.equalsIgnoreCase("localhost");
      systemDns = systemOk ? "found " + found[0].getHostAddress() : "returned a blocked address";
    } catch (UnknownHostException e) {
      systemDns = "could not find it";
    }
    String secureDns;
    boolean secureOk = false;
    if (Network.SecureDns.isLiteral(host) || host.equalsIgnoreCase("localhost")) {
      secureOk = true;
      secureDns = "not needed";
    } else
      try {
        List<InetAddress> found = Network.SecureDns.secure(host);
        secureOk = true;
        secureDns = "found " + found.get(0).getHostAddress();
      } catch (UnknownHostException e) {
        secureDns = "could not find it";
      }
    if (systemOk) r.add("ok", "Server lookup", "Network DNS " + systemDns + ".");
    else if (secureOk && Network.dnsMode() != Network.DNS_OFF)
      r.add(
          "warn",
          "Server lookup",
          "Your network's DNS "
              + systemDns
              + " (often an ISP block), but Secure DNS "
              + secureDns
              + ". Moonshot uses Secure DNS for this automatically.");
    else if (secureOk) {
      r.add(
          "fail",
          "Server lookup",
          "Your network's DNS " + systemDns + ", but Secure DNS " + secureDns + ". Secure DNS is off.");
      r.fix = Fix.SECURE_DNS;
      r.verdict = "Your network is blocking this provider. Turn Secure DNS on.";
      return r;
    } else {
      r.add(
          "fail",
          "Server lookup",
          "Neither your network's DNS nor Secure DNS can find "
              + host
              + ". The address may be misspelled, or the provider's domain has moved or shut"
              + " down. Ask your provider for the current link.");
      r.verdict = "The provider's server name does not exist right now.";
      return r;
    }

    // Playlist download with the configured identity, then alternatives if refused.
    String agent = Network.userAgent();
    Attempt first = attempt(address, allowHttp, agent);
    describeHops(r, first);
    Attempt success = first.error == null ? first : null;
    if (success == null) {
      r.add("fail", "Playlist download", Network.safeError(first.error));
      if (first.error instanceof ProviderException
          && ((ProviderException) first.error).reason == ProviderException.Reason.NEEDS_HTTP) {
        r.fix = Fix.ALLOW_HTTP;
        r.verdict = "Your provider sends the playlist over unencrypted HTTP. Allow HTTP to use it.";
        return r;
      }
      if (first.error instanceof ProviderException
          && first.error.getMessage().contains("Xtream")) {
        r.fix = Fix.XTREAM;
        r.verdict = "Use the Xtream login option with this provider.";
        return r;
      }
      if (retryable(first.error))
        for (int i = 0; i < Network.AGENTS.length; i++) {
          if (Network.AGENTS[i].equals(agent)) continue;
          Attempt alt = attempt(address, allowHttp, Network.AGENTS[i]);
          if (alt.error == null) {
            r.add(
                "warn",
                "Player identity",
                "The provider rejected Moonshot's current identity but accepts “"
                    + Network.AGENT_LABELS[i]
                    + "”.");
            r.fix = Fix.AGENT;
            r.agent = i;
            success = alt;
            break;
          }
        }
      if (success == null) {
        if (retryable(first.error))
          r.add(
              "fail",
              "Other player identities",
              "The provider also refused the alternative identities, so this is not a player"
                  + " restriction.");
        r.verdict =
            first.error instanceof ProviderException
                ? "The provider did not return a playlist. See the reason above."
                : "Moonshot could not reach the provider. See the reason above.";
        return r;
      }
    }
    r.add(
        "ok",
        "Playlist download",
        String.format(
            Locale.ROOT,
            "%s received%s.",
            size(success.bytes),
            success.contentType == null ? "" : " · " + success.contentType.split(";")[0]));
    if (success.parsed > 0) {
      r.add(
          "ok",
          "Playlist contents",
          String.format(
              Locale.ROOT,
              "%,d streams in %,d groups: %,d live, %,d movies, %,d series episodes.",
              success.parsed,
              success.groups.size(),
              success.live,
              success.movies,
              success.series));
      if (success.insecure > 0 && !allowHttp)
        r.add(
            "warn",
            "Stream addresses",
            String.format(
                Locale.ROOT,
                "%,d of %,d streams use unencrypted HTTP. They will not play until you allow"
                    + " HTTP.",
                success.insecure,
                success.parsed));
      else if (success.insecure > 0)
        r.add(
            "ok",
            "Stream addresses",
            String.format(Locale.ROOT, "%,d streams use HTTP, which is allowed.", success.insecure));
    } else {
      r.add("fail", "Playlist contents", success.parseError);
      r.verdict = "The download worked, but it is not a usable playlist.";
      return r;
    }

    // One sample stream: does it answer, and where does it redirect?
    if (success.sample != null) {
      Attempt stream = sample(success.sample, allowHttp, r.agent >= 0 ? Network.AGENTS[r.agent] : agent);
      if (stream.error == null)
        r.add(
            "ok",
            "Sample stream · " + success.sampleName,
            "Answered"
                + hopSummary(stream)
                + (stream.contentType == null ? "." : " with " + stream.contentType.split(";")[0] + "."));
      else {
        boolean http =
            stream.error instanceof ProviderException
                && ((ProviderException) stream.error).reason == ProviderException.Reason.NEEDS_HTTP;
        r.add(
            http ? "warn" : "fail",
            "Sample stream · " + success.sampleName,
            (http
                    ? "The stream redirects to unencrypted HTTP, which is turned off."
                    : Network.safeError(stream.error))
                + hopSummary(stream));
        if (http && r.fix == Fix.NONE) r.fix = Fix.ALLOW_HTTP;
      }
    }
    if (success.insecure > 0 && !allowHttp && r.fix == Fix.NONE) r.fix = Fix.ALLOW_HTTP;
    r.ok = true;
    r.verdict =
        r.fix == Fix.AGENT
            ? "This link works when Moonshot uses the “"
                + Network.AGENT_LABELS[r.agent]
                + "” identity."
            : r.fix == Fix.ALLOW_HTTP
                ? "The playlist works. Allow HTTP so its streams can play."
                : "This link works. Add it as a playlist.";
    return r;
  }

  private static boolean retryable(IOException e) {
    if (!(e instanceof ProviderException)) return false;
    ProviderException p = (ProviderException) e;
    return (p.reason == ProviderException.Reason.STATUS
            && (p.status == 401 || p.status == 403 || p.status == 404 || p.status == 406))
        || p.reason == ProviderException.Reason.FORMAT;
  }

  static final class Attempt {
    IOException error;
    final List<String> hops = new ArrayList<>();
    long bytes;
    String contentType;
    int parsed, live, movies, series, insecure;
    final Set<String> groups = new HashSet<>();
    String sample, sampleName, parseError;
  }

  private static Attempt attempt(String address, boolean allowHttp, String agent) {
    Attempt a = new Attempt();
    Network.Trace trace = (hop, status) -> a.hops.add(hostOf(hop) + " " + status);
    try (Network.Resource resource =
            Network.open(address, allowHttp, 256L * 1024 * 1024, agent, trace);
        InputStream counted =
            new FilterInputStream(resource.input) {
              @Override
              public int read() throws IOException {
                int n = super.read();
                if (n >= 0) a.bytes++;
                return n;
              }

              @Override
              public int read(byte[] b, int o, int l) throws IOException {
                int n = super.read(b, o, l);
                if (n > 0) a.bytes += n;
                return n;
              }
            };
        InputStream text = Network.playlist(counted);
        Reader reader = new InputStreamReader(text, StandardCharsets.UTF_8)) {
      a.contentType = resource.contentType;
      try {
        M3uParser.parse(
            reader,
            ch -> {
              a.parsed++;
              a.groups.add(ch.group);
              if (ch.kind.equals("movie")) a.movies++;
              else if (ch.kind.equals("series")) a.series++;
              else a.live++;
              if (ch.url.regionMatches(true, 0, "http:", 0, 5)) a.insecure++;
              if (a.sample == null && ch.kind.equals("live")) {
                a.sample = ch.url;
                a.sampleName = ch.name;
              }
            });
      } catch (IOException e) {
        a.parseError = Network.safeError(e);
      } catch (Exception e) {
        a.parseError = "The playlist could not be read.";
      }
    } catch (IOException e) {
      a.error = e;
    }
    return a;
  }

  private static Attempt sample(String address, boolean allowHttp, String agent) {
    Attempt a = new Attempt();
    Network.Trace trace = (hop, status) -> a.hops.add(hostOf(hop) + " " + status);
    try (Network.Resource resource = Network.open(address, allowHttp, 64 * 1024, agent, trace)) {
      a.contentType = resource.contentType;
      byte[] buffer = new byte[2048];
      resource.input.read(buffer);
    } catch (IOException e) {
      a.error = e;
    }
    return a;
  }

  private static void describeHops(Result r, Attempt a) {
    if (a.hops.size() > 1)
      r.add("info", "Redirects", "Route: " + String.join("  →  ", a.hops));
  }

  private static String hopSummary(Attempt a) {
    return a.hops.size() > 1 ? " (route: " + String.join(" → ", a.hops) + ")" : "";
  }

  static String hostOf(String address) {
    try {
      URI u = URI.create(address);
      return (u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT) + "://")
          + u.getHost();
    } catch (IllegalArgumentException e) {
      return "unknown host";
    }
  }

  private static String size(long bytes) {
    if (bytes < 1024) return bytes + " bytes";
    if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024f);
    return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576f);
  }
}
