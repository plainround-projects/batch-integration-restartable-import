# Lombok

## What it is

Lombok is a Java library that writes repetitive code for you while the program is being compiled. You put a short label (an **annotation**) on a class, and Lombok adds the missing methods to the compiled result. For example, a class with three `final` fields needs a constructor that takes three values and stores them; writing it by hand is boring and easy to get wrong. With `@RequiredArgsConstructor` you write nothing, and the compiler output contains the constructor.

How is that possible? The Java compiler allows plug-ins called **annotation processors** that run during compilation and may generate or change code. Lombok is one. Because the generated code only exists in the compiled `.class` file, you do not see it in the `.java` file; an editor needs a Lombok plug-in to know about it.

## Why this repository uses it

Many classes receive their dependencies through a constructor that only copies parameters into fields, and several need a logger. Lombok removes that noise so the code shows only what is special. It is used for constructors, loggers and one getter.

## Where it appears

| Path | What it does with Lombok |
| --- | --- |
| `lombok.config` | Project-wide Lombok settings. |
| `${J}/application/ImportCatalog.java` | `@Slf4j` and `@RequiredArgsConstructor`. |
| `${J}/domain/InvalidProductException.java` | `@Getter`. |
| `${J}/infrastructure/batch/RejectSkipListener.java`, `SpringBatchImportJob.java`, `StaleExecutionRecovery.java`, `TransientFaultInjector.java` | `@RequiredArgsConstructor` (and `@Slf4j` where they log). |
| `${J}/infrastructure/jdbc/AdvisoryRunLock.java`, `JdbcRejectLog.java`, `StepExecutionTable.java` | `@RequiredArgsConstructor`. |
| `${J}/interfaces/ImportRunner.java` | `@Slf4j`, `@RequiredArgsConstructor`. |
| `pom.xml` | The dependency (scope `provided`) and the annotation-processor path. |

## How it works underneath

In `pom.xml` Lombok is a dependency with scope `provided`: it is needed to compile but is not packaged in the final program, since the generated code no longer needs the library. Newer JDKs (23 and later) no longer run annotation processors found on the classpath automatically, so `pom.xml` also names Lombok in the compiler plug-in's `annotationProcessorPaths`; without that, the build would fail with "no constructor" or "cannot find symbol `log`" errors.

The two annotations used here:

- `@RequiredArgsConstructor` generates a constructor with one parameter for every `final` field that has no initial value (and for fields marked `@NonNull`). That makes **constructor injection** (Spring passes dependencies through the constructor) trivial to write and keeps the fields immutable.
- `@Slf4j` generates the line `private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TheClass.class);`, so any method can write `log.info("...")`. SLF4J is the logging façade (see the Logback note).

## Walkthrough of this repository's use

### `lombok.config`

```
config.stopBubbling = true
lombok.addLombokGeneratedAnnotation = true
lombok.log.fieldName = log
```

Line 1, `config.stopBubbling = true`, tells Lombok to stop looking for `lombok.config` files in parent folders. Line 2, `lombok.addLombokGeneratedAnnotation = true`, marks every method Lombok writes with `@lombok.Generated`; JaCoCo (the coverage tool) skips marked methods, so generated constructors do not count as untested code. Line 3, `lombok.log.fieldName = log`, fixes the name of the logger variable that `@Slf4j` creates.

### Constructors

```java
@Slf4j
@RequiredArgsConstructor
public class ImportCatalog {

  private final RunLock lock;
  private final CrashRecovery recovery;
  private final ImportJob job;
  private final RejectLog rejects;
```

`@RequiredArgsConstructor` writes `public ImportCatalog(RunLock lock, CrashRecovery recovery, ImportJob job, RejectLog rejects)` that stores each parameter in its field (all four fields are `final` with no initial value). `ApplicationWiring` calls it as `new ImportCatalog(lock, recovery, job, rejects)`, in field order.

```java
@RequiredArgsConstructor
public class RejectSkipListener implements SkipListener<ProductRow, ProductRow> {

  private final String sourceFile;
  private final JdbcRejectLog rejects;
```

