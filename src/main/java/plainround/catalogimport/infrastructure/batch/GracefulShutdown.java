package plainround.catalogimport.infrastructure.batch;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.StepExecution;

/**
 * On SIGTERM or SIGINT, asks the running step to stop after the chunk in flight and waits for it.
 * The chunk commits, the position is saved, and the next start resumes cleanly. {@code kill -9}
 * cannot be handled; that is the point of the crash test.
 */
public class GracefulShutdown implements JobExecutionListener {

  private static final long WAIT_SECONDS = 60;
  private volatile CompletableFuture<Void> finished = new CompletableFuture<>();

  @Override
  public void beforeJob(JobExecution execution) {
    finished = new CompletableFuture<>();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> stopAndWait(execution), "graceful-stop"));
  }

  @Override
  public void afterJob(JobExecution execution) {
    finished.complete(null);
  }

  private void stopAndWait(JobExecution execution) {
    execution.getStepExecutions().forEach(StepExecution::setTerminateOnly);
    finished.orTimeout(WAIT_SECONDS, TimeUnit.SECONDS).exceptionally(timeout -> null).join();
  }
}
