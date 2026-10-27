package plainround.catalogimport.interfaces;

import javax.sql.DataSource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import plainround.catalogimport.application.GenerateCatalogFile;
import plainround.catalogimport.application.ImportCatalog;
import plainround.catalogimport.infrastructure.batch.SpringBatchImportJob;
import plainround.catalogimport.infrastructure.batch.StaleExecutionRecovery;
import plainround.catalogimport.infrastructure.jdbc.AdvisoryRunLock;
import plainround.catalogimport.infrastructure.jdbc.JdbcRejectLog;
import plainround.catalogimport.infrastructure.jdbc.StepExecutionTable;

/** The composition root: the use cases get their adapters here and nowhere else. */
@Configuration
public class ApplicationWiring {

  @Bean
  ImportCatalog importCatalog(
      DataSource dataSource,
      JobExplorer explorer,
      JobRepository repository,
      JobLauncher launcher,
      Job catalogImport,
      JdbcRejectLog rejects) {
    return new ImportCatalog(
        new AdvisoryRunLock(dataSource),
        new StaleExecutionRecovery(explorer, repository),
        new SpringBatchImportJob(launcher, catalogImport, explorer),
        rejects);
  }

  @Bean
  GenerateCatalogFile generateCatalogFile() {
    return new GenerateCatalogFile();
  }

  @Bean
  StepExecutionTable stepExecutionTable(JdbcTemplate jdbc) {
    return new StepExecutionTable(jdbc);
  }
}
