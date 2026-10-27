package plainround.catalogimport.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import plainround.catalogimport.application.ImportReport.Status;

class ImportCatalogTest {

  private final List<String> calls = new ArrayList<>();
  private final ImportRequest request =
      new ImportRequest(Path.of("p.csv"), "run-1", Path.of("rejects.txt"));

  private ImportReport completed() {
    return new ImportReport(Status.COMPLETED, 200000, 199900, 100, 201, false, "");
  }

  private ImportCatalog useCase(FakeRunLock lock, ImportJob job) {
    return new ImportCatalog(
        lock, () -> record("recover", 2), job, (file, target) -> record("export", 100));
  }

  private int record(String call, int result) {
    calls.add(call);
    return result;
  }

  @Test
  void it_recovers_then_runs_the_job_then_writes_the_reject_file_in_that_order() {
    ImportJob job =
        r -> {
          calls.add("launch");
          return completed();
        };
    ImportReport report = useCase(new FakeRunLock(true), job).run(request);
    assertThat(calls).containsExactly("recover", "launch", "export");
    assertThat(report.status()).isEqualTo(Status.COMPLETED);
  }

  @Test
  void a_held_lock_means_nothing_runs_and_the_report_says_blocked() {
    ImportReport report = useCase(new FakeRunLock(false), r -> completed()).run(request);
    assertThat(calls).isEmpty();
    assertThat(report.status()).isEqualTo(Status.BLOCKED);
    assertThat(report.message()).contains("held by pid 42");
    assertThat(report.exitCode()).isEqualTo(4);
  }

  @Test
  void the_lock_is_released_even_when_the_job_throws() {
    FakeRunLock lock = new FakeRunLock(true);
    ImportJob exploding =
        r -> {
          throw new IllegalStateException("database gone");
        };
    assertThatThrownBy(() -> useCase(lock, exploding).run(request)).hasMessage("database gone");
    assertThat(lock.released).hasValue(1);
  }

  @Test
  void a_blank_run_id_is_refused_up_front() {
    assertThatThrownBy(() -> new ImportRequest(Path.of("p.csv"), " ", Path.of("r.txt")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("run.id");
  }

  @Test
  void exit_codes_tell_the_outcomes_apart() {
    assertThat(completed().exitCode()).isZero();
    assertThat(ImportReport.alreadyComplete("r").exitCode()).isEqualTo(3);
    assertThat(ImportReport.alreadyComplete("r").message()).contains("new run.id");
    assertThat(new ImportReport(Status.FAILED, 0, 0, 0, 0, false, "x").exitCode()).isEqualTo(1);
    assertThat(new ImportReport(Status.STOPPED, 0, 0, 0, 0, false, "x").exitCode()).isEqualTo(1);
  }
}
