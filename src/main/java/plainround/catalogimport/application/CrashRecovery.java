package plainround.catalogimport.application;

/**
 * The port that marks runs killed mid-flight as failed, so that the same parameters can be
 * restarted.
 */
public interface CrashRecovery {

  /** Marks every execution still recorded as running as failed; returns how many it found. */
  int recoverCrashedRuns();
}
