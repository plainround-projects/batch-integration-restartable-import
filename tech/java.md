# Java 21

## What it is

Java is a programming language. A programming language is a way of writing instructions that a computer can follow. You write the instructions in plain text files (here they end in `.java`). A program called the **compiler** (`javac`) turns those text files into **bytecode**, which is a compact set of instructions saved in `.class` files. Another program, the **Java Virtual Machine** (the JVM, started with the command `java`), reads the bytecode and runs it. The JVM is the same on a Mac, a Windows laptop and a Linux server, which is why Java programs run almost anywhere.

Three words you will meet again and again:

- A **class** is a named bundle of data and the actions (called **methods**) that work on that data.
- A **package** is a folder-like name that groups classes, written with dots, such as `plainround.catalogimport.domain`. The folder on disk has the same shape: `plainround/catalogimport/domain/`.
- A **JDK** (Java Development Kit) is the download that contains the compiler, the JVM and the standard library (thousands of ready-made classes such as `List`, `Duration` and `BigDecimal`).

This repository is written for **Java 21**, an LTS release (LTS means "long-term support": the version that companies keep using for years). It is built and run here with a newer JDK, but the compiler is told to produce Java 21 code (`maven.compiler.release` is 21 in `pom.xml`), so everything in the notes is true for Java 21.

## Why this repository uses it

The lesson is about Spring Batch, a Java framework. Beyond the framework, the repository keeps its business rules (what makes a catalog row valid, how a refused row is written down, what each exit code means) in plain Java with no framework imports, so they can be tested in milliseconds. The packages follow one rule: **domain** knows nothing, **application** knows only the domain, **infrastructure** (Spring Batch, JDBC, files) knows both, and **interfaces** (the command line) may know everything but nothing may depend on it. An ArchUnit test enforces it.

## Where it appears

| Path | What it does with Java |
| --- | --- |
| `${J}/domain/` | The rules: a product row, validation, a refused row. |
| `${J}/application/` | The use cases (`ImportCatalog`, `GenerateCatalogFile`) and the ports they need. |
| `${J}/infrastructure/` | Spring Batch, JDBC and file code. Walked through in the Spring Batch, JDBC and Spring Boot notes. |
| `${J}/interfaces/` | The command-line program. Walked through in the Spring Boot note. |
| `src/test/java/` | The tests. Walked through in the JUnit note. |
| `pom.xml` | Tells Maven to compile for Java 21. |

## How it works underneath

When you run `java -jar app.jar` an operating-system **process** starts. A process is one running copy of a program, with its own memory. Inside that process the JVM loads the bytecode, checks it, and then runs it. The JVM starts by interpreting bytecode and, for the code that runs often, compiles it to the machine's own instructions while the program is running (this is called JIT, "just in time" compilation). That is why a Java program is slow for the first second and fast afterwards.

Memory is split in two main areas. The **stack** holds the small, short-lived values of the method that is running right now (a method is one named block of instructions); the **heap** holds the objects, which are the bundles of data created with `new`. A part of the JVM called the **garbage collector** finds objects that nothing refers to any more and frees their memory, so you never free memory by hand.

Java is **statically typed**: every variable has a type (`long`, `String`, `BigDecimal`) that is fixed when you write the code, and the compiler refuses a program that mixes types wrongly. The main numeric types you will see: `int` (a 32-bit whole number), `long` (a 64-bit whole number), `double` (a floating-point number, which is only approximate), and `BigDecimal` (an exact decimal number, used for money because `double` cannot represent 0.10 exactly).

Code is organised in **packages** and **imports**. A line `import java.util.List;` tells the compiler that the short name `List` means `java.util.List`. Names from `java.lang` (such as `String`, `Math`, `IllegalArgumentException`) need no import.

A **thread** is one line of execution inside a process. A program can run several threads at the same time (on a machine with several processor cores they really run in parallel). Threads share the heap, which is powerful and dangerous: two threads changing the same object at the same moment can corrupt it. This is why this repository prefers **immutable** objects (objects that can never change after they are created).

## Walkthrough of this repository's use

Language features this repository uses, explained once here and pointed to later:

