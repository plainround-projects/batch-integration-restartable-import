# JUnit 5 (and the test files)

## What it is

A **test** is a small program that checks that another program behaves as promised. **JUnit 5** (its modern part is called **Jupiter**) is the standard Java tool for writing and running tests. A test is an ordinary method with the annotation `@Test`; JUnit finds every such method, runs each one, and reports which passed and which failed. A test **fails** when it throws an exception, which is what a failed assertion does.

Words you will meet: a **unit test** checks one small piece (a single class) with no database or network; an **integration test** checks several real parts together (here the job and a real PostgreSQL); an **end-to-end test** (e2e) checks a whole scenario the way a user would run it. A **fixture** is the data and setup a test needs. A **tag** (`@Tag("integration")`) is a label used to include or exclude groups of tests.

## Why this repository uses it

The lesson's promises are claims about behavior: a bad row is skipped and recorded, a restart resumes where it left off, the same file twice leaves one row per SKU, a transient failure is retried but a constraint violation is not. Each claim is a test. Unit tests guard the pure rules; integration tests run the real job on a real PostgreSQL; two end-to-end tests run the lesson's scenario at full size (200,000 rows). The `kill -9` rounds are a script (`make crash-test`), because a test cannot kill its own JVM.

## Where it appears

| Path | What it tests |
| --- | --- |
| `${T}/domain/ProductRulesTest.java` | The validation rules and price parsing. |
| `${T}/domain/RejectedRowTest.java` | How a refused row reads, and the exception. |
| `${T}/application/ImportCatalogTest.java`, `FakeRunLock.java` | The use case with fakes. |
| `${T}/application/GenerateCatalogFileTest.java` | The sample file generator. |
| `${T}/infrastructure/BatchComponentsTest.java` | Line mapper, processor, fault injector, settings. |
| `${T}/integration/TestDatabase.java` | A separate database for the integration tests. |
| `${T}/integration/CatalogImportIT.java` | The job on PostgreSQL. |
| `${T}/integration/FaultToleranceIT.java` | Skip limit and retry. |
| `${T}/integration/CrashAndRecoveryIT.java` | Crash, restart, recovery. |
| `${T}/integration/JdbcRejectLogIT.java` | The reject log and the step table. |
| `${T}/e2e/E2eDatabase.java`, `LessonScenarioE2E.java`, `RetryScenarioE2E.java` | The lesson at full size. |
| `${T}/architecture/ArchitectureTest.java` | The dependency rule (ArchUnit note). |

## How it works underneath

Maven runs tests through two plug-ins. **Surefire** runs unit tests during `mvn test`; its configuration here excludes the tags `integration` and `e2e`, so unit tests need no services. **Failsafe** runs the integration and end-to-end tests during `failsafe:integration-test` and `failsafe:verify`; it only picks up classes whose names end in `IT` or `E2E` (the `includes` in `pom.xml`) and only the tags named by the property `failsafe.groups`.

For each test class JUnit creates a new instance, so tests cannot leak state through instance fields. Lifecycle annotations run code around the tests: `@BeforeAll` once before all tests of a class (the method must be `static`), `@BeforeEach` before every test, `@AfterAll` and `@AfterEach` the same after. `@TempDir` gives the test a fresh empty folder that JUnit deletes afterwards. `@ParameterizedTest` runs one test method many times with different inputs from `@CsvSource` (rows of comma-separated values) or `@ValueSource` (a list of values). `@TestMethodOrder` with `@Order(n)` forces the order of test methods, used when one test builds on the state left by the previous one (the end-to-end scenario).

Two more pieces appear here. **`@SpringBootTest`** starts the real Spring application inside the test (so the real beans are injected with `@Autowired`), and **`@DynamicPropertySource`** lets the test choose settings (here the database address) before the application starts. A **nested test class** (`@Nested`) is an inner class whose tests share an outer class but get their own setup, here a different set of settings per context.

## Walkthrough of this repository's use

### Surefire and Failsafe in `pom.xml`

```xml
      <!-- Unit tests run on every build; tests tagged integration or e2e need the services of docker-compose.yml. -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <configuration>
          <excludedGroups>integration,e2e</excludedGroups>
          <argLine>@{argLine} -XX:+EnableDynamicAgentLoading</argLine>
        </configuration>
      </plugin>
```

