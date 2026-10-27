package plainround.catalogimport.infrastructure.batch;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.dao.TransientDataAccessResourceException;
import plainround.catalogimport.domain.ProductRow;

/**
 * Wraps the writer and makes every Nth chunk fail once with a transient database error (the
 * lesson's step 6), so the retry policy can be seen working. A retried chunk reaches the writer
 * again with the same first row and succeeds.
 */
@RequiredArgsConstructor
public class TransientFaultInjector implements ItemWriter<ProductRow> {

  private final ItemWriter<ProductRow> delegate;
  private final int everyNthChunk;
  private final AtomicLong chunks = new AtomicLong();
  private final Set<Integer> failedOnce = new HashSet<>();
  private final AtomicLong injected = new AtomicLong();

  @Override
  public void write(Chunk<? extends ProductRow> chunk) throws Exception {
    if (shouldFail(chunk)) {
      injected.incrementAndGet();
      throw new TransientDataAccessResourceException("injected transient failure");
    }
    delegate.write(chunk);
  }

  private boolean shouldFail(Chunk<? extends ProductRow> chunk) {
    int first = chunk.getItems().getFirst().lineNumber();
    boolean fresh = !failedOnce.contains(first);
    return everyNthChunk > 0
        && fresh
        && chunks.incrementAndGet() % everyNthChunk == 0
        && failedOnce.add(first);
  }

  /** How many failures were injected, for the tests and the log. */
  public long injected() {
    return injected.get();
  }
}