- **Record.** `public record Account(long accountId, String tier) {}` declares a class whose only job is to carry two values. The compiler writes for you: a constructor that takes both values, a getter method for each value (named like the field, `account.tier()`, with no `get` prefix), and `equals`, `hashCode` and `toString` (two accounts with the same values are equal; printing one shows both values). The fields are `final`, which means they cannot change after construction: a record is immutable.
- **Compact constructor.** Inside a record you can write `public BillingMonth { ... }` with no parameter list. It runs before the fields are assigned, so it is the place to check the values and throw an exception if they are wrong.
- **Exception.** A way for a method to say "I cannot continue". `throw new IllegalArgumentException("...")` stops the method and unwinds to whoever can handle it; if nobody does, the program or thread ends with a message. `IllegalArgumentException` is the standard exception for "the caller gave me a bad value".
- **`final` class and private constructor.** `public final class ProductRules { private ProductRules() {} }` is the usual way to write a holder of functions that should never be instantiated: `final` forbids subclasses and the private constructor forbids `new ProductRules()`.
- **`static`.** A static method belongs to the class, not to an object, so you call it as `ProductRules.validate(...)` without creating anything.
- **Generics.** `List<AccountRange>` is "a list whose elements are all `AccountRange`". The angle brackets let the compiler check what goes in and what comes out.
- **Lambda.** `index -> min + index * size <= max` is a small unnamed function: the part before `->` is the parameter, the part after is the result. Lambdas are how you hand a piece of behaviour to another method.
- **Stream.** `LongStream.range(0, parts).filter(...).mapToObj(...).toList()` is a pipeline: produce the numbers 0 to parts-1, keep some, convert each to another kind of value, collect the results. Each step receives the output of the step before it.
- **Text formatting.** `"minId %d is above maxId %d".formatted(minId, maxId)` replaces each `%d` with the next number; `%s` is for text; `%ds` in `"projected %ds"` is `%d` (a number) followed by a literal `s`.
- **Javadoc.** A comment that starts with `/**` documents the next class or method. Tools turn it into web pages; people read it in the editor.
- **Duration.** `java.time.Duration` is an exact length of time (seconds and nanoseconds). Using it instead of a bare number prevents "was that milliseconds or seconds?" bugs.

More features appear in this repository; each is explained where it is first used below: a `switch` expression, `try`-with-resources, nested interfaces, `Optional` chains, `transient`, `Set.of`, and `Stream.concat`.

### The domain package: the rules

**`ProductRow.java`** is one line of the catalog file as a record.

```java
package plainround.catalogimport.domain;

import java.math.BigDecimal;

/**
 * One data line of the catalog file. The line number travels with the row so that a reject record
 * can say where the problem is. {@code price} is null when the field was empty or not a number.
 */
public record ProductRow(
    int lineNumber, String sku, String name, String category, BigDecimal price) {}
```

The record has five components: the line number (`int`, a 32-bit whole number), the SKU (text such as `SKU-000042`, the product code), the name, the category and the price (`BigDecimal`, exact decimal). The Javadoc says two important things: the line number travels with the row so a refusal can say where the problem is, and the price is `null` when the field was empty or not a number (the validator then refuses it).

**`Rejection.java`** is the reason a row was refused.

```java
package plainround.catalogimport.domain;

/** Why a row was refused: the value side of {@link ProductRules#validate}'s result. */
public record Rejection(String reason) {}
```

A record with one component, the reason text. The `{@link ProductRules#validate}` in the comment is a Javadoc link to the method that produces it.

**`InvalidProductException.java`** is the one exception the import step is allowed to skip.

```java
package plainround.catalogimport.domain;

import lombok.Getter;

/**
 * Thrown for a row that can never succeed. The step may skip this exception and no other: an
 * unexpected error must still stop the job.
 */
@Getter
public class InvalidProductException extends RuntimeException {

  private final transient ProductRow row;

  public InvalidProductException(ProductRow row, Rejection rejection) {
    super(rejection.reason());
    this.row = row;
  }
}
```

