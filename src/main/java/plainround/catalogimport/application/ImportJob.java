package plainround.catalogimport.application;

/** The port to the batch engine: start a job for a request and say what happened. */
public interface ImportJob {

  /**
   * Launches the job with the request's identity; a restart of a crashed run resumes where it
   * stopped.
   */
  ImportReport launch(ImportRequest request);
}
