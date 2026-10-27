package plainround.catalogimport.application;

import com.github.sviperll.result4j.Result;

/**
 * The port to "only one import at a time". The lock is tied to a live process, so a crash releases
 * it by itself: that is what makes it safe to treat an execution recorded as running as dead once
 * the lock can be taken.
 */
public interface RunLock {

  /** A held lock; closing it releases it. */
  interface Lease extends AutoCloseable {
    @Override
    void close();
  }

  /** The lease, or the reason another process holds the lock. */
  Result<Lease, String> tryAcquire();
}