Line 3 imports Lombok's `@Getter`, which writes a getter method for each field (here `getRow()`). The class **extends** `RuntimeException`, so it is an *unchecked* exception: callers are not forced to catch it. The Javadoc explains the design: the Spring Batch step is told to skip *this* exception and no other, so a bug (a `NullPointerException`, say) still stops the job. The field is `private final transient ProductRow row`. `final` means it is assigned once, in the constructor. `transient` tells Java's serialization (turning an object into bytes to save or send it) to leave the field out; exceptions are serializable and a `ProductRow` is not required to be, so `transient` avoids a compiler warning (the build treats warnings as errors). The constructor calls `super(rejection.reason())`, which sets the exception's message to the reason, then stores the row.

**`RejectedRow.java`** is a refused row as it is stored and printed.

```java
package plainround.catalogimport.domain;

/** A row the import refused, as it is recorded and as it reads in the reject file. */
public record RejectedRow(
    int lineNumber, String sku, String category, String price, String reason) {

  /** {@code line 2002: SKU-002000,weapons,87.12 -> unknown category weapons} */
  public String asLine() {
    return "line %d: %s,%s,%s -> %s".formatted(lineNumber, sku, category, price, reason);
  }

  /** Builds the record from the row that failed validation. */
  public static RejectedRow of(ProductRow row, String reason) {
    return new RejectedRow(
        row.lineNumber(),
        row.sku(),
        row.category(),
        row.price() == null ? "" : row.price().toPlainString(),
        reason);
  }
}
```

`asLine()` produces the human-readable line of the reject file: `"line %d: %s,%s,%s -> %s".formatted(...)` puts the line number, SKU, category, price and reason in the places of `%d` (number) and `%s` (text). The Javadoc example shows the shape. The static method `of` builds a `RejectedRow` from the row that failed validation; `row.price() == null ? "" : row.price().toPlainString()` is the conditional operator: an empty text when there is no price, otherwise the price written without exponent notation (`toPlainString` prints `0.50`, never `5E-1`).

**`ProductRules.java`** is the validation, written as small functions that return a value instead of throwing.

```java
package plainround.catalogimport.domain;

import com.github.sviperll.result4j.Result;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The validation rules of the lesson's step 1: price above zero, a known category, a SKU in the
 * right format.
 */
public final class ProductRules {

  private static final Set<String> CATEGORIES =
      Set.of("books", "toys", "garden", "kitchen", "sport");
  private static final Pattern SKU_FORMAT = Pattern.compile("SKU-\\d{6}");

  private ProductRules() {}

  /** The row itself when every rule holds, otherwise the first rule's {@link Rejection}. */
  public static Result<ProductRow, Rejection> validate(ProductRow row) {
    return Stream.<Function<ProductRow, Optional<String>>>of(
            ProductRules::priceProblem, ProductRules::categoryProblem, ProductRules::skuProblem)
        .flatMap(rule -> rule.apply(row).stream())
        .findFirst()
        .<Result<ProductRow, Rejection>>map(reason -> Result.error(new Rejection(reason)))
        .orElseGet(() -> Result.success(row));
  }
```

Line 3 imports `Result` (see the Result4J note): a value that is a success or an error. The constants on lines 17 to 19: `Set.of("books", ...)` is an immutable set of the five known categories, and `SKU_FORMAT` is the regular expression `SKU-\d{6}` (the Java string `"SKU-\\d{6}"` doubles the backslash): the letters `SKU-` followed by exactly six digits. `validate` (lines 24 to 31) applies three rules in order and returns the *first* problem. It builds a stream of three functions (`Stream.of(ProductRules::priceProblem, ...)`; each is a **method reference**, a short way to say "this method"), turns each into an `Optional<String>` by applying it to the row (`rule.apply(row)`), flattens the optionals into a stream of problems (`flatMap(... .stream())`: an `Optional` with a value becomes a one-element stream, an empty one becomes an empty stream), takes the first with `findFirst()`, maps it into `Result.error(new Rejection(reason))`, and if there is no problem at all (`orElseGet`) returns `Result.success(row)`. The generic argument `<Function<ProductRow, Optional<String>>>` and `<Result<ProductRow, Rejection>>` are explicit type hints that help the compiler.

