package plainround.catalogimport.infrastructure.batch;

import java.time.LocalDateTime;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import plainround.catalogimport.application.CrashRecovery;

/**
 * After a {@code kill -9} the job repository still says STARTED, and Spring Batch will not start a
 * job instance with a running execution. This marks such executions (and their steps) FAILED,
 * keeping the counts of the last commit, so the same parameters restart from there. Call it only
 * while holding the run lock, which proves the process is dead.
 */
@Slf4j
@RequiredArgsConstructor
public class StaleExecutionRecovery implements CrashRecovery {

  private static final String NOTE = "process died; marked failed by the next start";

  private final JobExplorer explorer;
  private final JobRepository repository;

  @Override
  public int recoverCrashedRuns() {
    Set<JobExecution> running = explorer.findRunningJobExecutions(CatalogJobConfiguration.JOB_NAME);
    running.forEach(this::markFailed);
    return running.size();
  }

  private void markFailed(JobExecution execution) {
    execution.getStepExecutions().stream()
        .filter(step -> step.getStatus().isRunning())
        .forEach(this::fail);
    execution.setStatus(BatchStatus.FAILED);
    execution.setExitStatus(ExitStatus.FAILED.addExitDescription(NOTE));
    execution.setEndTime(LocalDateTime.now());
    repository.update(execution);
    log.warn("recovered crashed execution id={}", execution.getId());
  }

  private void fail(StepExecution step) {
    step.setStatus(BatchStatus.FAILED);
    step.setExitStatus(ExitStatus.FAILED.addExitDescription(NOTE));
    step.setEndTime(LocalDateTime.now());
    repository.update(step);
  }
}
