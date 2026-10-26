package tv.moonshot;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.*;

final class BackupCrypto {
  static final long LIMIT = 32L * 1024 * 1024;
  static final byte[] MAGIC = "MNSB1".getBytes(StandardCharsets.US_ASCII);

  static CipherOutputStream encrypt(OutputStream out, char[] password) throws Exception {
    validate(password);
    byte[] header = new byte[33];
    System.arraycopy(MAGIC, 0, header, 0, 5);
    byte[] random = new byte[28];
    new SecureRandom().nextBytes(random);
    System.arraycopy(random, 0, header, 5, 28);
    out.write(header);
    return new CipherOutputStream(out, cipher(Cipher.ENCRYPT_MODE, password, header));
  }

  static CipherInputStream decrypt(InputStream in, char[] password) throws Exception {
    validate(password);
    byte[] header = new byte[33];
    new DataInputStream(in).readFully(header);
    if (!Arrays.equals(MAGIC, Arrays.copyOfRange(header, 0, 5)))
      throw new IOException("Unsupported backup");
    return new CipherInputStream(in, cipher(Cipher.DECRYPT_MODE, password, header));
  }

  static void validate(char[] password) {
    if (password.length < 12 || password.length > 256)
      throw new IllegalArgumentException("Use a passphrase of 12 to 256 characters");
  }

  static Cipher cipher(int mode, char[] password, byte[] header) throws Exception {
    PBEKeySpec spec = new PBEKeySpec(password, Arrays.copyOfRange(header, 5, 21), 310000, 256);
    byte[] key;
    try {
      key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    } finally {
      spec.clearPassword();
    }
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          mode,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(128, Arrays.copyOfRange(header, 21, 33)));
      cipher.updateAAD(header);
      return cipher;
    } finally {
      Arrays.fill(key, (byte) 0);
    }
  }

  static InputStream bounded(InputStream in) {
    return new FilterInputStream(in) {
      long size;

      void check(int n) throws IOException {
        if (n > 0) size += n;
        if (size > LIMIT) throw new IOException("Backup exceeds 32 MiB");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
      }

      @Override
      public int read() throws IOException {
        int n = in.read();
        check(n < 0 ? 0 : 1);
        return n;
      }

      @Override
      public int read(byte[] b, int o, int l) throws IOException {
        int n = in.read(b, o, l);
        check(n);
        return n;
      }
    };
  }
}
