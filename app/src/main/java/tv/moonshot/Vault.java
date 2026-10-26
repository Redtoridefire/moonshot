package tv.moonshot;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Android Keystore holds the non-exportable AES key; only ciphertext reaches disk. */
final class Vault {
  private final javax.crypto.SecretKey key;

  Vault(javax.crypto.SecretKey key) {
    this.key = key;
  }

  Vault() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    String alias = "moonshot.credentials.v1";
    if (!store.containsAlias(alias)) {
      KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      g.init(
          new KeyGenParameterSpec.Builder(
                  alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      g.generateKey();
    }
    key = (javax.crypto.SecretKey) store.getKey(alias, null);
  }

  synchronized String seal(String text) throws Exception {
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.ENCRYPT_MODE, key);
    return Base64.encodeToString(c.getIV(), Base64.NO_WRAP)
        + ":"
        + Base64.encodeToString(c.doFinal(text.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
  }

  synchronized String open(String text) throws Exception {
    String[] parts = text.split(":", 2);
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(
        Cipher.DECRYPT_MODE,
        key,
        new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
    return new String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
  }
}
