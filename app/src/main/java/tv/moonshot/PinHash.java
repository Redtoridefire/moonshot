package tv.moonshot;

import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.PBEKeySpec;

final class PinHash {
  static String create(char[] pin) throws Exception {
    if (pin.length < 6 || pin.length > 12) throw new IllegalArgumentException("Use 6 to 12 digits");
    for (char digit : pin)
      if (digit < '0' || digit > '9') throw new IllegalArgumentException("Use digits only");
    byte[] salt = new byte[16];
    new SecureRandom().nextBytes(salt);
    return Base64.getEncoder().encodeToString(salt)
        + ":"
        + Base64.getEncoder().encodeToString(derive(pin, salt));
  }

  static boolean verify(char[] pin, String record) throws Exception {
    if (pin.length < 6 || pin.length > 12 || record == null) return false;
    String[] parts = record.split(":", -1);
    if (parts.length != 2) return false;
    byte[] salt = Base64.getDecoder().decode(parts[0]),
        expected = Base64.getDecoder().decode(parts[1]);
    return salt.length == 16
        && expected.length == 32
        && MessageDigest.isEqual(expected, derive(pin, salt));
  }

  private static byte[] derive(char[] pin, byte[] salt) throws Exception {
    PBEKeySpec spec = new PBEKeySpec(pin, salt, 210000, 256);
    try {
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    } finally {
      spec.clearPassword();
    }
  }
}
