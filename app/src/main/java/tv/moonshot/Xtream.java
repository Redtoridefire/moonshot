package tv.moonshot;

import java.net.*;

/** Standard Xtream-compatible M3U/XMLTV endpoints; no provider-specific access bypass. */
final class Xtream {
  static String endpoint(String server, String username, String password, String path) {
    if (!M3uParser.isWebUrl(server) || username.trim().isEmpty() || password.isEmpty())
      throw new IllegalArgumentException("Invalid connection details");
    URI base = URI.create(server);
    if (base.getUserInfo() != null || base.getRawQuery() != null || base.getRawFragment() != null)
      throw new IllegalArgumentException(
          "Enter a server address without credentials or query parameters");
    try {
      return server.replaceAll("/+$", "")
          + "/"
          + path
          + "?username="
          + URLEncoder.encode(username, "UTF-8")
          + "&password="
          + URLEncoder.encode(password, "UTF-8");
    } catch (java.io.UnsupportedEncodingException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  static String playlist(String server, String username, String password) {
    return endpoint(server, username, password, "get.php") + "&type=m3u_plus&output=ts";
  }
}
