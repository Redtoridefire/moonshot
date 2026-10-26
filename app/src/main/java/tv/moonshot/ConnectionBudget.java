package tv.moonshot;

import java.util.concurrent.atomic.AtomicBoolean;

/** Counts app-owned provider sessions, including DVR; user supplies the subscription limit. */
final class ConnectionBudget {
  private static int used;

  static synchronized Lease acquire(int limit, int count) {
    if (count < 1 || limit < 1 || used + count > limit) return null;
    used += count;
    return new Lease(count);
  }

  static final class Lease implements AutoCloseable {
    final int count;
    final AtomicBoolean released = new AtomicBoolean();

    Lease(int count) {
      this.count = count;
    }

    @Override
    public void close() {
      if (released.compareAndSet(false, true))
        synchronized (ConnectionBudget.class) {
          used -= count;
        }
    }
  }
}
