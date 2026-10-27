package plainround.catalogimport.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import plainround.catalogimport.domain.RejectedRow;
import plainround.catalogimport.infrastructure.jdbc.JdbcRejectLog;
import plainround.catalogimport.infrastructure.jdbc.StepExecutionTable;

@Tag("integration")
class JdbcRejectLogIT {

  @TempDir Path folder;
  private JdbcTemplate jdbc;
  private JdbcRejectLog log;

  @BeforeEach
  void clean() {
    TestDatabase.reset();
    jdbc = TestDatabase.jdbc();
    log = new JdbcRejectLog(jdbc);
  }

  @Test
  void recording_the_same_line_twice_leaves_one_record_with_the_latest_reason() {
    log.record(
        "a.csv",
        new RejectedRow(2002, "SKU-002000", "weapons", "87.12", "unknown category weapons"));
    log.record(
        "a.csv",
        new RejectedRow(
            2002, "SKU-002000", "weapons", "87.12", "unknown category weapons (again)"));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM import_reject", Long.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT reason FROM import_reject", String.class))
        .endsWith("(again)");
  }

  @Test
  void export_writes_one_line_per_reject_of_that_file_in_line_order() throws Exception {
    log.record("a.csv", new RejectedRow(4002, "BAD4000", "books", "64.5", "bad SKU format"));
    log.record(
        "a.csv",
        new RejectedRow(2002, "SKU-002000", "weapons", "87.12", "unknown category weapons"));
    log.record("b.csv", new RejectedRow(7, "X", "books", "1", "other file"));
    Path target = folder.resolve("rejects.txt");
    assertThat(log.export("a.csv", target)).isEqualTo(2);
    assertThat(Files.readAllLines(target))
        .containsExactly(
            "line 2002: SKU-002000,weapons,87.12 -> unknown category weapons",
            "line 4002: BAD4000,books,64.5 -> bad SKU format");
  }

  @Test
  void exporting_a_file_with_no_rejects_writes_an_empty_file() throws Exception {
    Path target = folder.resolve("rejects.txt");
    assertThat(log.export("clean.csv", target)).isZero();
    assertThat(Files.readString(target)).isEmpty();
  }

  @Test
  void the_step_table_reads_the_job_repository_and_counts_products() {
    StepExecutionTable table = new StepExecutionTable(jdbc);
    assertThat(table.lines()).isEmpty();
    assertThat(table.products()).isZero();
    assertThat(table.distinctSkus()).isZero();
    assertThat(StepExecutionTable.heading()).startsWith("step_name");
  }
}
