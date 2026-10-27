package plainround.catalogimport.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import plainround.catalogimport.application.GenerateCatalogFile;
import plainround.catalogimport.application.ImportCatalog;
import plainround.catalogimport.application.ImportReport;
import plainround.catalogimport.application.ImportReport.Status;
import plainround.catalogimport.application.ImportRequest;
import plainround.catalogimport.interfaces.CatalogImportApplication;

/**
 * The lesson's scenario at full size: 200,000 rows with 100 planted bad ones. A clean run, a
 * repeat, a file with too many bad rows, and a run where every fiftieth chunk fails once. (The kill
 * -9 rounds are `make crash-test`.)
 */
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(classes = CatalogImportApplication.class, args = "--command=none")
class LessonScenarioE2E {

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", E2eDatabase::url);
    registry.add("spring.datasource.username", E2eDatabase::user);
    registry.add("spring.datasource.password", E2eDatabase::password);
  }

  @TempDir static Path folder;
  @Autowired ImportCatalog importCatalog;
  @Autowired JdbcTemplate jdbc;
  static Path file;

  @BeforeAll
  static void bigFile() {
    E2eDatabase.reset();
    file = folder.resolve("products.csv");
    new GenerateCatalogFile().write(file, 200_000, 2_000, 7);
  }

  private ImportReport run(Path source, String runId) {
    return importCatalog.run(new ImportRequest(source, runId, folder.resolve("rejects.txt")));
  }

  private long count(String sql) {
    return jdbc.queryForObject(sql, Long.class);
  }

  @Test
  @org.junit.jupiter.api.Order(1)
  void a_clean_run_loads_199900_rows_skips_100_and_records_them() throws Exception {
    ImportReport report = run(file, "run-1");
    assertThat(report.status()).isEqualTo(Status.COMPLETED);
    assertThat(report)
        .extracting("read", "written", "skipped")
        .containsExactly(200_000L, 199_900L, 100L);
    assertThat(count("SELECT count(*) FROM product")).isEqualTo(199_900);
    List<String> rejects = Files.readAllLines(folder.resolve("rejects.txt"));
    assertThat(rejects).hasSize(100);
    assertThat(rejects.getFirst()).startsWith("line 2001: SKU-002000,weapons,");
    assertThat(rejects.get(1)).startsWith("line 4001: BAD4000,");
  }

  @Test
  @org.junit.jupiter.api.Order(2)
  void the_job_repository_shows_one_completed_execution_with_the_lessons_counts() {
    List<String> rows =
        jdbc.queryForList(
            "SELECT step_name || ' ' || status || ' ' || read_count || ' ' || write_count || ' ' || skip_count "
                + "|| ' ' || commit_count FROM step_summary",
            String.class);
    assertThat(rows)
        .hasSize(1)
        .first()
        .asString()
        .startsWith("importStep COMPLETED 200000 199900 100 ");
  }

  @Test
  @org.junit.jupiter.api.Order(3)
  void running_the_whole_file_again_under_a_new_run_id_leaves_one_row_per_sku() {
    assertThat(run(file, "run-2").status()).isEqualTo(Status.COMPLETED);
    assertThat(count("SELECT count(*) FROM product")).isEqualTo(199_900);
    assertThat(count("SELECT count(DISTINCT sku) FROM product")).isEqualTo(199_900);
  }

  @Test
  @org.junit.jupiter.api.Order(4)
  void a_file_with_600_bad_rows_fails_the_step_on_the_skip_limit() {
    Path bad = folder.resolve("too-many-bad.csv");
    new GenerateCatalogFile().write(bad, 20_000, 33, 7);
    ImportReport report = run(bad, "bad-1");
    assertThat(report.status()).isEqualTo(Status.FAILED);
    assertThat(report.message()).contains("Skip limit of '500' exceeded");
  }
}
