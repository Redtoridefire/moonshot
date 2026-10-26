package tv.moonshot;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import javax.crypto.KeyGenerator;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FamilyTest {
  Context context;

  @Before
  public void setup() {
    context = ApplicationProvider.getApplicationContext();
    Family.prefs(context).edit().clear().commit();
    Family.clearSession();
  }

  @Test
  public void pinProtectsHomeAndAdminSettings() throws Exception {
    Family.setPin(context, "123456".toCharArray());
    Family.clearSession();
    assertTrue(Family.needsUnlock(context));
    assertFalse(Family.admin(context));
    assertFalse(Family.verify(context, "654321".toCharArray()));
    assertTrue(Family.verify(context, "123456".toCharArray()));
    Family.grant(context, false);
    assertFalse(Family.needsUnlock(context));
    assertFalse(Family.admin(context));
  }

  @Test
  public void wrongAttemptsLockOutCorrectPinTemporarily() throws Exception {
    Family.setPin(context, "123456".toCharArray());
    for (int i = 0; i < 5; i++) assertFalse(Family.verify(context, "654321".toCharArray()));
    assertFalse(Family.verify(context, "123456".toCharArray()));
  }

  @Test
  public void familyProfileUsesSeparateDatabaseAndCannotUnlockSettings() throws Exception {
    Family.setPin(context, "123456".toCharArray());
    Vault vault = new Vault(KeyGenerator.getInstance("AES").generateKey());
    try (Library home = new Library(context, vault)) {
      home.setting("test", "home");
    }
    String id = Family.create(context, "Children", true);
    Family.select(context, id);
    assertFalse(Family.needsUnlock(context));
    assertFalse(Family.admin(context));
    try (Library child = new Library(context, vault)) {
      assertNull(child.setting("test"));
      child.setting("test", "child");
    }
    Family.select(context, "home");
    try (Library home = new Library(context, vault)) {
      assertEquals("home", home.setting("test"));
    }
  }

  @Test
  public void expiredSessionCannotChangeParentalPin() throws Exception {
    Family.setPin(context, "123456".toCharArray());
    Family.clearSession();
    try {
      Family.setPin(context, "654321".toCharArray());
      fail("PIN change must require parental authorization");
    } catch (SecurityException expected) {
    }
    assertTrue(Family.verify(context, "123456".toCharArray()));
  }

  @Test
  public void changingWallClockCannotBypassSameBootLockout() throws Exception {
    Family.setPin(context, "123456".toCharArray());
    for (int i = 0; i < 5; i++) Family.verify(context, "654321".toCharArray());
    Family.prefs(context).edit().putLong("lockedUntil", 0).commit();
    assertFalse(Family.verify(context, "123456".toCharArray()));
  }
}
