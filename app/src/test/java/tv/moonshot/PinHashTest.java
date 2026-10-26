package tv.moonshot;

import static org.junit.Assert.*;

import org.junit.Test;

public class PinHashTest {
  @Test
  public void hashesAreSaltedAndVerified() throws Exception {
    char[] pin = "123456".toCharArray();
    String a = PinHash.create(pin), b = PinHash.create(pin);
    assertNotEquals(a, b);
    assertTrue(PinHash.verify(pin, a));
    assertFalse(PinHash.verify("654321".toCharArray(), a));
    assertFalse(a.contains("123456"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsShortPins() throws Exception {
    PinHash.create("1234".toCharArray());
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsNonDigits() throws Exception {
    PinHash.create("password".toCharArray());
  }
}
