package plainround.catalogimport.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
 * Lesson step 6 at full size: every fiftieth chunk fails once with a transient error, and the job
 * still ends right.
 */
@Tag("e2e")
@SpringBootTest(
    classes = CatalogImportApplication.class,
    args = "--command=none",
    properties = "import.transient-fault-every-nth-chunk=50")
class RetryScenarioE2E {

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", E2eDatabase::url);
    registry.add("spring.datasource.username", E2eDatabase::user);
    registry.add("spring.datasource.password", E2eDatabase::password);
  }

  @TempDir Path folder;
  @Autowired ImportCatalog importCatalog;
  @Autowired JdbcTemplate jdbc;

  @Test
  void a_transient_failure_every_fiftieth_chunk_costs_no_skip_and_no_row() {
    E2eDatabase.reset();
    Path file = folder.resolve("products.csv");
    new GenerateCatalogFile().write(file, 200_000, 2_000, 7);
    ImportReport report =
        importCatalog.run(new ImportRequest(file, "retry-1", folder.resolve("rejects.txt")));
    assertThat(report.status()).isEqualTo(Status.COMPLETED);
    assertThat(report.skipped()).isEqualTo(100);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM product", Long.class)).isEqualTo(199_900);
  }
}