```java

  private static Optional<String> priceProblem(ProductRow row) {
    return Optional.of(row)
        .filter(candidate -> candidate.price() == null || candidate.price().signum() <= 0)
        .map(candidate -> "price must be above zero");
  }

  private static Optional<String> categoryProblem(ProductRow row) {
    return Optional.of(row)
        .filter(candidate -> !CATEGORIES.contains(candidate.category()))
        .map(candidate -> "unknown category " + candidate.category());
  }

  private static Optional<String> skuProblem(ProductRow row) {
    return Optional.of(row)
        .filter(
            candidate -> candidate.sku() == null || !SKU_FORMAT.matcher(candidate.sku()).matches())
        .map(candidate -> "bad SKU format");
  }

  /** The known categories, for the file generator and the documentation. */
  public static Set<String> categories() {
    return CATEGORIES;
  }
```

Each rule returns an `Optional<String>`: `Optional.of(row).filter(condition).map(reason)` is "if the condition holds, the reason text; otherwise nothing". `priceProblem` holds when the price is `null` or `signum() <= 0` (`signum` is -1, 0 or 1 for negative, zero, positive). `categoryProblem` holds when the set does not contain the category (`!` means not). `skuProblem` holds when the SKU is null or does not match the pattern (`matcher(sku).matches()` tests the whole text). The reasons are exactly the texts the README shows: `price must be above zero`, `unknown category weapons`, `bad SKU format`. `categories()` exposes the set for the file generator.

```java

  /**
   * Parses a price field; null when it is empty or not a number, which {@link #validate} then
   * refuses.
   */
  public static BigDecimal parsePrice(String field) {
    return Optional.ofNullable(field)
        .map(String::strip)
        .filter(text -> !text.isEmpty())
        .flatMap(ProductRules::toDecimal)
        .orElse(null);
  }

  private static Optional<BigDecimal> toDecimal(String text) {
    return Optional.of(text).filter(ProductRules::looksNumeric).map(BigDecimal::new);
  }

  private static boolean looksNumeric(String text) {
    return text.matches("-?\\d+(\\.\\d+)?");
  }
}
```

`parsePrice` turns a text field into a `BigDecimal` or `null`: `Optional.ofNullable(field)` (empty for `null`), `.map(String::strip)` trims spaces, `.filter(text -> !text.isEmpty())` drops empty text, `.flatMap(ProductRules::toDecimal)` converts, `.orElse(null)` gives `null` when anything failed. `looksNumeric` uses the regex `-?\d+(\.\d+)?` (optional minus, digits, optional decimal part) so that the `BigDecimal` constructor never throws on text such as `abc`.

### The application package: use cases and ports

An **interface** lists methods without code; here each interface is a **port** the use case needs from the outside.

```java
package plainround.catalogimport.application;

import java.nio.file.Path;

/**
 * What to import. The file and the run id together are the job's identity: the same pair after a
 * crash restarts the same job instance; a new run id is a new instance that starts from the first
 * row.
 */
public record ImportRequest(Path file, String runId, Path rejectFile) {

  public ImportRequest {
    if (runId == null || runId.isBlank()) {
      throw new IllegalArgumentException("run.id must not be blank");
    }
  }
}
```

A record of what to import: the file, the **run id** and where to write the reject file. The file and the run id together are the job's identity in Spring Batch: the same pair after a crash restarts the same job instance; a new run id starts from the first row. The compact constructor rejects a blank run id (`isBlank()` is true for empty text and text of only spaces).

```java
package plainround.catalogimport.application;

/**
 * What a run did, from the step's counters. Zero counts with a status other than COMPLETED mean the
 * run did not start.
 */
public record ImportReport(
    Status status,
    long read,
    long written,
    long skipped,
    long commits,
    boolean resumed,
    String message) {

  /** How the run ended. */
  public enum Status {
    COMPLETED,
    FAILED,
    STOPPED,
    ALREADY_COMPLETE,
    BLOCKED
  }

```

