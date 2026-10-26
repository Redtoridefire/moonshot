package tv.moonshot;

import android.app.Application;

/** One construction boundary for components; production always uses Android Keystore. */
public class MoonshotApplication extends Application {
  Library openLibrary(String profile) throws Exception {
    return new Library(this, profile);
  }

  Library openLibrary() throws Exception {
    return new Library(this);
  }
}
