package plainround.catalogimport.application;

import java.nio.file.Path;

/**
 * What to import. The file and the run id together are the job's identity: the same pair after a
 * crash restarts the same job instance; a new run id is a new instance that starts from the first
 * row.
 */
public record ImportRequest(Path file, String runId, Path rejectFile) {

  public ImportRequest {
    if (runId == null || runId.isBlank()) {
      throw new IllegalArgumentException("run.id must not be blank");
    }
  }
}