The report of a run: the status, the counts, whether the run resumed an earlier one, and a message. `enum Status` (lines 17 to 23) is a type with a fixed list of values; defining it inside the record makes its full name `ImportReport.Status`.

```java
  /**
   * 0 when the table is right, 1 when failed or stopped, 3 when the same parameters already
   * completed, 4 when blocked.
   */
  public int exitCode() {
    return switch (status) {
      case COMPLETED -> 0;
      case ALREADY_COMPLETE -> 3;
      case BLOCKED -> 4;
      case FAILED, STOPPED -> 1;
    };
  }

  /** A run refused because the same file and run id already finished. */
  public static ImportReport alreadyComplete(String runId) {
    return new ImportReport(
        Status.ALREADY_COMPLETE,
        0,
        0,
        0,
        0,
        false,
        "run.id '%s' already completed; use a new run.id to import again".formatted(runId));
  }

  /** A run refused because another process holds the import's lock. */
  public static ImportReport blocked(String reason) {
    return new ImportReport(Status.BLOCKED, 0, 0, 0, 0, false, reason);
  }
}
```

`exitCode()` uses a **switch expression**: `return switch (status) { case COMPLETED -> 0; ... };` evaluates to a value, and the compiler checks that every enum value is covered (no `default` needed). The mapping: completed 0, already complete 3, blocked 4, failed or stopped 1 (a scheduler can read these numbers). The two static factory methods build refused-run reports; note the text `"run.id '%s' already completed; use a new run.id to import again".formatted(runId)`.

```java
package plainround.catalogimport.application;

/** The port to the batch engine: start a job for a request and say what happened. */
public interface ImportJob {

  /**
   * Launches the job with the request's identity; a restart of a crashed run resumes where it
   * stopped.
   */
  ImportReport launch(ImportRequest request);
}
```

```java
package plainround.catalogimport.application;

/**
 * The port that marks runs killed mid-flight as failed, so that the same parameters can be
 * restarted.
 */
public interface CrashRecovery {

  /** Marks every execution still recorded as running as failed; returns how many it found. */
  int recoverCrashedRuns();
}
```

```java
package plainround.catalogimport.application;

import java.nio.file.Path;

/** The port to the record of refused rows. */
public interface RejectLog {

  /**
   * Writes the refused rows of a source file, one line each, in file order, to {@code target};
   * returns the count.
   */
  int export(String sourceFile, Path target);
}
```

Three ports. `ImportJob`: start the job for a request and say what happened. `CrashRecovery`: mark executions that a killed process left "running" as failed, returning how many. `RejectLog`: write the refused rows of a file, in file order, to a target file and return how many.

```java
package plainround.catalogimport.application;

import com.github.sviperll.result4j.Result;

/**
 * The port to "only one import at a time". The lock is tied to a live process, so a crash releases
 * it by itself: that is what makes it safe to treat an execution recorded as running as dead once
 * the lock can be taken.
 */
public interface RunLock {

  /** A held lock; closing it releases it. */
  interface Lease extends AutoCloseable {
    @Override
    void close();
  }

  /** The lease, or the reason another process holds the lock. */
  Result<Lease, String> tryAcquire();
}
```

`RunLock` is "only one import at a time". `tryAcquire()` returns `Result<Lease, String>`: the lease (a held lock) or the reason someone else holds it. `Lease extends AutoCloseable` is a **nested interface** (declared inside another): a thing you can close, here releasing the lock; the `@Override void close()` narrows `AutoCloseable.close()` so it throws no checked exception, which lets it be used in `try`-with-resources without a `catch`. The Javadoc gives the reason the lock is safe: it is tied to a live process, so a crash releases it by itself.

```java
package plainround.catalogimport.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The use case: take the lock, recover from a crash, run the job, write the reject file.
 *
 * <p>Recovery comes first because Spring Batch refuses to start a job instance that still has an
 * execution recorded as running, which is exactly what a {@code kill -9} leaves behind. It is safe
 * only because the lock proves that no other process is running the import.
 */
@Slf4j
@RequiredArgsConstructor
public class ImportCatalog {

  private final RunLock lock;
  private final CrashRecovery recovery;
  private final ImportJob job;
  private final RejectLog rejects;

```

