package plainround.catalogimport.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import plainround.catalogimport.application.GenerateCatalogFile;
import plainround.catalogimport.application.ImportCatalog;
import plainround.catalogimport.application.ImportReport;
import plainround.catalogimport.application.ImportReport.Status;
import plainround.catalogimport.application.ImportRequest;
import plainround.catalogimport.interfaces.CatalogImportApplication;

/** The job against a real PostgreSQL: the happy path, the reject record, and repeating the work. */
@Tag("integration")
@SpringBootTest(classes = CatalogImportApplication.class, args = "--command=none")
class CatalogImportIT {

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", TestDatabase::url);
    registry.add("spring.datasource.username", TestDatabase::user);
    registry.add("spring.datasource.password", TestDatabase::password);
    registry.add("import.chunk-size", () -> "500");
  }

  @TempDir Path folder;
  @Autowired ImportCatalog importCatalog;

  private Path file;
  private Path rejects;

  @BeforeEach
  void freshDatabaseAndFile() {
    TestDatabase.reset();
    file = folder.resolve("products.csv");
    rejects = folder.resolve("rejects.txt");
    new GenerateCatalogFile().write(file, 20_000, 2_000, 7);
  }

  private ImportReport run(String runId) {
    return importCatalog.run(new ImportRequest(file, runId, rejects));
  }

  private long products() {
    return TestDatabase.jdbc().queryForObject("SELECT count(*) FROM product", Long.class);
  }

  @Test
  void a_clean_run_loads_every_valid_row_skips_the_bad_ones_and_records_them() throws Exception {
    ImportReport report = run("first");
    assertThat(report.status()).isEqualTo(Status.COMPLETED);
    assertThat(report)
        .extracting("read", "written", "skipped")
        .containsExactly(20_000L, 19_990L, 10L);
    assertThat(products()).isEqualTo(19_990);
    List<String> lines = Files.readAllLines(rejects);
    assertThat(lines).hasSize(10);
    assertThat(lines.getFirst())
        .startsWith("line 2002: SKU-002000,weapons,")
        .endsWith("-> unknown category weapons");
  }

  @Test
  void the_job_repository_shows_one_completed_step_with_the_counts() {
    run("first");
    List<String> rows =
        TestDatabase.jdbc()
            .queryForList(
                "SELECT status || ' ' || read_count || ' ' || write_count || ' ' || skip_count FROM step_summary",
                String.class);
    assertThat(rows).containsExactly("COMPLETED 20000 19990 10");
  }

  @Test
  void the_same_file_under_a_new_run_id_leaves_one_row_per_sku_and_the_same_rejects()
      throws Exception {
    run("first");
    ImportReport again = run("second");
    assertThat(again.status()).isEqualTo(Status.COMPLETED);
    assertThat(products()).isEqualTo(19_990);
    assertThat(
            TestDatabase.jdbc()
                .queryForObject("SELECT count(DISTINCT sku) FROM product", Long.class))
        .isEqualTo(19_990);
    assertThat(Files.readAllLines(rejects)).hasSize(10);
  }

  @Test
  void the_same_run_id_after_completion_is_refused_and_changes_nothing() {
    run("first");
    ImportReport refused = run("first");
    assertThat(refused.status()).isEqualTo(Status.ALREADY_COMPLETE);
    assertThat(refused.exitCode()).isEqualTo(3);
    assertThat(products()).isEqualTo(19_990);
  }

  @Test
  void an_updated_price_in_the_file_is_applied_by_the_upsert_not_added_as_a_row() throws Exception {
    run("first");
    String edited = Files.readString(file).replace("SKU-000001,Product 1,", "SKU-000001,Renamed,");
    Files.writeString(file, edited);
    run("second");
    String name =
        TestDatabase.jdbc()
            .queryForObject("SELECT name FROM product WHERE sku = 'SKU-000001'", String.class);
    assertThat(name).isEqualTo("Renamed");
    assertThat(products()).isEqualTo(19_990);
  }

  @Test
  void a_second_process_cannot_take_the_lock_while_the_first_holds_it() {
    var lock =
        new plainround.catalogimport.infrastructure.jdbc.AdvisoryRunLock(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                TestDatabase.url(), TestDatabase.user(), TestDatabase.password()));
    var first = lock.tryAcquire();
    var second = lock.tryAcquire();
    assertThat(first.isError()).isFalse();
    assertThat(second.isError()).isTrue();
    first.ifSuccess(lease -> lease.close());
    var third = lock.tryAcquire();
    assertThat(third.isError()).isFalse();
    third.ifSuccess(lease -> lease.close());
  }
}
