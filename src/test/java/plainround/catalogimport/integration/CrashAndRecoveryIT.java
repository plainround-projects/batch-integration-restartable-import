package plainround.catalogimport.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import plainround.catalogimport.application.GenerateCatalogFile;
import plainround.catalogimport.application.ImportCatalog;
import plainround.catalogimport.application.ImportReport;
import plainround.catalogimport.application.ImportReport.Status;
import plainround.catalogimport.application.ImportRequest;
import plainround.catalogimport.domain.ProductRow;
import plainround.catalogimport.infrastructure.batch.CatalogJobConfiguration;
import plainround.catalogimport.infrastructure.batch.StaleExecutionRecovery;
import plainround.catalogimport.interfaces.CatalogImportApplication;

/**
 * Crashes and recovery. The in-process crash is a writer that throws on the seventh chunk, which
 * leaves the repository as a failed run; the kill -9 version is `make crash-test`. A write failure
 * that can never succeed is not retried.
 */
@Tag("integration")
@SpringBootTest(
    classes = {CatalogImportApplication.class, CrashAndRecoveryIT.Switches.class},
    args = "--command=none")
class CrashAndRecoveryIT {

  static final AtomicInteger CRASH_AT_CALL = new AtomicInteger(0);
  static final AtomicInteger WRITE_CALLS = new AtomicInteger();

  @TestConfiguration
  static class Switches {
    @Bean
    @Primary
    ItemWriter<ProductRow> countingWriter(JdbcBatchItemWriter<ProductRow> upsertWriter) {
      return chunk -> {
        int call = WRITE_CALLS.incrementAndGet();
        if (call == CRASH_AT_CALL.get()) {
          throw new IllegalStateException("simulated crash");
        }
        upsertWriter.write(chunk);
      };
    }
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", TestDatabase::url);
    registry.add("spring.datasource.username", TestDatabase::user);
    registry.add("spring.datasource.password", TestDatabase::password);
    registry.add("import.chunk-size", () -> "500");
  }

  @TempDir Path folder;
  @Autowired ImportCatalog importCatalog;
  @Autowired JobExplorer explorer;
  @Autowired JobRepository repository;
  Path file;

  @BeforeEach
  void clean() {
    TestDatabase.reset();
    CRASH_AT_CALL.set(0);
    WRITE_CALLS.set(0);
    file = folder.resolve("products.csv");
    new GenerateCatalogFile().write(file, 20_000, 2_000, 7);
  }

  private ImportReport run(String runId) {
    return importCatalog.run(new ImportRequest(file, runId, folder.resolve("rejects.txt")));
  }

  private List<String> steps() {
    return TestDatabase.jdbc()
        .queryForList(
            "SELECT status || ':' || read_count || ':' || write_count || ':' || skip_count FROM step_summary "
                + "ORDER BY step_execution_id",
            String.class);
  }

  @Test
  void a_crash_then_a_restart_with_the_same_parameters_resumes_and_ends_with_one_row_per_sku() {
    CRASH_AT_CALL.set(7);
    ImportReport crashed = run("crashy");
    assertThat(crashed.status()).isEqualTo(Status.FAILED);
    long afterCrash =
        TestDatabase.jdbc().queryForObject("SELECT count(*) FROM product", Long.class);
    assertThat(afterCrash)
        .isBetween(2_900L, 3_100L); // six committed chunks of 500, minus the planted skips

    CRASH_AT_CALL.set(0);
    ImportReport resumed = run("crashy");
    assertThat(resumed.status()).isEqualTo(Status.COMPLETED);
    assertThat(resumed.resumed()).isTrue();
    assertThat(resumed.read()).isLessThan(20_000);
    assertThat(TestDatabase.jdbc().queryForObject("SELECT count(*) FROM product", Long.class))
        .isEqualTo(19_990);
    assertThat(
            TestDatabase.jdbc()
                .queryForObject("SELECT count(DISTINCT sku) FROM product", Long.class))
        .isEqualTo(19_990);
    assertThat(TestDatabase.jdbc().queryForObject("SELECT count(*) FROM import_reject", Long.class))
        .isEqualTo(10);
    assertThat(steps()).hasSize(2).first().asString().startsWith("FAILED:");
  }

  @Test
  void the_restart_reads_only_the_lines_after_the_last_committed_chunk() {
    CRASH_AT_CALL.set(5);
    run("crashy");
    long committed = savedReadCount();
    assertThat(committed).isEqualTo(2_000);
    CRASH_AT_CALL.set(0);
    ImportReport resumed = run("crashy");
    assertThat(resumed.read()).isEqualTo(20_000 - committed);
  }

  /**
   * The reader's saved position, read through the job explorer (the context is stored serialized).
   */
  private long savedReadCount() {
    var ids =
        TestDatabase.jdbc()
            .queryForMap(
                "SELECT job_execution_id, step_execution_id FROM batch_step_execution "
                    + "ORDER BY step_execution_id LIMIT 1");
    var step =
        explorer.getStepExecution(
            ((Number) ids.get("job_execution_id")).longValue(),
            ((Number) ids.get("step_execution_id")).longValue());
    return step.getExecutionContext().getInt("productReader.read.count");
  }

  @Test
  void an_execution_left_running_by_a_dead_process_is_marked_failed_and_keeps_its_counts() {
    JobExecution orphan = orphan("dead-run");
    int recovered = new StaleExecutionRecovery(explorer, repository).recoverCrashedRuns();
    assertThat(recovered).isEqualTo(1);
    assertThat(explorer.getJobExecution(orphan.getId()).getStatus()).isEqualTo(BatchStatus.FAILED);
    assertThat(explorer.getJobExecution(orphan.getId()).getExitStatus().getExitDescription())
        .contains("process died");
  }

  @Test
  void recovery_with_nothing_running_does_nothing() {
    assertThat(new StaleExecutionRecovery(explorer, repository).recoverCrashedRuns()).isZero();
  }

  @Test
  void the_use_case_recovers_an_orphan_before_launching_so_the_same_parameters_can_restart() {
    orphan("dead-run");
    ImportReport report = run("dead-run");
    assertThat(report.status()).isEqualTo(Status.COMPLETED);
  }

  private JobExecution orphan(String runId) {
    var parameters =
        new JobParametersBuilder()
            .addString("file", file.toString())
            .addString("run.id", runId)
            .toJobParameters();
    try {
      JobExecution execution =
          repository.createJobExecution(CatalogJobConfiguration.JOB_NAME, parameters);
      execution.setStatus(BatchStatus.STARTED);
      repository.update(execution);
      return execution;
    } catch (Exception problem) {
      throw new IllegalStateException(problem);
    }
  }

  @Test
  void a_failure_that_can_never_succeed_is_written_once_and_not_retried() throws Exception {
    String tooLong = "x".repeat(200);
    java.nio.file.Files.writeString(
        file, "sku,name,category,price\nSKU-000001," + tooLong + ",books,10.00\n");
    ImportReport report = run("never");
    assertThat(report.status()).isEqualTo(Status.FAILED);
    assertThat(report.message()).contains(DataIntegrityViolationException.class.getName());
    assertThat(WRITE_CALLS).hasValue(1);
  }
}
