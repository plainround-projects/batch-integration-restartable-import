package plainround.catalogimport.e2e;

import java.nio.file.Path;
import java.util.stream.Stream;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * The database of the end-to-end tests: `catalog_e2e` on the compose PostgreSQL, created with the
 * same SQL as `make db`.
 */
final class E2eDatabase {

  private static final String HOST = System.getenv().getOrDefault("DB_HOST", "localhost");
  private static final String PORT = System.getenv().getOrDefault("POSTGRES_PORT", "55531");
  private static final String USER = System.getenv().getOrDefault("DB_USER", "catalog");
  private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD", "");
  private static final String NAME = "catalog_e2e";
  private static boolean ready;

  private E2eDatabase() {}

  static synchronized String url() {
    if (!ready) {
      create();
      ready = true;
    }
    return "jdbc:postgresql://%s:%s/%s".formatted(HOST, PORT, NAME);
  }

  static String user() {
    return USER;
  }

  static String password() {
    return PASSWORD;
  }

  static void reset() {
    JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url(), USER, PASSWORD));
    jdbc.execute("TRUNCATE product, import_reject");
    jdbc.execute(
        "TRUNCATE batch_step_execution_context, batch_job_execution_context, batch_step_execution, "
            + "batch_job_execution_params, batch_job_execution, batch_job_instance");
  }

  private static void create() {
    JdbcTemplate admin =
        new JdbcTemplate(
            new DriverManagerDataSource(
                "jdbc:postgresql://%s:%s/postgres".formatted(HOST, PORT), USER, PASSWORD));
    if (admin.queryForList("SELECT 1 FROM pg_database WHERE datname = ?", NAME).isEmpty()) {
      admin.execute("CREATE DATABASE " + NAME);
    }
    ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
    Stream.of(
            "sql/001_spring_batch_schema.sql",
            "sql/002_catalog_schema.sql",
            "sql/migrations/001_import_reject.sql")
        .map(file -> new FileSystemResource(Path.of(file)))
        .forEach(populator::addScript);
    populator.execute(
        new DriverManagerDataSource(
            "jdbc:postgresql://%s:%s/%s".formatted(HOST, PORT, NAME), USER, PASSWORD));
  }
}