The use case. `@Slf4j` (a logger named `log`) and `@RequiredArgsConstructor` (a constructor for the four `final` fields) come from Lombok. The Javadoc explains the order: recovery first, because Spring Batch refuses to start a job instance that still has an execution recorded as running, which is exactly what `kill -9` leaves behind; this is only safe because the lock proves no other process is running the import.

```java
  /**
   * Runs the import for the request and returns the report; the reject file is rewritten from the
   * record.
   */
  public ImportReport run(ImportRequest request) {
    log.info("import starting file={} runId={}", request.file(), request.runId());
    return lock.tryAcquire()
        .map(lease -> runLocked(request, lease))
        .recoverError(ImportReport::blocked);
  }

  private ImportReport runLocked(ImportRequest request, RunLock.Lease lease) {
    try (lease) {
      int recovered = recovery.recoverCrashedRuns();
      ImportReport report = job.launch(request);
      int rejected = rejects.export(request.file().toString(), request.rejectFile());
      log.info(
          "import finished status={} recovered={} rejected={}",
          report.status(),
          recovered,
          rejected);
      return report;
    }
  }
}
```

`run` logs the start, then `lock.tryAcquire().map(lease -> runLocked(request, lease)).recoverError(ImportReport::blocked)`: if the lock was obtained, run; if not, turn the reason text into a "blocked" report. `runLocked` uses **try-with-resources** (`try (lease) { ... }`): whatever happens inside, `lease.close()` runs at the end and releases the lock. Inside: recover crashed runs, launch the job, export the reject file, log, and return the report.

**`GenerateCatalogFile.java`** writes the sample file.

```java
package plainround.catalogimport.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import plainround.catalogimport.domain.ProductRules;

/**
 * Writes a catalog file with bad rows planted at a fixed spacing, in four flavors (price zero,
 * unknown category, wrong SKU format, negative price), like the lesson's {@code make_products.py}.
 */
public class GenerateCatalogFile {

  private static final List<String> CATEGORIES =
      List.copyOf(ProductRules.categories().stream().sorted().toList());

```

The imports show the standard library tools used: `Files`/`Path` for files, `Random` for repeatable pseudo-random numbers, `IntStream`/`Stream`. The constant `CATEGORIES` is the sorted list of the known categories (so the same seed always gives the same file).

```java
  /**
   * Writes {@code rows} products; every {@code badEvery}th row is bad (0 for none). Returns the bad
   * row count.
   */
  public int write(Path target, int rows, int badEvery, long seed) {
    Random random = new Random(seed);
    try (Stream<String> lines =
        Stream.concat(Stream.of("sku,name,category,price"), body(rows, badEvery, random))) {
      Files.write(target, (Iterable<String>) lines::iterator);
    } catch (IOException problem) {
      throw new UncheckedIOException(problem);
    }
    return badEvery == 0 ? 0 : rows / badEvery;
  }
```

`write` creates a `Random` from a **seed** (the same seed gives the same sequence). `Stream.concat(Stream.of("sku,name,category,price"), body(...))` puts the header line in front of the data lines. `try (Stream<String> lines = ...)` closes the stream afterwards. `Files.write(target, (Iterable<String>) lines::iterator)` writes the lines to the file (the cast makes the stream usable as an `Iterable`). The checked `IOException` is wrapped in an `UncheckedIOException`. The method returns how many bad rows were planted.

```java

  private Stream<String> body(int rows, int badEvery, Random random) {
    return IntStream.rangeClosed(1, rows).mapToObj(row -> line(row, badEvery, random));
  }

  private String line(int row, int badEvery, Random random) {
    String category = CATEGORIES.get(random.nextInt(CATEGORIES.size()));
    BigDecimal price =
        BigDecimal.valueOf(1 + random.nextDouble() * 499).setScale(2, RoundingMode.HALF_UP);
    boolean bad = badEvery > 0 && row % badEvery == 0;
    return bad
        ? badLine(row, (row / badEvery) % 4, category, price)
        : format("SKU-%06d".formatted(row), row, category, price.toPlainString());
  }

  private String badLine(int row, int flavor, String category, BigDecimal price) {
    return switch (flavor) {
      case 0 -> format("SKU-%06d".formatted(row), row, category, "0");
      case 1 -> format("SKU-%06d".formatted(row), row, "weapons", price.toPlainString());
      case 2 -> format("BAD" + row, row, category, price.toPlainString());
      default -> format("SKU-%06d".formatted(row), row, category, "-5");
    };
  }

  private String format(String sku, int row, String category, String price) {
    return "%s,Product %d,%s,%s".formatted(sku, row, category, price);
  }
}
```

