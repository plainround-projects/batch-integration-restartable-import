package plainround.catalogimport.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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

/**
 * Skip limits and retries, each with its own settings, so each runs in its own application context.
 */
@Tag("integration")
class FaultToleranceIT {

  abstract static class Base {
    @TempDir Path folder;
    @Autowired ImportCatalog importCatalog;
    Path file;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
      registry.add("spring.datasource.url", TestDatabase::url);
      registry.add("spring.datasource.username", TestDatabase::user);
      registry.add("spring.datasource.password", TestDatabase::password);
    }

    @BeforeEach
    void clean() {
      TestDatabase.reset();
      file = folder.resolve("products.csv");
    }

    ImportReport run(String runId) {
      return importCatalog.run(new ImportRequest(file, runId, folder.resolve("rejects.txt")));
    }

    long count(String sql) {
      return TestDatabase.jdbc().queryForObject(sql, Long.class);
    }
  }

  @Nested
  @SpringBootTest(
      classes = CatalogImportApplication.class,
      args = "--command=none",
      properties = "import.skip-limit=5")
  class SkipLimit extends Base {

    @Test
    void more_bad_rows_than_the_limit_fail_the_step_with_the_limit_in_the_message() {
      new GenerateCatalogFile().write(file, 2_000, 100, 7); // 20 bad rows, limit 5
      ImportReport report = run("too-many");
      assertThat(report.status()).isEqualTo(Status.FAILED);
      assertThat(report.message()).contains("Skip limit of '5' exceeded");
      assertThat(report.exitCode()).isEqualTo(1);
    }

    @Test
    void bad_rows_up_to_the_limit_are_skipped_and_the_step_completes() {
      new GenerateCatalogFile().write(file, 2_000, 400, 7); // 5 bad rows, limit 5
      assertThat(run("at-the-limit").status()).isEqualTo(Status.COMPLETED);
      assertThat(count("SELECT count(*) FROM import_reject")).isEqualTo(5);
    }
  }

  @Nested
  @SpringBootTest(
      classes = CatalogImportApplication.class,
      args = "--command=none",
      properties = {"import.transient-fault-every-nth-chunk=3", "import.chunk-size=200"})
  class TransientFaults extends Base {

    @Autowired
    List<org.springframework.batch.item.ItemWriter<plainround.catalogimport.domain.ProductRow>>
        writers;

    @Test
    void a_transient_write_failure_is_retried_so_the_job_completes_with_only_the_planted_skips() {
      new GenerateCatalogFile().write(file, 20_000, 2_000, 7);
      ImportReport report = run("flaky");
      assertThat(report.status()).isEqualTo(Status.COMPLETED);
      assertThat(report.skipped()).isEqualTo(10);
      assertThat(count("SELECT count(*) FROM product")).isEqualTo(19_990);
    }
  }
}
