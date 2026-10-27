package plainround.catalogimport.application;

/**
 * What a run did, from the step's counters. Zero counts with a status other than COMPLETED mean the
 * run did not start.
 */
public record ImportReport(
    Status status,
    long read,
    long written,
    long skipped,
    long commits,
    boolean resumed,
    String message) {

  /** How the run ended. */
  public enum Status {
    COMPLETED,
    FAILED,
    STOPPED,
    ALREADY_COMPLETE,
    BLOCKED
  }

  /**
   * 0 when the table is right, 1 when failed or stopped, 3 when the same parameters already
   * completed, 4 when blocked.
   */
  public int exitCode() {
    return switch (status) {
      case COMPLETED -> 0;
      case ALREADY_COMPLETE -> 3;
      case BLOCKED -> 4;
      case FAILED, STOPPED -> 1;
    };
  }

  /** A run refused because the same file and run id already finished. */
  public static ImportReport alreadyComplete(String runId) {
    return new ImportReport(
        Status.ALREADY_COMPLETE,
        0,
        0,
        0,
        0,
        false,
        "run.id '%s' already completed; use a new run.id to import again".formatted(runId));
  }

  /** A run refused because another process holds the import's lock. */
  public static ImportReport blocked(String reason) {
    return new ImportReport(Status.BLOCKED, 0, 0, 0, 0, false, reason);
  }
}
