package tv.moonshot;

import java.io.*;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/**
 * Verified TLS, explicit redirect policy and cancellable response ownership. No request logging.
 */
final class Network {
  private static final OkHttpClient CLIENT =
      new OkHttpClient.Builder()
          .followRedirects(false)
          .followSslRedirects(false)
          .retryOnConnectionFailure(true)
          .connectTimeout(15, TimeUnit.SECONDS)
          .readTimeout(30, TimeUnit.SECONDS)
          .build();

  static OkHttpClient playbackClient() {
    return CLIENT.newBuilder().followRedirects(true).followSslRedirects(false).build();
  }

  static final class Resource implements AutoCloseable {
    final InputStream input;
    final String address;

    Resource(InputStream input, String address) {
      this.input = input;
      this.address = address;
    }

    @Override
    public void close() throws IOException {
      input.close();
    }
  }

  static InputStream fetch(String address, boolean allowHttp) throws IOException {
    return open(address, allowHttp, 256L * 1024 * 1024).input;
  }

  static Resource open(String address, boolean allowHttp, long limit) throws IOException {
    String next = address;
    for (int redirects = 0; redirects < 6; redirects++) {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
      if (!M3uParser.isWebUrl(next)) throw new IOException("Enter a valid HTTP or HTTPS address");
      if (!allowHttp && !"https".equalsIgnoreCase(URI.create(next).getScheme()))
        throw new IOException("HTTP is disabled");
      Call call =
          CLIENT.newCall(
              new Request.Builder()
                  .url(next)
                  .header("User-Agent", "Moonshot/0.3 AndroidTV")
                  .build());
      Response response;
      try {
        response = call.execute();
      } catch (IOException e) {
        call.cancel();
        throw e;
      }
      int status = response.code();
      if (status >= 300 && status < 400) {
        String location = response.header("Location");
        String base = response.request().url().toString();
        call.cancel();
        response.close();
        if (location == null) throw new IOException("Invalid provider redirect");
        try {
          next = URI.create(base).resolve(location).toString();
        } catch (IllegalArgumentException e) {
          throw new IOException("Invalid provider redirect");
        }
        continue;
      }
      if (status != 200 || response.body() == null) {
        call.cancel();
        response.close();
        throw new IOException("Provider returned HTTP " + status);
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
      return new Resource(bounded, response.request().url().toString());
    }
    throw new IOException("Too many provider redirects");
  }

  static String safeError(Exception e) {
    if (e instanceof java.net.SocketTimeoutException)
      return "The provider timed out. Check the connection and try again.";
    if (e instanceof javax.net.ssl.SSLException)
      return "Secure connection failed. Check the provider certificate and device clock.";
    return "Import failed. Check the address, provider access, and HTTP setting. Your previous"
        + " library is unchanged.";
  }
}
