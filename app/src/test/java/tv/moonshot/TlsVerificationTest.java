package tv.moonshot;

import static org.junit.Assert.*;

import okhttp3.mockwebserver.*;
import okhttp3.tls.*;
import org.junit.Test;

public class TlsVerificationTest {
  @Test
  public void untrustedProviderCertificateIsRejected() throws Exception {
    HeldCertificate certificate =
        new HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build();
    HandshakeCertificates serverTls =
        new HandshakeCertificates.Builder().heldCertificate(certificate).build();
    try (MockWebServer server = new MockWebServer()) {
      server.useHttps(serverTls.sslSocketFactory(), false);
      server.enqueue(new MockResponse().setBody("should never be accepted"));
      server.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
      try (Network.Resource ignored =
          Network.open("https://127.0.0.1:" + server.getPort() + "/private-fixture", false, 1024)) {
        fail("Self-signed provider certificate must not be trusted");
      } catch (javax.net.ssl.SSLHandshakeException
          | javax.net.ssl.SSLPeerUnverifiedException expected) {
      }
    }
  }
}