The comment says it plainly: unit tests run on every build, tests tagged `integration` or `e2e` need the services of the compose file. `maven-surefire-plugin` runs the unit tests; `<excludedGroups>integration,e2e</excludedGroups>` leaves out every test with one of those tags. `<argLine>@{argLine} -XX:+EnableDynamicAgentLoading</argLine>` is the option string for the test JVM: `@{argLine}` is what JaCoCo added (the coverage agent), and the flag lets an agent attach on newer JDKs without a warning.

```xml
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-failsafe-plugin</artifactId>
        <configuration>
          <includes>
            <include>**/*IT.java</include>
            <include>**/*E2E.java</include>
          </includes>
          <groups>${failsafe.groups}</groups>
          <argLine>@{argLine} -XX:+EnableDynamicAgentLoading</argLine>
        </configuration>
        <executions>
          <execution>
            <goals>
              <goal>integration-test</goal>
              <goal>verify</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
```

`maven-failsafe-plugin` runs the slower tests. `<includes>` names the classes it may pick up: those ending in `IT` or `E2E` (without this, Failsafe's default patterns would miss the `E2E` classes and they would never run; that was a real bug in this repository). `<groups>${failsafe.groups}</groups>` runs only the tags in the property. The two goals: `integration-test` runs the tests and `verify` fails the build if any failed.

### `ProductRulesTest.java`: the rules, with Instancio data

```java
package plainround.catalogimport.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;

import java.math.BigDecimal;
import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

class ProductRulesTest {

  private String reasonOf(ProductRow row) {
    return ProductRules.validate(row).map(accepted -> "valid").recoverError(Rejection::reason);
  }

  private ProductRow valid() {
    return Instancio.of(ProductRow.class)
        .set(field(ProductRow::sku), "SKU-000123")
        .set(field(ProductRow::category), "books")
        .set(field(ProductRow::price), new BigDecimal("12.50"))
        .create();
  }

  @Test
  void a_valid_row_comes_back_unchanged() {
    ProductRow row = valid();
    assertThat(ProductRules.validate(row).discardError()).contains(row);
  }

```

Lines 3 and 4 are **static imports** so the test can write `assertThat(...)` and `field(...)`. `reasonOf` turns a row into the reason text or `"valid"`: `validate(row).map(accepted -> "valid").recoverError(Rejection::reason)` (a success becomes the text `valid`; an error becomes its reason; after `recoverError` both are `String`). `valid()` builds a complete row with Instancio (see its note): random line number and name, but the fields the rules look at are fixed with `.set(field(ProductRow::sku), "SKU-000123")` and likewise for the category and the price. The first test asserts that a good row comes back unchanged: `discardError()` turns the success into an `Optional` holding the row, and `contains(row)` checks it.

```java
  @ParameterizedTest
  @CsvSource({
    "0,price must be above zero",
    "-5,price must be above zero",
    "0.00,price must be above zero"
  })
  void a_price_that_is_not_above_zero_is_refused(String price, String reason) {
    ProductRow row = withPrice(new BigDecimal(price));
    assertThat(reasonOf(row)).isEqualTo(reason);
  }

  private ProductRow withPrice(BigDecimal price) {
    ProductRow base = valid();
    return new ProductRow(base.lineNumber(), base.sku(), base.name(), base.category(), price);
  }

  @Test
  void a_missing_price_is_refused_like_a_zero_price() {
    assertThat(reasonOf(withPrice(null))).isEqualTo("price must be above zero");
  }

  @Test
  void an_unknown_category_is_refused_and_named() {
    ProductRow base = valid();
    ProductRow row =
        new ProductRow(base.lineNumber(), base.sku(), base.name(), "weapons", base.price());
    assertThat(reasonOf(row)).isEqualTo("unknown category weapons");
  }

  @ParameterizedTest
  @CsvSource({"BAD4000", "SKU-12345", "sku-123456", "SKU-1234567", "SKU-12A456"})
  void a_sku_in_the_wrong_format_is_refused(String sku) {
    ProductRow base = valid();
    ProductRow row =
        new ProductRow(base.lineNumber(), sku, base.name(), base.category(), base.price());
    assertThat(reasonOf(row)).isEqualTo("bad SKU format");
  }

  @Test
  void a_row_with_several_problems_reports_the_first_rule_in_the_lessons_order() {
    ProductRow row = new ProductRow(2, "BAD1", "n", "weapons", BigDecimal.ZERO);
    assertThat(reasonOf(row)).isEqualTo("price must be above zero");
  }

```

`@ParameterizedTest` with `@CsvSource` runs the price test three times (prices `0`, `-5`, `0.00`); the helper `withPrice` copies a valid row with a different price (a record cannot be changed, so a new one is built). A missing price (`null`) is refused with the same reason. An unknown category is named in the reason. Five wrong SKUs (`BAD4000`, too few digits, lower case, too many digits, a letter among the digits) all give `bad SKU format`. The test named for "the first rule in the lesson's order" builds a row with three problems and asserts that only the first rule's reason (the price) is reported, which pins the lesson's rule order.

```java
  @ParameterizedTest
  @NullAndEmptySource
  void a_missing_sku_is_refused(String sku) {
    ProductRow base = valid();
    ProductRow row =
        new ProductRow(base.lineNumber(), sku, base.name(), base.category(), base.price());
    assertThat(ProductRules.validate(row).isError()).isTrue();
  }

  @ParameterizedTest
  @CsvSource({"87.12,87.12", "64.5,64.5", "0,0", "-5,-5", "' 3.10 ',3.10"})
  void a_price_field_is_parsed_exactly(String field, String expected) {
    assertThat(ProductRules.parsePrice(field)).isEqualByComparingTo(new BigDecimal(expected));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @CsvSource({"abc", "'1,5'", "1e3", "--2"})
  void anything_else_in_the_price_field_is_no_price(String field) {
    assertThat(ProductRules.parsePrice(field)).isNull();
  }

  @Test
  void the_categories_are_the_lessons_five() {
    assertThat(ProductRules.categories())
        .containsExactlyInAnyOrder("books", "toys", "garden", "kitchen", "sport");
  }
}
```

`@NullAndEmptySource` feeds `null` and `""` to the SKU test. The price-parsing tests list inputs and expected values: `"' 3.10 '"` shows that surrounding spaces are tolerated (the single quotes keep the spaces in CSV text); `isEqualByComparingTo` compares decimals by value (`3.10` equals `3.1`). The failing inputs (`abc`, `1,5`, `1e3`, `--2`, empty, null) must all parse to `null`. The last test pins the five categories with `containsExactlyInAnyOrder`.

### `RejectedRowTest.java`

```java
package plainround.catalogimport.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RejectedRowTest {

  @Test
  void a_reject_reads_like_the_lessons_reject_file() {
    ProductRow row =
        new ProductRow(2002, "SKU-002000", "Product 2000", "weapons", new BigDecimal("87.12"));
    assertThat(RejectedRow.of(row, "unknown category weapons").asLine())
        .isEqualTo("line 2002: SKU-002000,weapons,87.12 -> unknown category weapons");
  }

  @Test
  void a_row_without_a_price_shows_an_empty_price() {
    ProductRow row = new ProductRow(7, "SKU-000001", "n", "books", null);
    assertThat(RejectedRow.of(row, "price must be above zero").asLine())
        .isEqualTo("line 7: SKU-000001,books, -> price must be above zero");
  }

  @Test
  void the_exception_carries_the_row_and_the_reason() {
    ProductRow row = new ProductRow(9, "BAD9", "n", "books", BigDecimal.ONE);
    InvalidProductException error =
        new InvalidProductException(row, new Rejection("bad SKU format"));
    assertThat(error).hasMessage("bad SKU format");
    assertThat(error.getRow()).isEqualTo(row);
  }
}
```

Three small tests: a refused row reads like the lesson's reject line; a row without a price shows an empty price field (`line 7: SKU-000001,books, -> price must be above zero`); the exception carries both the row and the reason (`hasMessage`, `getRow()`).

### `ImportCatalogTest.java` and `FakeRunLock.java`: the use case with fakes

```java
package plainround.catalogimport.application;

import com.github.sviperll.result4j.Result;
import java.util.concurrent.atomic.AtomicInteger;

/** A lock that is free or held by someone else, and counts how often a lease was released. */
class FakeRunLock implements RunLock {

  final AtomicInteger released = new AtomicInteger();
  private final boolean free;

  FakeRunLock(boolean free) {
    this.free = free;
  }

  @Override
  public Result<Lease, String> tryAcquire() {
    return free ? Result.success(released::incrementAndGet) : Result.error("held by pid 42");
  }
}
```

A hand-written fake: a lock that is free or held by "pid 42" and counts how many times a lease was released. `Result.success(released::incrementAndGet)` is a lease written as a method reference (the lease's `close()` increments the counter). `AtomicInteger` is a number safe to change from several threads.

```java
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

```

The use case needs four collaborators. Three are lambdas: `() -> record("recover", 2)` is a `CrashRecovery`, `(file, target) -> record("export", 100)` is a `RejectLog`, and each test supplies its own `ImportJob`. The helper `record` appends the call name to a shared list and returns a number, so the test can later check the order of calls.

```java
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
```

The first test asserts `containsExactly("recover", "launch", "export")`: recovery, then the job, then the reject file, in that order. The second: with a held lock nothing runs (`calls` is empty), the status is `BLOCKED`, the message contains the holder, and the exit code is 4. The third: when the job throws, the exception propagates (`hasMessage("database gone")`) **and** the lock was released exactly once (`assertThat(lock.released).hasValue(1)`), proving the `try (lease)` block. Then the blank run id and the exit codes of every outcome.

### `GenerateCatalogFileTest.java`

```java
package plainround.catalogimport.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import plainround.catalogimport.domain.ProductRules;
import plainround.catalogimport.infrastructure.batch.ProductLineMapper;

class GenerateCatalogFileTest {

  @TempDir Path folder;

  private List<String> generate(int rows, int badEvery) throws IOException {
    Path file = folder.resolve("products.csv");
    new GenerateCatalogFile().write(file, rows, badEvery, 7);
    return Files.readAllLines(file);
  }

  @Test
  void the_file_has_a_header_and_one_line_per_row() throws IOException {
    List<String> lines = generate(5000, 2000);
    assertThat(lines).hasSize(5001);
    assertThat(lines.getFirst()).isEqualTo("sku,name,category,price");
  }

```

`@TempDir Path folder` is a fresh empty folder JUnit deletes afterwards. The helper `generate` writes a file there and reads it back (`Files.readAllLines`). The first test checks the header and the count of lines (5,000 rows plus the header).

```java
  @Test
  void bad_rows_are_planted_at_the_spacing_in_the_lessons_four_flavors() throws IOException {
    List<String> lines = generate(8000, 2000);
    assertThat(lines.get(2000)).startsWith("SKU-002000,Product 2000,weapons,");
    assertThat(lines.get(4000)).startsWith("BAD4000,");
    assertThat(lines.get(6000)).endsWith(",-5");
    assertThat(lines.get(8000)).endsWith(",0");
  }

  @Test
  void exactly_the_planted_rows_fail_validation() throws IOException {
    ProductLineMapper mapper = new ProductLineMapper();
    List<String> lines = generate(8000, 2000);
    long bad =
        java.util.stream.IntStream.range(1, lines.size())
            .mapToObj(i -> mapper.mapLine(lines.get(i), i + 1))
            .filter(row -> ProductRules.validate(row).isError())
            .count();
    assertThat(bad).isEqualTo(4);
  }

  @Test
  void the_same_seed_gives_the_same_file_and_no_bad_rows_when_asked_for_none() throws IOException {
    assertThat(generate(300, 0)).isEqualTo(generate(300, 0));
    assertThat(new GenerateCatalogFile().write(folder.resolve("clean.csv"), 300, 0, 1)).isZero();
  }

  @Test
  void the_return_value_is_the_number_of_bad_rows() {
    assertThat(new GenerateCatalogFile().write(folder.resolve("x.csv"), 200_000, 2000, 7))
        .isEqualTo(100);
  }
}
```

The four flavors of bad row land where expected (`lines.get(2000)` is the line for row 2000 because index 0 is the header). A test maps every line through the real `ProductLineMapper` and counts the rows the real rules refuse: exactly 4 for 8,000 rows with a bad row every 2,000. The same seed gives the same file (`generate(300, 0)` twice is equal). The return value for 200,000 rows is 100.

### `BatchComponentsTest.java`

```java
package plainround.catalogimport.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.Chunk;
import org.springframework.dao.TransientDataAccessException;
import plainround.catalogimport.domain.InvalidProductException;
import plainround.catalogimport.domain.ProductRow;
import plainround.catalogimport.infrastructure.batch.ImportProperties;
import plainround.catalogimport.infrastructure.batch.ProductItemProcessor;
import plainround.catalogimport.infrastructure.batch.ProductLineMapper;
import plainround.catalogimport.infrastructure.batch.TransientFaultInjector;

class BatchComponentsTest {

  private final ProductLineMapper mapper = new ProductLineMapper();

  @Test
  void a_line_becomes_a_row_that_keeps_its_line_number() {
    ProductRow row = mapper.mapLine("SKU-002000,Product 2000,weapons,87.12", 2002);
    assertThat(row)
        .isEqualTo(
            new ProductRow(2002, "SKU-002000", "Product 2000", "weapons", new BigDecimal("87.12")));
  }

  @Test
  void a_short_or_garbled_line_becomes_a_row_the_rules_refuse() {
    ProductRow row = mapper.mapLine("SKU-000001,only a name", 5);
    assertThat(row.category()).isEmpty();
    assertThat(row.price()).isNull();
  }

  @Test
  void the_processor_returns_a_valid_row_and_throws_the_one_skippable_exception_for_a_bad_one() {
    ProductItemProcessor processor = new ProductItemProcessor();
    ProductRow good = mapper.mapLine("SKU-000001,n,books,10.00", 2);
    ProductRow bad = mapper.mapLine("SKU-000002,n,books,0", 3);
    assertThat(processor.process(good)).isSameAs(good);
    assertThatThrownBy(() -> processor.process(bad))
        .isInstanceOf(InvalidProductException.class)
        .hasMessage("price must be above zero");
  }

```

The line mapper keeps the line number and parses the price; a short line (`"SKU-000001,only a name"`) becomes a row with an empty category and a `null` price, which the rules refuse; the processor returns a good row as the **same object** (`isSameAs`) and throws `InvalidProductException` with the rule's message for a bad one.

```java
  private final List<Integer> written = new ArrayList<>();

  private Chunk<ProductRow> chunk(int firstLine) {
    return new Chunk<>(new ProductRow(firstLine, "SKU-000001", "n", "books", BigDecimal.ONE));
  }

  @Test
  void the_injector_fails_every_nth_chunk_once_and_lets_the_retry_through() throws Exception {
    TransientFaultInjector injector = new TransientFaultInjector(c -> written.add(c.size()), 2);
    injector.write(chunk(10));
    assertThatThrownBy(() -> injector.write(chunk(20)))
        .isInstanceOf(TransientDataAccessException.class);
    injector.write(chunk(20));
    injector.write(chunk(30));
    assertThat(written).hasSize(3);
    assertThat(injector.injected()).isEqualTo(1);
  }

  @Test
  void the_injector_with_zero_never_fails() throws Exception {
    TransientFaultInjector injector = new TransientFaultInjector(c -> written.add(c.size()), 0);
    injector.write(chunk(1));
    injector.write(chunk(2));
    assertThat(injector.injected()).isZero();
  }

  @Test
  void the_properties_have_the_lessons_defaults_and_reject_nonsense() {
    Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    assertThat(validator.validate(new ImportProperties(1000, 500, 3, 0))).isEmpty();
    assertThat(validator.validate(new ImportProperties(0, -1, 0, -2))).hasSize(4);
  }
}
```

The injector tests use a lambda as the delegate writer (`c -> written.add(c.size())`) and build tiny chunks. With `everyNthChunk` 2: the first chunk passes, the second throws `TransientDataAccessException`, the retry of the same chunk (same first line) passes, and the next passes: three writes and one injection. With 0 it never fails. The last test validates `ImportProperties`: the defaults give no violations; an all-wrong record gives exactly four, one per component.

### `TestDatabase.java` and the integration tests

```java
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

```

Like the scale project, this helper reads the connection details from environment variables with defaults, creates its own database (`catalog_test`) so tests never touch the data `make run` loads, and gives out a `JdbcTemplate`.

```java
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
```

`reset` empties the product table, the reject table and every Spring Batch table. `prepare` creates the database if missing and applies the same three SQL files as `make db`, once per JVM (`synchronized` and a `ready` flag).

```java
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

```

`@SpringBootTest(classes = CatalogImportApplication.class, args = "--command=none")` starts the real application with a command that does nothing, so the test calls the use case itself. `@DynamicPropertySource` points it at the test database and sets the chunk size to 500. Before each test a 20,000-row file with 10 bad rows is generated into the temp folder.

```java
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
        .startsWith("line 2001: SKU-002000,weapons,")
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
```

The tests: a clean run reads 20,000, writes 19,990, skips 10, and the reject file has 10 lines, the first starting `line 2001: SKU-002000,weapons,`; the job repository (through the view `step_summary`) shows one completed step with those counts; the same file under a new run id leaves one row per SKU and the same 10 rejects; the same run id after completion is refused with status `ALREADY_COMPLETE` and exit code 3; an edited name in the file is **updated** by the upsert and not added as a row; and two attempts to take the advisory lock show that the second is refused while the first is held, and that the lock is free again after the first lease is closed.

```java
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

```

Skip limits and retries need different settings, so each runs in its own application context. `abstract static class Base` holds the shared setup. `@Nested` classes extend it.

```java
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
```

`SkipLimit` sets `import.skip-limit=5` through the annotation's `properties`. With 20 bad rows the run fails and the message contains `Skip limit of '5' exceeded`; with exactly 5 bad rows it completes and 5 records exist. `TransientFaults` makes every third chunk of 200 fail once; the job still completes with only the 10 planted skips and 19,990 rows.

```java
package plainround.catalogimport.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import plainround.catalogimport.application.GenerateCatalogFile;
import plainround.catalogimport.application.ImportCatalog;
import plainround.catalogimport.application.ImportReport;
import plainround.catalogimport.application.ImportReport.Status;
import plainround.catalogimport.application.ImportRequest;
import plainround.catalogimport.domain.ProductRow;
import plainround.catalogimport.infrastructure.batch.CatalogJobConfiguration;
import plainround.catalogimport.infrastructure.batch.StaleExecutionRecovery;
import plainround.catalogimport.interfaces.CatalogImportApplication;

/**
 * Crashes and recovery. The in-process crash is a writer that throws on the seventh chunk, which
 * leaves the repository as a failed run; the kill -9 version is `make crash-test`. A write failure
 * that can never succeed is not retried.
 */
@Tag("integration")
@SpringBootTest(
    classes = {CatalogImportApplication.class, CrashAndRecoveryIT.Switches.class},
    args = "--command=none")
class CrashAndRecoveryIT {

  static final AtomicInteger CRASH_AT_CALL = new AtomicInteger(0);
  static final AtomicInteger WRITE_CALLS = new AtomicInteger();

  @TestConfiguration
  static class Switches {
    @Bean
    @Primary
    ItemWriter<ProductRow> countingWriter(JdbcBatchItemWriter<ProductRow> upsertWriter) {
      return chunk -> {
        int call = WRITE_CALLS.incrementAndGet();
        if (call == CRASH_AT_CALL.get()) {
          throw new IllegalStateException("simulated crash");
        }
        upsertWriter.write(chunk);
      };
    }
  }

```

The in-process version of a crash. `Switches` is a `@TestConfiguration` that replaces the writer with a `@Primary` one that throws `IllegalStateException("simulated crash")` on a chosen call (`CRASH_AT_CALL`), counting calls in `WRITE_CALLS`.

```java
  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", TestDatabase::url);
    registry.add("spring.datasource.username", TestDatabase::user);
    registry.add("spring.datasource.password", TestDatabase::password);
    registry.add("import.chunk-size", () -> "500");
  }

  @TempDir Path folder;
  @Autowired ImportCatalog importCatalog;
  @Autowired JobExplorer explorer;
  @Autowired JobRepository repository;
  Path file;

  @BeforeEach
  void clean() {
    TestDatabase.reset();
    CRASH_AT_CALL.set(0);
    WRITE_CALLS.set(0);
    file = folder.resolve("products.csv");
    new GenerateCatalogFile().write(file, 20_000, 2_000, 7);
  }

  private ImportReport run(String runId) {
    return importCatalog.run(new ImportRequest(file, runId, folder.resolve("rejects.txt")));
  }

  private List<String> steps() {
    return TestDatabase.jdbc()
        .queryForList(
            "SELECT status || ':' || read_count || ':' || write_count || ':' || skip_count FROM step_summary "
                + "ORDER BY step_execution_id",
            String.class);
  }

  @Test
  void a_crash_then_a_restart_with_the_same_parameters_resumes_and_ends_with_one_row_per_sku() {
    CRASH_AT_CALL.set(7);
    ImportReport crashed = run("crashy");
    assertThat(crashed.status()).isEqualTo(Status.FAILED);
    long afterCrash =
        TestDatabase.jdbc().queryForObject("SELECT count(*) FROM product", Long.class);
    assertThat(afterCrash)
        .isBetween(2_900L, 3_100L); // six committed chunks of 500, minus the planted skips

    CRASH_AT_CALL.set(0);
    ImportReport resumed = run("crashy");
    assertThat(resumed.status()).isEqualTo(Status.COMPLETED);
    assertThat(resumed.resumed()).isTrue();
    assertThat(resumed.read()).isLessThan(20_000);
    assertThat(TestDatabase.jdbc().queryForObject("SELECT count(*) FROM product", Long.class))
        .isEqualTo(19_990);
    assertThat(
            TestDatabase.jdbc()
                .queryForObject("SELECT count(DISTINCT sku) FROM product", Long.class))
        .isEqualTo(19_990);
    assertThat(TestDatabase.jdbc().queryForObject("SELECT count(*) FROM import_reject", Long.class))
        .isEqualTo(10);
    assertThat(steps()).hasSize(2).first().asString().startsWith("FAILED:");
  }

```

A crash on the 7th write leaves the run `FAILED` with about six committed chunks in the table; clearing the switch and running again with the same run id **resumes**: status `COMPLETED`, `resumed` true, fewer than 20,000 lines read, and the final table has 19,990 rows, all with distinct SKUs, and 10 reject records; the repository holds two step executions, the first `FAILED`.

```java
  @Test
  void the_restart_reads_only_the_lines_after_the_last_committed_chunk() {
    CRASH_AT_CALL.set(5);
    run("crashy");
    long committed = savedReadCount();
    assertThat(committed).isEqualTo(2_000);
    CRASH_AT_CALL.set(0);
    ImportReport resumed = run("crashy");
    assertThat(resumed.read()).isEqualTo(20_000 - committed);
  }

  /**
   * The reader's saved position, read through the job explorer (the context is stored serialized).
   */
  private long savedReadCount() {
    var ids =
        TestDatabase.jdbc()
            .queryForMap(
                "SELECT job_execution_id, step_execution_id FROM batch_step_execution "
                    + "ORDER BY step_execution_id LIMIT 1");
    var step =
        explorer.getStepExecution(
            ((Number) ids.get("job_execution_id")).longValue(),
            ((Number) ids.get("step_execution_id")).longValue());
    return step.getExecutionContext().getInt("productReader.read.count");
  }

  @Test
  void an_execution_left_running_by_a_dead_process_is_marked_failed_and_keeps_its_counts() {
    JobExecution orphan = orphan("dead-run");
    int recovered = new StaleExecutionRecovery(explorer, repository).recoverCrashedRuns();
    assertThat(recovered).isEqualTo(1);
    assertThat(explorer.getJobExecution(orphan.getId()).getStatus()).isEqualTo(BatchStatus.FAILED);
    assertThat(explorer.getJobExecution(orphan.getId()).getExitStatus().getExitDescription())
        .contains("process died");
  }

  @Test
  void recovery_with_nothing_running_does_nothing() {
    assertThat(new StaleExecutionRecovery(explorer, repository).recoverCrashedRuns()).isZero();
  }

  @Test
  void the_use_case_recovers_an_orphan_before_launching_so_the_same_parameters_can_restart() {
    orphan("dead-run");
    ImportReport report = run("dead-run");
    assertThat(report.status()).isEqualTo(Status.COMPLETED);
  }

```

A crash on the 5th write leaves a saved read position of exactly 2,000 (four committed chunks of 500); the restart reads exactly 18,000 lines. `savedReadCount` reads the position through the `JobExplorer` (the step context is stored serialized). The recovery tests build an "orphan" execution recorded as `STARTED` and check that `StaleExecutionRecovery` marks it `FAILED` with the note `process died`, that it does nothing when nothing is running, and that the use case repairs an orphan before launching so the same parameters can restart.

```java
  private JobExecution orphan(String runId) {
    var parameters =
        new JobParametersBuilder()
            .addString("file", file.toString())
            .addString("run.id", runId)
            .toJobParameters();
    try {
      JobExecution execution =
          repository.createJobExecution(CatalogJobConfiguration.JOB_NAME, parameters);
      execution.setStatus(BatchStatus.STARTED);
      repository.update(execution);
      return execution;
    } catch (Exception problem) {
      throw new IllegalStateException(problem);
    }
  }

  @Test
  void a_failure_that_can_never_succeed_is_written_once_and_not_retried() throws Exception {
    String tooLong = "x".repeat(200);
    java.nio.file.Files.writeString(
        file, "sku,name,category,price\nSKU-000001," + tooLong + ",books,10.00\n");
    ImportReport report = run("never");
    assertThat(report.status()).isEqualTo(Status.FAILED);
    assertThat(report.message()).contains(DataIntegrityViolationException.class.getName());
    assertThat(WRITE_CALLS).hasValue(1);
  }
}
```

`orphan` creates a job execution directly in the repository and marks it started. The last test writes a file whose product name is 200 characters (longer than the column), which makes the database reject the insert with `DataIntegrityViolationException`: the report is `FAILED`, mentions that exception, and the writer was called **once** (`assertThat(WRITE_CALLS).hasValue(1)`), proving a non-transient error is not retried.

```java
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

```

The reject log tests: recording the same line twice leaves one record with the latest reason (the upsert).

```java
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
```

Export writes the lines of the requested file only, in line order; a file with no rejects exports an empty file; the step table reads the job repository (empty at the start) and counts products.

### The end-to-end tests

```java
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
```

The same helper pattern for the database `catalog_e2e`.

```java
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

```

The lesson's scenario at full size: 200,000 rows with 100 bad ones, run in order (`@TestMethodOrder` with `@Order`). `@TempDir static Path folder` is a static temp folder shared by all tests of the class.

```java
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
```

Test 1: the clean run reads 200,000, writes 199,900, skips 100, and the reject file has 100 lines starting `line 2001: SKU-002000,weapons,` and `line 4001: BAD4000,`. Test 2: the job repository shows one completed step with those counts. Test 3: the whole file under a new run id leaves 199,900 rows and 199,900 distinct SKUs. Test 4: a file with about 600 bad rows fails on the skip limit.

```java
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
```

Lesson step 6 at full size: with `import.transient-fault-every-nth-chunk=50` set in the annotation, the import still completes with exactly 100 skips and 199,900 rows.

## Try it yourself

1. Run the unit tests: `make test`. Expect `Tests run: 48` and a passing coverage check.
2. Run one class: `./mvnw -q test -Dtest=ProductRulesTest`, then read `target/surefire-reports/plainround.catalogimport.domain.ProductRulesTest.txt`.
3. Break a rule: in `ProductRules.java` change `signum() <= 0` to `signum() < 0`; the price tests for `0` and `0.00` fail with the expected reason. Undo it.
4. Run the integration tests: `make up db` then `make test-integration`.
5. Run the end-to-end tests: `make test-e2e` (about a minute).

## Mistakes people make

- **Tests that depend on each other by accident.** JUnit may run methods in any order. This repository uses an explicit order only for the scenario test, and says so.
- **Using the real clock or randomness.** The result changes from run to run. The code takes a `Clock`, and the random test seeds `new Random(11)`.
- **Testing the framework instead of the behavior.** A test that only checks that Spring injected a bean proves little. These tests call real code and assert real answers.
- **A test that passes when nothing runs.** Failsafe ignores classes it does not recognise by name; a misnamed class "passes" by never running. The `includes` in `pom.xml` and the line `Tests run: ...` in the report are how this repository checks the tests ran.
- **Leaving data behind.** Integration tests here use their own database (`catalog_test`) and clear what they changed in `@BeforeEach` and `@AfterAll`.

## Check yourself

1. How do integration tests differ from unit tests here? *They use the real job on a real PostgreSQL; Failsafe runs them, tagged `integration`.*
2. What does `@DynamicPropertySource` do? *Lets the test set application settings (like the database URL) before the application starts.*
3. Why does `ImportCatalogTest` use a fake lock? *To test the use case without a database and to count releases.*
4. How does a test prove that a non-transient error is not retried? *It counts writer calls and asserts exactly one.*
5. Why can the `kill -9` test not be a JUnit test? *A test cannot kill its own JVM and then check the result; the script does it from outside.*

## Further reading

- JUnit 5 user guide (Jupiter 5.12, pinned through Spring Boot 3.5): https://junit.org/junit5/docs/5.12.2/user-guide/
- Maven Surefire plug-in: https://maven.apache.org/surefire/maven-surefire-plugin/
- Maven Failsafe plug-in: https://maven.apache.org/surefire/maven-failsafe-plugin/
