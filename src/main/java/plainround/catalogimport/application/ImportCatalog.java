package plainround.catalogimport.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The use case: take the lock, recover from a crash, run the job, write the reject file.
 *
 * <p>Recovery comes first because Spring Batch refuses to start a job instance that still has an
 * execution recorded as running, which is exactly what a {@code kill -9} leaves behind. It is safe
 * only because the lock proves that no other process is running the import.
 */
@Slf4j
@RequiredArgsConstructor
public class ImportCatalog {

  private final RunLock lock;
  private final CrashRecovery recovery;
  private final ImportJob job;
  private final RejectLog rejects;

  /**
   * Runs the import for the request and returns the report; the reject file is rewritten from the
   * record.
   */
  public ImportReport run(ImportRequest request) {
    log.info("import starting file={} runId={}", request.file(), request.runId());
    return lock.tryAcquire()
        .map(lease -> runLocked(request, lease))
        .recoverError(ImportReport::blocked);
  }

  private ImportReport runLocked(ImportRequest request, RunLock.Lease lease) {
    try (lease) {
      int recovered = recovery.recoverCrashedRuns();
      ImportReport report = job.launch(request);
      int rejected = rejects.export(request.file().toString(), request.rejectFile());
      log.info(
          "import finished status={} recovered={} rejected={}",
          report.status(),
          recovered,
          rejected);
      return report;
    }
  }
}