`body` produces one line per row number from 1 to `rows` (`rangeClosed` includes the end). `line` picks a random category and a random price between 1 and 500 with two decimals (`setScale(2, RoundingMode.HALF_UP)`), then decides if this row is bad: `row % badEvery == 0` (the remainder is zero for every `badEvery`-th row). A bad row is one of four flavors chosen by `(row / badEvery) % 4`: price 0, category `weapons`, a SKU starting `BAD`, price -5 (a `switch` expression with a `default` case). Every line has the form `sku,Product N,category,price`.

## Try it yourself

1. Check your Java: `java -version`.
2. Run the tests of the rules: `./mvnw -q test -Dtest=ProductRulesTest`.
3. Generate a small file and look at it: `java -jar target/restartable-import-0.1.0.jar --command=generate --out=/tmp/p.csv --rows=10 --bad-every=5` (after `./mvnw -q -DskipTests package`), then `cat /tmp/p.csv`. Expect a header, eight good lines and bad lines on rows 5 and 10.
4. See the exit codes: run `make run-twice` and then `echo $?` (the Makefile ignores the failure; run the java command by hand to see 3).
5. Try a rule in `jshell`: `jshell --class-path target/classes`, then `plainround.catalogimport.domain.ProductRules.parsePrice("12.50")` prints `12.50`, and `parsePrice("abc")` prints `null`.

## Mistakes people make

- **Comparing text with `==`.** In Java `==` on two objects asks "are they the very same object?", not "do they hold the same text?". Use `equals`. The code here writes `GOLD.equals(tier)` (constant first), which also avoids a crash when `tier` is `null`.
- **Using `double` for money.** `0.1 + 0.2` is not exactly `0.3` with `double`. Money and prices use `BigDecimal`, and rounding is always chosen on purpose (`RoundingMode.DOWN` here).
- **Forgetting that `int` overflows.** A whole number that grows past its type's limit wraps around silently. Counts of rows here use `long`.
- **Catching an exception and doing nothing.** An empty `catch` hides the reason a program is wrong. This repository lets unexpected exceptions travel upward until something logs them.
- **Sharing a changing object between threads.** Records and `final` fields avoid the problem; mutable shared state needs locks or atomic classes (you will meet `AtomicLong` in the batch notes).
- **Off-by-one in ranges.** `range(0, 8)` produces 0 to 7, not 0 to 8. The sample file generator has a unit test that checks the line of the bad row for exactly this kind of mistake (`lines.get(2000)` is the line for row 2000 because index 0 is the header).

Specific to this repository: do not catch `Exception` around the import and hide the reason; and do not skip more exception types than `InvalidProductException`.

## Check yourself

1. Why is `InvalidProductException` unchecked and special? *The step is told to skip only that type; any other exception still stops the job.*
2. What does `ProductRules.validate` return for a row with price 0 and category `weapons`? *An error with `price must be above zero`, the first rule that fails.*
3. Why does `ImportCatalog` recover crashed runs before launching? *Spring Batch refuses to start an instance that has an execution still recorded as running.*
4. What does `try (lease)` guarantee? *The lock is released when the block ends, even after an exception.*
5. What exit code means "already complete"? *3.*

## Further reading

- Java SE 21 documentation: https://docs.oracle.com/en/java/javase/21/
- The Java Language Specification, Java SE 21 edition: https://docs.oracle.com/javase/specs/jls/se21/html/index.html
- Records (JEP 395): https://openjdk.org/jeps/395
- `java.time.Duration`: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/Duration.html
