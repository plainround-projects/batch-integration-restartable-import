package plainround.catalogimport.application;

import com.github.sviperll.result4j.Result;
import java.util.concurrent.atomic.AtomicInteger;

/** A lock that is free or held by someone else, and counts how often a lease was released. */
class FakeRunLock implements RunLock {

  final AtomicInteger released = new AtomicInteger();
  private final boolean free;

  FakeRunLock(boolean free) {
    this.free = free;
  }

  @Override
  public Result<Lease, String> tryAcquire() {
    return free ? Result.success(released::incrementAndGet) : Result.error("held by pid 42");
  }
}
