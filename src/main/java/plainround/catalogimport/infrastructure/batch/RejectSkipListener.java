package plainround.catalogimport.infrastructure.batch;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.SkipListener;
import plainround.catalogimport.domain.ProductRow;
import plainround.catalogimport.domain.RejectedRow;
import plainround.catalogimport.infrastructure.jdbc.JdbcRejectLog;

/**
 * Records every row the processor refuses, with its line number and the reason, in the chunk's
 * transaction.
 */
@RequiredArgsConstructor
public class RejectSkipListener implements SkipListener<ProductRow, ProductRow> {

  private final String sourceFile;
  private final JdbcRejectLog rejects;

  @Override
  public void onSkipInProcess(ProductRow row, Throwable reason) {
    rejects.record(sourceFile, RejectedRow.of(row, reason.getMessage()));
  }
}
