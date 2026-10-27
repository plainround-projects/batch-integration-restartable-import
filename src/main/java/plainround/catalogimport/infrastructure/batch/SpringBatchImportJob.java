package plainround.catalogimport.infrastructure.batch;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import plainround.catalogimport.application.ImportJob;
import plainround.catalogimport.application.ImportReport;
import plainround.catalogimport.application.ImportReport.Status;
import plainround.catalogimport.application.ImportRequest;

/**
 * Launches the job with the request's identity as job parameters. The same file and run id after a
 * crash is the same job instance, so the reader resumes from the last committed chunk; a new run id
 * starts a new instance.
 */
@RequiredArgsConstructor
public class SpringBatchImportJob implements ImportJob {

  private final JobLauncher launcher;
  private final Job job;
  private final JobExplorer explorer;

  @Override
  public ImportReport launch(ImportRequest request) {
    return Attempt.of(() -> launcher.run(job, parameters(request)))
        .map(this::report)
        .recoverError(problem -> failure(request, problem));
  }

  private JobParameters parameters(ImportRequest request) {
    return new JobParametersBuilder()
        .addString("file", request.file().toString())
        .addString("run.id", request.runId())
        .toJobParameters();
  }

  private ImportReport report(JobExecution execution) {
    StepExecution step = execution.getStepExecutions().stream().findFirst().orElseThrow();
    boolean resumed = explorer.getJobExecutions(execution.getJobInstance()).size() > 1;
    return new ImportReport(
        status(execution.getStatus()),
        step.getReadCount(),
        step.getWriteCount(),
        step.getSkipCount(),
        step.getCommitCount(),
        resumed,
        execution.getExitStatus().getExitDescription());
  }

  private Status status(BatchStatus batchStatus) {
    return switch (batchStatus) {
      case COMPLETED -> Status.COMPLETED;
      case STOPPED, STOPPING -> Status.STOPPED;
      default -> Status.FAILED;
    };
  }

  private ImportReport failure(ImportRequest request, Exception problem) {
    return problem instanceof JobInstanceAlreadyCompleteException
        ? ImportReport.alreadyComplete(request.runId())
        : new ImportReport(Status.FAILED, 0, 0, 0, 0, false, problem.toString());
  }
}
