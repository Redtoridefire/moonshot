package tv.moonshot;

import java.io.IOException;

/**
 * A provider/import failure whose message is safe to show: it never contains the playlist
 * address, path, query string or credentials. {@link #reason} lets the UI offer a direct fix.
 */
final class ProviderException extends IOException {
  enum Reason {
    /** The provider (or one of its redirects) needs unencrypted HTTP, which is turned off. */
    NEEDS_HTTP,
    /** The host name could not be resolved. */
    DNS,
    /** The provider answered with an HTTP error status. */
    STATUS,
    /** The response was not an M3U playlist. */
    FORMAT,
    /** Anything else. */
    OTHER
  }

  final Reason reason;
  final int status;

  ProviderException(Reason reason, String safeMessage) {
    this(reason, safeMessage, 0);
  }

  ProviderException(Reason reason, String safeMessage, int status) {
    super(safeMessage);
    this.reason = reason;
    this.status = status;
  }

  static ProviderException status(int code) {
    String meaning;
    if (code == 401 || code == 403)
      meaning =
          "The provider refused access (HTTP "
              + code
              + "). The link may be expired, the account inactive or locked to another device, or"
              + " the provider may reject this player. Try Settings → Player identity, or ask"
              + " your provider for a fresh link.";
    else if (code == 404 || code == 410)
      meaning =
          "The provider has no playlist at this address (HTTP "
              + code
              + "). Copy the link again from your provider; it may have changed or expired.";
    else if (code == 429)
      meaning = "The provider is rate-limiting requests (HTTP 429). Wait a few minutes and retry.";
    else if (code == 512 || code == 513 || code == 884)
      meaning =
          "The provider reports the account's connection limit is in use (HTTP "
              + code
              + "). Close the stream on other devices and retry.";
    else if (code >= 500)
      meaning =
          "The provider's server had an error (HTTP " + code + "). It is likely temporary; retry.";
    else meaning = "The provider returned HTTP " + code + " instead of a playlist.";
    return new ProviderException(Reason.STATUS, meaning, code);
  }
}
