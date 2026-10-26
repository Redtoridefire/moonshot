package tv.moonshot;

import static org.junit.Assert.*;

import java.io.*;
import javax.crypto.*;
import org.junit.Test;

public class BackupCryptoTest {
  char[] pass = "a long secret passphrase".toCharArray();

  byte[] encrypted() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (CipherOutputStream cipher = BackupCrypto.encrypt(out, pass)) {
      cipher.write("private fixture".getBytes("UTF-8"));
    }
    return out.toByteArray();
  }

  @Test
  public void roundTripsAndRandomizes() throws Exception {
    byte[] a = encrypted(), b = encrypted();
    assertFalse(java.util.Arrays.equals(a, b));
    try (InputStream in = BackupCrypto.decrypt(new ByteArrayInputStream(a), pass)) {
      assertEquals("private fixture", new String(in.readAllBytes(), "UTF-8"));
    }
  }

  @Test
  public void wrongPasswordFails() throws Exception {
    try (InputStream in =
        BackupCrypto.decrypt(
            new ByteArrayInputStream(encrypted()), "another long passphrase".toCharArray())) {
      in.readAllBytes();
      fail();
    } catch (IOException expected) {
    }
  }

  @Test
  public void tamperFails() throws Exception {
    byte[] data = encrypted();
    data[data.length - 1] ^= 1;
    try (InputStream in = BackupCrypto.decrypt(new ByteArrayInputStream(data), pass)) {
      in.readAllBytes();
      fail();
    } catch (IOException expected) {
    }
  }
}
