# JDBC and JdbcTemplate

## What it is

A **database** is a program that stores data and answers questions about it. **PostgreSQL** is one such program. To talk to it from Java you use **JDBC** (Java Database Connectivity): a standard set of Java interfaces for sending SQL text to a database and reading back the rows. The code that makes JDBC work with one specific database is a **driver** (here the PostgreSQL driver, `org.postgresql:postgresql`, which speaks PostgreSQL's network protocol).

Three terms: a **connection** is an open conversation with the database; opening one is slow (a network handshake and a login), so programs keep a **connection pool**, a set of already-open connections that code borrows and returns (here the pool is **HikariCP**, which Spring Boot configures for you). A **transaction** groups several changes so that they all happen or none does. A **prepared statement** is SQL with `?` placeholders; you pass the values separately, which is both faster and safe against SQL injection (an attack that hides SQL in a value).

Spring's **`JdbcTemplate`** wraps JDBC so you do not write the same repetitive code for every query: it borrows a connection, prepares the statement, runs it, maps rows, closes everything and returns the connection to the pool, even when something fails.

## Why this repository uses it

The import writes products and refused rows to PostgreSQL, Spring Batch keeps its bookkeeping there, and two small pieces talk to the database directly: the **advisory lock** that proves only one import runs, and the **reject log** that records every refused row.

## Where it appears

| Path | What it does with JDBC |
| --- | --- |
| `${J}/infrastructure/jdbc/AdvisoryRunLock.java` | Takes a PostgreSQL advisory lock on a dedicated connection. |
| `${J}/infrastructure/jdbc/JdbcRejectLog.java` | Writes and exports the refused rows. |
| `${J}/infrastructure/jdbc/StepExecutionTable.java` | Reads the job repository for the report. |
| `${J}/infrastructure/batch/CatalogJobConfiguration.java` | The batch writer's upsert. |
| `${J}/interfaces/ImportRunner.java` | The health query. |
| `src/main/resources/application.yml` | The URL, user, password and pool. |

## How it works underneath

A `jdbc:postgresql://host:port/database` **URL** tells the driver where the database is. When code calls `jdbcTemplate.queryForObject(sql, Long.class, args...)`, `JdbcTemplate` asks the pool for a connection, sends the SQL with the arguments as parameters, reads the single row, converts the first column to a `Long`, and returns the connection to the pool. If the pool has no free connection the call **waits** up to `connection-timeout` milliseconds and then fails with an error; that waiting is the "pool exhausted" problem that the lesson's step 4 shows.

A **cursor** reader (like `JdbcCursorItemReader`) keeps one connection busy for the whole read: the database remembers where the result set is and sends rows in groups of `fetchSize`. The connection is not returned to the pool until the reader is closed. So a reader holds one pooled connection while its worker also needs another one to write.

A **PostgreSQL advisory lock** is a lock that the database keeps for a number you choose; it has no meaning for any table. `pg_try_advisory_lock(n)` returns true and holds the lock if nobody else holds `n`, otherwise false without waiting. A **session-level** advisory lock belongs to the database connection: the server releases it when that connection ends, even if the client process was killed. That property makes it a correct "a live process is running" test.

## Walkthrough of this repository's use

### `AdvisoryRunLock.java`

```java
package plainround.catalogimport.infrastructure.jdbc;

import com.github.sviperll.result4j.Result;
import java.sql.Connection;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import plainround.catalogimport.application.RunLock;

/**
 * A PostgreSQL advisory lock on a connection held for the whole run. The server releases it when
 * that connection ends, so a process killed with {@code kill -9} cannot leave the lock behind, and
 * a held lock means a live process.
 */
@RequiredArgsConstructor
public class AdvisoryRunLock implements RunLock {

  static final long KEY = 7_203_001L;
  private static final String LOCK = "SELECT pg_try_advisory_lock(" + KEY + ")";
  private static final String UNLOCK = "SELECT pg_advisory_unlock(" + KEY + ")";

  private final DataSource dataSource;

```

The class implements the `RunLock` port. The constant `KEY` (7,203,001, written with underscores for readability) is the number of the lock; `LOCK` and `UNLOCK` are the two SQL texts built by joining strings (safe here because `KEY` is a constant, not user input). `DataSource` is the connection pool.

```java
  @Override
  public Result<Lease, String> tryAcquire() {
    Connection connection = DataSourceUtils.getConnection(dataSource);
    JdbcTemplate session = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    boolean held = Boolean.TRUE.equals(session.queryForObject(LOCK, Boolean.class));
    return held ? Result.success(() -> release(session, connection)) : refuse(connection);
  }

  private Result<Lease, String> refuse(Connection connection) {
    DataSourceUtils.releaseConnection(connection, dataSource);
    return Result.error("another import holds the lock; wait for it or stop it");
  }

  private void release(JdbcTemplate session, Connection connection) {
    session.queryForObject(UNLOCK, Boolean.class);
    DataSourceUtils.releaseConnection(connection, dataSource);
  }
}
```

`tryAcquire` borrows a connection from the pool (`DataSourceUtils.getConnection`) and wraps **that one connection** in a `SingleConnectionDataSource` so a `JdbcTemplate` uses it and no other: a session-level lock must be asked for and released on the same connection. It runs `pg_try_advisory_lock`. If the answer is true the method returns a success holding a **lease**, written as the lambda `() -> release(session, connection)`: closing the lease calls `release`, which runs `pg_advisory_unlock` and gives the connection back to the pool. If the answer is false, `refuse` gives the connection back at once and returns an error with the message `another import holds the lock; wait for it or stop it`. `Boolean.TRUE.equals(...)` handles a `null` answer safely. The connection stays checked out for the whole run, which the pool size (5) allows for.

### `JdbcRejectLog.java`

```java
package plainround.catalogimport.infrastructure.jdbc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import plainround.catalogimport.application.RejectLog;
import plainround.catalogimport.domain.RejectedRow;

/**
 * The record of refused rows, in the {@code import_reject} table.
 *
 * <p>{@link #record} runs inside the chunk's transaction and is an upsert on (file, line), so a
 * chunk that is processed again after a crash or a rollback leaves exactly one record per refused
 * line.
 */
@RequiredArgsConstructor
public class JdbcRejectLog implements RejectLog {

  private static final String UPSERT =
      """
      INSERT INTO import_reject (source_file, line_number, sku, category, price, reason)
      VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT (source_file, line_number) DO UPDATE
      SET sku = EXCLUDED.sku, category = EXCLUDED.category, price = EXCLUDED.price, reason = EXCLUDED.reason
      """;

  private final JdbcTemplate jdbc;

  /** Records one refused row of {@code sourceFile}. */
  public void record(String sourceFile, RejectedRow row) {
    jdbc.update(
        UPSERT, sourceFile, row.lineNumber(), row.sku(), row.category(), row.price(), row.reason());
  }

```

`UPSERT` inserts a refused row into `import_reject`, and if a record for the same file and line already exists it overwrites that record (`ON CONFLICT (source_file, line_number) DO UPDATE SET ...`). So a chunk that is processed again after a crash leaves exactly one record per refused line. `record` runs the statement with six `?` parameters filled in order from the arguments: `jdbc.update(sql, a, b, ...)`. Because it is called from the skip listener, it runs inside the chunk's transaction.

```java
  @Override
  public int export(String sourceFile, Path target) {
    List<String> lines = lines(sourceFile);
    write(target, lines);
    return lines.size();
  }

  private List<String> lines(String sourceFile) {
    return jdbc.query(
        "SELECT line_number, sku, category, price, reason FROM import_reject WHERE source_file = ? ORDER BY line_number",
        (rs, i) ->
            new RejectedRow(
                    rs.getInt("line_number"),
                    rs.getString("sku"),
                    rs.getString("category"),
                    rs.getString("price"),
                    rs.getString("reason"))
                .asLine(),
        sourceFile);
  }

  private void write(Path target, List<String> lines) {
    try {
      Files.write(target, lines);
    } catch (IOException problem) {
      throw new UncheckedIOException(problem);
    }
  }
}
```

`export` reads the records of one file in line order and writes them as text lines. `jdbc.query(sql, rowMapper, sourceFile)` runs a query and converts each row with the lambda `(rs, i) -> new RejectedRow(...).asLine()`; `rs` is the current row (a `ResultSet`) and `rs.getInt("line_number")` reads a column by name. `write` saves the lines with `Files.write` and wraps the checked `IOException` in `UncheckedIOException`.

### `StepExecutionTable.java`

```java
package plainround.catalogimport.infrastructure.jdbc;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

/** The job repository as the lesson reads it: one line per step execution with its counters. */
@RequiredArgsConstructor
public class StepExecutionTable {

  private final JdbcTemplate jdbc;

  /** One formatted line per step execution, oldest first. */
  public List<String> lines() {
    return jdbc.query(
        """
        SELECT step_name, status, read_count, write_count, skip_count, commit_count
        FROM step_summary ORDER BY step_execution_id
        """,
        (rs, i) ->
            "%-10s | %-9s | %10d | %11d | %10d | %12d"
                .formatted(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getLong(3),
                    rs.getLong(4),
                    rs.getLong(5),
                    rs.getLong(6)));
  }

```

`lines()` queries the view `step_summary` (the PostgreSQL note) and formats each row as one table line with `"%-10s | %-9s | %10d | %11d | %10d | %12d"`: `%-10s` is text padded to 10 characters, left-aligned; `%10d` is a number padded to 10, right-aligned. The text block holds the SQL.

```java
  /** The heading that matches {@link #lines()}. */
  public static String heading() {
    return "step_name  | status    | read_count | write_count | skip_count | commit_count";
  }

  /** The number of rows in the product table, for the end-state check. */
  public long products() {
    return jdbc.queryForObject("SELECT count(*) FROM product", Long.class);
  }

  /** The number of distinct SKUs, which must equal {@link #products()}. */
  public long distinctSkus() {
    return jdbc.queryForObject("SELECT count(DISTINCT sku) FROM product", Long.class);
  }
}
```

`heading()` is the matching header line. `products()` and `distinctSkus()` count the rows of the product table and its distinct SKUs; after a good import they must be equal, which proves there are no duplicates.

### The writer and the health query

```java
  @Bean
  JdbcBatchItemWriter<ProductRow> upsertWriter(DataSource dataSource) {
    return new JdbcBatchItemWriterBuilder<ProductRow>()
        .dataSource(dataSource)
        .sql(UPSERT)
        .itemSqlParameterSourceProvider(CatalogJobConfiguration::parameters)
        .assertUpdates(false)
        .build();
  }
```

The batch writer: the upsert SQL with named parameters, the data source, and the function that turns an item into parameters.

```java
  private int healthCommand(ApplicationArguments args) {
    Long products = jdbc.queryForObject("SELECT count(*) FROM product", Long.class);
    System.out.println("ok database reachable, " + products + " products");
    return 0;
  }
```

The health query counts the products.

## Try it yourself

1. See the lock in the database while an import runs: in one terminal `make run`, in another `docker compose exec -T postgres psql -U catalog -d catalog -c "select locktype, objid, granted from pg_locks where locktype = 'advisory'"`. Expect one row with `objid` 7203001.
2. Try to start a second import at once: `java -jar target/restartable-import-0.1.0.jar --command=import --run.id=other` while the first runs prints `BLOCKED ... another import holds the lock` and exits with 4.
3. See the records of refused rows: `docker compose exec -T postgres psql -U catalog -d catalog -c "select line_number, reason from import_reject order by line_number limit 5"`.
4. See the report table: `make summary`.
5. Check the pool setting: change `maximum-pool-size` to `1` in `application.yml`, rebuild and run; the import cannot get a second connection and fails with a timeout after 20 seconds. Undo it.

## Mistakes people make

- **Building SQL by joining strings with user values.** That is SQL injection. Always pass values as `?` parameters, as this repository does.
- **Leaking connections.** Borrowed and never returned connections empty the pool. Use `JdbcTemplate` (or a try-with-resources block) so they are always returned.
- **A pool smaller than the number of threads that hold connections.** Workers wait on each other; see the Spring Batch note.
- **Reading a whole huge table into memory.** Use a cursor with a `fetchSize`, as the readers here do.
- **`null` from `queryForObject`.** A query such as `SELECT MAX(id)` over an empty table returns SQL `NULL`; the Java side gets `null`, so the code checks for it.

## Check yourself

1. Why must lock and unlock use the same connection? *A session-level advisory lock belongs to the connection that took it.*
2. What happens to the lock if the process is killed? *The server releases it when the connection ends.*
3. What does `ON CONFLICT (source_file, line_number) DO UPDATE` give the reject log? *One record per refused line, however often the chunk is processed.*
4. Why is `record` called inside the chunk's transaction? *So the record is saved or rolled back together with the chunk.*
5. What do `products()` and `distinctSkus()` prove together? *No duplicate SKUs.*

## Further reading

- Spring Framework data access with JDBC (JdbcTemplate): https://docs.spring.io/spring-framework/reference/data-access/jdbc.html
- PostgreSQL JDBC driver documentation: https://jdbc.postgresql.org/documentation/
- HikariCP configuration: https://github.com/brettwooldridge/HikariCP#gear-configuration-knobs-baby