The same on a class with two fields: `new RejectSkipListener(file, rejects)`.

```java
@Slf4j
@RequiredArgsConstructor
public class TransientFaultInjector implements ItemWriter<ProductRow> {

  private final ItemWriter<ProductRow> delegate;
  private final int everyNthChunk;
  private final AtomicLong chunks = new AtomicLong();
  private final Set<Integer> failedOnce = new HashSet<>();
  private final AtomicLong injected = new AtomicLong();
```

Here the class has five fields, but three of them have an initial value (`= new AtomicLong()`, `= new HashSet<>()`), so the generated constructor takes only the first two, `delegate` and `everyNthChunk`: `new TransientFaultInjector(upsertWriter, 5)`.

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportRunner implements ApplicationRunner, ExitCodeGenerator {

  private final ImportCatalog importCatalog;
  private final GenerateCatalogFile generator;
  private final StepExecutionTable table;
  private final JdbcTemplate jdbc;
  private volatile int exitCode;
```

`exitCode` is not `final`, so the generated constructor takes the first four fields; Spring supplies them.

### Loggers

`@Slf4j` appears on `ImportCatalog` (above), `ImportRunner`, `StaleExecutionRecovery` and `TransientFaultInjector`. It adds `private static final Logger log = LoggerFactory.getLogger(TheClass.class);`, so the code can write:

```java
@Slf4j
@RequiredArgsConstructor
public class StaleExecutionRecovery implements CrashRecovery {
```

and then `log.warn("recovered crashed execution id={}", execution.getId());` further down.

### A getter

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
```

`@Getter` on the class writes `public ProductRow getRow()` for the field `row`; the Spring Batch skip listener can then read the row from the exception.

## Try it yourself

1. See what Lombok generated: `./mvnw -q -DskipTests package` then `javap -p -cp target/classes plainround.catalogimport.application.ImportCatalog`. Expect a constructor with four parameters and a `log` field, neither in the source.
2. Break the annotation path: comment out `annotationProcessorPaths` in `pom.xml` and run `./mvnw -q compile`; it fails with "cannot find symbol: variable log". Put it back.
3. Add a field: add `private final String name;` to `RejectSkipListener`; `CatalogJobConfiguration` no longer compiles because the generated constructor wants three arguments. Undo it.
4. Show the getter: `javap -p -cp target/classes plainround.catalogimport.domain.InvalidProductException` lists `getRow()`.
5. Coverage: after `make test` open `target/site/jacoco/index.html`; generated constructors are not listed as missed.

## Mistakes people make

- **Forgetting the annotation-processor path on a new JDK.** The build then fails with errors about missing constructors or the missing `log` variable. This repository names Lombok under `annotationProcessorPaths` in `pom.xml`.
- **Using `@Data` or setters on domain objects.** They make objects mutable. This repository uses records for data and Lombok only for constructors and loggers.
- **Reading the `.java` file and not finding the constructor.** It is generated; use `javap` on the `.class` file to see it.
- **Field order.** `@RequiredArgsConstructor` takes parameters in field order; reorder the fields and every `new ...(a, b)` call changes meaning. Wiring code that uses `new` directly must follow the field order.

## Check yourself

1. What does `@RequiredArgsConstructor` generate? *A constructor with a parameter for each `final` field without an initial value.*
2. Why does `TransientFaultInjector`'s constructor take only two arguments? *The other fields have initial values.*
3. What does `@Slf4j` add? *A static `log` field for the class.*
4. What does `@Getter` do on `InvalidProductException`? *Adds `getRow()`.*
5. Why mark generated code for JaCoCo? *So coverage numbers measure code a person wrote.*

## Further reading

- Lombok features overview: https://projectlombok.org/features/
- `@RequiredArgsConstructor`: https://projectlombok.org/features/constructor
- `@Slf4j` (log): https://projectlombok.org/features/log
- Configuration keys (`lombok.config`): https://projectlombok.org/features/configuration
