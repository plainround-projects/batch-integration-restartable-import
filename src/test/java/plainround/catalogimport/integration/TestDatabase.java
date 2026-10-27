package plainround.catalogimport.integration;

import java.nio.file.Path;
import java.util.stream.Stream;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * A database of its own ({@code catalog_test}) on the compose PostgreSQL, created on first use and
 * given the same SQL files as {@code make db}. The tests never touch the database {@code make run}
 * fills.
 */
final class TestDatabase {

  static final String NAME = "catalog_test";
  private static final String HOST = System.getenv().getOrDefault("DB_HOST", "localhost");
  private static final String PORT = System.getenv().getOrDefault("POSTGRES_PORT", "55531");
  private static final String USER = System.getenv().getOrDefault("DB_USER", "catalog");
  private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD", "");
  private static boolean ready;

  private TestDatabase() {}

  static String url() {
    prepare();
    return "jdbc:postgresql://%s:%s/%s".formatted(HOST, PORT, NAME);
  }

  static JdbcTemplate jdbc() {
    return new JdbcTemplate(new DriverManagerDataSource(url(), USER, PASSWORD));
  }

  static String user() {
    return USER;
  }

  static String password() {
    return PASSWORD;
  }

  /** Empties the product table, the reject record and every remembered run. */
  static void reset() {
    jdbc().update("TRUNCATE product, import_reject");
    jdbc()
        .execute(
            "TRUNCATE batch_step_execution_context, batch_job_execution_context, batch_step_execution, "
                + "batch_job_execution_params, batch_job_execution, batch_job_instance");
  }

  private static synchronized void prepare() {
    if (ready) {
      return;
    }
    JdbcTemplate admin =
        new JdbcTemplate(
            new DriverManagerDataSource(
                "jdbc:postgresql://%s:%s/postgres".formatted(HOST, PORT), USER, PASSWORD));
    boolean exists =
        !admin.queryForList("SELECT 1 FROM pg_database WHERE datname = ?", NAME).isEmpty();
    if (!exists) {
      admin.execute("CREATE DATABASE " + NAME);
    }
    apply();
    ready = true;
  }

  private static void apply() {
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
