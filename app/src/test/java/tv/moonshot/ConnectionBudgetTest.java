package tv.moonshot;

import static org.junit.Assert.*;

import org.junit.Test;

public class ConnectionBudgetTest {
  @Test
  public void sharesBudgetAndReleasesExactlyOnce() {
    ConnectionBudget.Lease recording = ConnectionBudget.acquire(2, 1);
    assertNotNull(recording);
    assertNull(ConnectionBudget.acquire(2, 2));
    ConnectionBudget.Lease player = ConnectionBudget.acquire(2, 1);
    assertNotNull(player);
    assertNull(ConnectionBudget.acquire(2, 1));
    player.close();
    player.close();
    assertNull(ConnectionBudget.acquire(2, 2));
    recording.close();
    try (ConnectionBudget.Lease multi = ConnectionBudget.acquire(2, 2)) {
      assertNotNull(multi);
    }
  }
}
