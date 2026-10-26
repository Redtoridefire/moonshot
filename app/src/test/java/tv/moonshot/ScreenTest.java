package tv.moonshot;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import javax.crypto.KeyGenerator;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = ScreenTest.TestApplication.class, qualifiers = "w960dp-h540dp-land")
public class ScreenTest {
  public static class TestApplication extends MoonshotApplication {
    Vault vault;

    @Override
    public void onCreate() {
      super.onCreate();
      try {
        vault = new Vault(KeyGenerator.getInstance("AES").generateKey());
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    Library openLibrary(String profile) {
      return new Library(this, vault, profile);
    }

    @Override
    Library openLibrary() {
      return new Library(this, vault);
    }
  }

  TextView text(View v, String value) {
    if (v instanceof TextView && value.equals(((TextView) v).getText().toString()))
      return (TextView) v;
    if (v instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
        TextView found = text(((ViewGroup) v).getChildAt(i), value);
        if (found != null) return found;
      }
    return null;
  }

  TextView hint(View v, String value) {
    if (v instanceof TextView
        && value.equals(
            ((TextView) v).getHint() == null ? null : ((TextView) v).getHint().toString()))
      return (TextView) v;
    if (v instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
        TextView found = hint(((ViewGroup) v).getChildAt(i), value);
        if (found != null) return found;
      }
    return null;
  }

  void settle() throws Exception {
    for (int i = 0; i < 20; i++) {
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      Thread.sleep(20);
    }
  }

  @Test
  public void playlistAddressIsReadableAndGuideOpens() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      settle();
      View root = activity.getWindow().getDecorView();
      long ready = System.currentTimeMillis() + 5000;
      while (text(root, "Your next favorite starts here.") == null
          && System.currentTimeMillis() < ready) {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Thread.sleep(20);
      }
      assertNotNull(
          "Empty library must finish loading", text(root, "Your next favorite starts here."));
      text(root, "Settings").performClick();
      AlertDialog settings = ShadowAlertDialog.getLatestAlertDialog();
      settings.getListView().performItemClick(null, 0, 0);
      AlertDialog entry = ShadowAlertDialog.getLatestAlertDialog();
      assertTrue(
          (entry.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
      EditText address =
          (EditText)
              hint(entry.getWindow().getDecorView(), "https://provider.example/playlist.m3u");
      assertNotNull("M3U address input must be present", address);
      address.setText("https://example.invalid/playlist.m3u?token=fixture");
      assertNull(
          "The address must be readable, not password masked", address.getTransformationMethod());
      assertEquals(
          android.text.InputType.TYPE_TEXT_VARIATION_URI,
          address.getInputType() & android.text.InputType.TYPE_MASK_VARIATION);
      assertEquals(
          "https://example.invalid/playlist.m3u?token=fixture", address.getText().toString());
      entry.dismiss();
      settings.dismiss();
      assertNotNull(text(root, "Program guide"));
    }
  }

  @Test
  public void emptyGuideBuildsAndTimezoneChoicePersists() throws Exception {
    try (ActivityController<GuideActivity> controller =
        Robolectric.buildActivity(GuideActivity.class).setup()) {
      GuideActivity activity = controller.get();
      settle();
      View root = activity.getWindow().getDecorView();
      assertNotNull(text(root, "Later"));
      text(root, "Time zone").performClick();
      AlertDialog zones = ShadowAlertDialog.getLatestAlertDialog();
      zones.getListView().performItemClick(null, 0, 0);
      settle();
      assertNotNull(activity.getSharedPreferences("preferences", 0).getString("guideZone", null));
    }
  }
}
