# Result4J

## What it is

Result4J is a very small Java library that provides one type, `Result<T, E>`. A `Result` holds either a **success** value of type `T` or an **error** value of type `E`, never both. It is a way to return "this worked, here is the value" or "this failed, here is why" as an ordinary value, instead of throwing an exception.

Why bother? An exception jumps out of the method and can pass through many callers that do not expect it. A `Result` is part of the method's type, so the caller is forced to look at it. You can then chain steps: `.map(f)` changes the success value and leaves an error alone; `.recoverError(g)` turns an error back into a value of the success type.

## Why this repository uses it

Three kinds of "this may fail" appear: a row may be invalid (an expected outcome, not a bug), the lock may be held by someone else, and the batch launcher may throw. The first two are returned as values; the third is caught once and converted.

## Where it appears

| Path | What it does with Result4J |
| --- | --- |
| `${J}/domain/ProductRules.java` | `validate` returns `Result<ProductRow, Rejection>`. |
| `${J}/application/RunLock.java` | `tryAcquire` returns `Result<Lease, String>`. |
| `${J}/application/ImportCatalog.java` | Maps the lock result and recovers the error into a "blocked" report. |
| `${J}/infrastructure/jdbc/AdvisoryRunLock.java` | Builds the success (a lease) or the error (a message). |
| `${J}/infrastructure/batch/ProductItemProcessor.java` | The one place a `Result` becomes an exception. |
| `${J}/infrastructure/batch/Attempt.java` | Wraps a throwing call in a `Result`. |
| `${J}/infrastructure/batch/SpringBatchImportJob.java` | Maps and recovers the launch result. |
| `pom.xml` | Pins `com.github.sviperll:result4j` 1.2.0. |

## How it works underneath

`Result.success(value)` and `Result.error(problem)` are the two ways to create one. Internally a `Result` is an object with a flag and one field. `map(f)` runs `f` only when the result is a success. `recoverError(g)` runs `g` only when it is an error and must return the success type, so after it you always have a plain value. `isError()` tells you which one you have.

Java methods that declare **checked exceptions** (exceptions the compiler forces you to catch, such as `IOException`) do not fit well with lambdas. The usual cure, used here, is one small method that catches the exception at the boundary and returns a `Result`.

## Walkthrough of this repository's use

A rule that returns a value:

```java
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

`validate` ends with `.map(reason -> Result.error(new Rejection(reason))).orElseGet(() -> Result.success(row))`: the first problem found becomes an error; no problem becomes a success holding the row.

A port that returns a value:

```java

  /** The lease, or the reason another process holds the lock. */
  Result<Lease, String> tryAcquire();
```

Using it:

```java
  public ImportReport run(ImportRequest request) {
    log.info("import starting file={} runId={}", request.file(), request.runId());
    return lock.tryAcquire()
        .map(lease -> runLocked(request, lease))
        .recoverError(ImportReport::blocked);
  }
```

`lock.tryAcquire()` gives a `Result<Lease, String>`. `.map(lease -> runLocked(request, lease))` runs the import only on success and produces an `ImportReport`. `.recoverError(ImportReport::blocked)` turns an error (the reason text) into a blocked report. Both branches have type `ImportReport`, so `run` returns a plain report and no exception ever leaves.

Building the values:

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
```

`Result.success(() -> release(session, connection))` wraps a lease; `Result.error("another import holds the lock; ...")` wraps the reason.

Where a value becomes an exception, on purpose:

```java
package plainround.catalogimport.infrastructure.batch;

import org.springframework.batch.item.ItemProcessor;
import plainround.catalogimport.domain.InvalidProductException;
import plainround.catalogimport.domain.ProductRow;
import plainround.catalogimport.domain.ProductRules;

/**
 * Validates and returns the row, or throws the one exception the step is allowed to skip. Writes
 * nothing.
 */
public class ProductItemProcessor implements ItemProcessor<ProductRow, ProductRow> {

  @Override
  public ProductRow process(ProductRow row) {
    return ProductRules.validate(row)
        .orOnErrorThrow(rejection -> new InvalidProductException(row, rejection));
  }
}
```

Spring Batch decides what to skip by looking at exception types, so this adapter converts: `orOnErrorThrow(rejection -> new InvalidProductException(row, rejection))` returns the row for a success and throws the built exception for an error.

Where an exception becomes a value:

```java
package plainround.catalogimport.infrastructure.batch;

import com.github.sviperll.result4j.Result;
import java.util.concurrent.Callable;

/** The one place a checked exception from the batch API becomes a {@link Result}. */
final class Attempt {

  private Attempt() {}

  static <T> Result<T, Exception> of(Callable<T> action) {
    try {
      return Result.success(action.call());
    } catch (Exception problem) {
      return Result.error(problem);
    }
  }
}
```

`of(Callable<T>)` runs an action that may throw a checked exception and returns `Result.success(value)` or `Result.error(exception)`. It is `final` and package-private: an implementation detail of the batch adapter. (Its use in `SpringBatchImportJob.launch` is shown in the Spring Batch note.)

## Try it yourself

1. See the dependency: `grep -n -B1 -A2 result4j pom.xml`.
2. See the value flow in a test: `grep -n "Result" src/test/java/plainround/catalogimport/domain/ProductRulesTest.java`.
3. Cause the blocked branch: start two imports at once (see the JDBC note) and read `BLOCKED ... another import holds the lock`.
4. Cause the error branch of `Attempt`: run `java -jar target/restartable-import-0.1.0.jar --command=import --import.file=/nonexistent.csv --run.id=x` and read the `FAILED` report.
5. Explore the library: `unzip -l ~/.m2/repository/com/github/sviperll/result4j/1.2.0/result4j-1.2.0.jar | head`.

## Mistakes people make

- **Treating `Result` as a new place for `null`.** A `Result.success(null)` is allowed but useless; keep values non-null.
- **Catching `Exception` everywhere.** Catch once, at the boundary of the library you call, and return a `Result`; the rest of the code stays free of `try`.
- **Forgetting that `recoverError` returns the success type.** After `.map(a).recoverError(b)` both branches must produce the same type.

## Check yourself

1. What two things can a `Result<Lease, String>` hold? *A lease, or a message saying why not.*
2. Where does a `Result` become an exception here, and why? *`ProductItemProcessor`, because Spring Batch's skip policy works on exception types.*
3. What does `recoverError(ImportReport::blocked)` do? *Turns the error text into a blocked report.*
4. Why is validation returned as a `Result` and not thrown? *An invalid row is an expected outcome, not a bug.*
5. Which class converts exceptions to values? *`Attempt`.*

## Further reading

- Result4J on GitHub (README and API): https://github.com/sviperll/result4j
- Maven coordinates pinned in this repository: `com.github.sviperll:result4j:1.2.0`.
