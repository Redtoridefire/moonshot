package tv.moonshot;

import android.graphics.*;
import java.io.*;

final class ProviderArtwork {
  static Bitmap load(String address, boolean http) throws Exception {
    if (!M3uParser.isWebUrl(address)) throw new IOException("Invalid image address");
    byte[] bytes;
    try (Network.Resource resource = Network.open(address, http, 5L * 1024 * 1024);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int n;
      while ((n = resource.input.read(buffer)) != -1) out.write(buffer, 0, n);
      bytes = out.toByteArray();
    }
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
    if (bounds.outWidth <= 0
        || bounds.outHeight <= 0
        || bounds.outWidth > 8192
        || bounds.outHeight > 8192) throw new IOException("Unsupported image dimensions");
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inSampleSize = 1;
    while (bounds.outWidth / options.inSampleSize > 800
        || bounds.outHeight / options.inSampleSize > 800) options.inSampleSize *= 2;
    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    if (bitmap == null) throw new IOException("Unsupported image");
    return bitmap;
  }
}
