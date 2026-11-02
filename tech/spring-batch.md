# Spring Batch 5.2

## What it is

Spring Batch is a Java library for **batch jobs**. A batch job is a program that processes a large amount of data in one go, without a person waiting at a screen: "recalculate every customer's points tonight", "load this file of 200,000 products". Writing such a job from scratch means solving the same hard problems every time: reading a lot of data without running out of memory, writing it in safe groups, remembering how far you got so a crash does not force you to start over, counting what happened, and retrying or skipping bad items. Spring Batch solves these once and gives you a vocabulary:

- A **job** is the whole piece of work (for example "import the product catalog"). It is made of one or more steps.
- A **step** is one phase of the job. The most common step is *chunk-oriented*: it reads items, processes them and writes them.
- An **item** is one unit of data (one account, one product row).
- An **item reader** produces items one at a time; an **item processor** changes or checks one item (or drops it); an **item writer** receives a whole *chunk* of processed items and writes them together.
- A **chunk** is a group of items (here 1,000) that is read, processed and written inside a single database **transaction**. A transaction is a group of database changes that either all happen or none of them does. When a chunk's transaction commits, Spring Batch also saves its progress, so a restart knows where to continue.
- The **job repository** is the set of database tables (`batch_job_instance`, `batch_job_execution`, `batch_step_execution` and friends) where Spring Batch stores what ran, with what parameters, and with what counts and status.
- A **job instance** is a job plus its identifying parameters (here the month and a run id). An **execution** is one attempt to run an instance. If an execution fails, running the same instance again creates a new execution that continues the work; if it completed, Spring Batch refuses to run it again.

## Why this repository uses it

The lesson is "an import that survives a crash". Spring Batch gives the three things it needs: a chunked read-process-write loop, a **job repository** that remembers how far the reader got at every commit (so a restart resumes instead of starting over), and **fault tolerance**: *skip* a bad row up to a limit, *retry* a transient failure a few times. The repository's own code decides what a bad row is, where its record goes, and how to recover when a process is killed.

## Where it appears

| Path | What it does with Spring Batch |
| --- | --- |
| `${B}/CatalogJobConfiguration.java` | The job, the one step with skip and retry, the file reader, the upsert writer. |
| `${B}/ProductLineMapper.java` | Turns one CSV line into a `ProductRow`, keeping its line number. |
| `${B}/ProductItemProcessor.java` | Validates a row or throws the one skippable exception. |
| `${B}/RejectSkipListener.java` | Records every skipped row. |
| `${B}/TransientFaultInjector.java` | A writer wrapper that fails on purpose for the retry demonstration. |
| `${B}/GracefulShutdown.java` | Stops the step after the current chunk on SIGTERM. |
| `${B}/StaleExecutionRecovery.java` | Repairs the job repository after `kill -9`. |
| `${B}/SpringBatchImportJob.java` | Starts the job and turns the finished execution into a report. |
| `sql/001_spring_batch_schema.sql`, `002_catalog_schema.sql` | The job repository tables and the `step_summary` view (see the PostgreSQL note). |

## How it works underneath

When the program starts the job, Spring Batch does this, in order:

1. It looks in the job repository tables for an instance with the same job name and the same parameters. If none exists it creates one; if one exists and is complete it refuses; if one exists and failed it will restart it.
2. It writes a new row in `batch_job_execution` with status `STARTED`.
3. For each step it writes a row in `batch_step_execution`, then runs the step's loop: *open* the reader and writer (restoring the saved position of the reader from the step's **execution context**, a small key-value store saved with each commit), then repeat: start a transaction, read items until the chunk is full (or the input ends), pass each item through the processor, hand the whole list of results to the writer, commit the transaction, and update the counters and execution context in the same transaction.
4. When the reader returns "no more items" the step ends with status `COMPLETED`; if an exception escapes, the transaction rolls back, the step gets status `FAILED` and so does the job.
5. The job execution row gets its end time and final status. Because the counters and the reader position were committed together with the data of each chunk, they never disagree with what is in the database.

A step can be **partitioned**. A *manager step* asks a *partitioner* for a map of named **execution contexts** (for example a name and a range of ids), then starts one copy of a *worker step* per entry, each with its own context. The copies can run at the same time on different threads (a **task executor** supplies the threads). The manager step is complete when all workers are complete. Each worker has its own row in `batch_step_execution`, so a restart can skip the workers that already completed and rerun only the failed ones. This repository does not partition: the import is one step on one thread, and partitioning is the way to scale it further.

**Step scope.** A Spring bean marked `@StepScope` is created fresh for every step execution, and it can read values from the job parameters and the step's execution context with expressions such as `#{jobParameters['month']}`. That is what lets each worker have its own reader with its own id range: one bean definition, many instances.

Two more ideas matter in this project.

**Skip and retry.** A *fault-tolerant* step wraps the chunk loop. If an exception of a *skippable* type is thrown while processing an item, the item is skipped (counted, and passed to a skip listener) and the chunk continues; once the number of skips exceeds the **skip limit**, the step fails with `SkipLimitExceededException`. If an exception of a *retryable* type is thrown by the writer, the whole chunk is tried again, up to the **retry limit**; if it still fails, the step fails. Any other exception fails the step immediately.

**The reader's position.** The flat-file reader counts the lines it has read and saves that count in the step's execution context at every commit (key `productReader.read.count`, named after the reader's `name`). On restart it reads and discards that many lines, then continues. The saved count is committed in the same transaction as the written rows, so the two always agree.

## Walkthrough of this repository's use

A **bean** is an object Spring creates and keeps (the Spring Boot note explains this), and a method annotated `@Bean` inside a `@Configuration` class is a recipe for one; Spring passes the method's parameters in from other beans.

### `CatalogJobConfiguration.java`: the job

```java
package plainround.catalogimport.infrastructure.batch;

import javax.sql.DataSource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import plainround.catalogimport.domain.InvalidProductException;
import plainround.catalogimport.domain.ProductRow;
import plainround.catalogimport.infrastructure.jdbc.JdbcRejectLog;

/**
 * The job: one chunk-oriented step. Reader, processor, writer; skip only {@link
 * InvalidProductException} up to the limit; retry only transient database errors; write by upsert
 * so that a chunk written twice leaves one row.
 */
@Configuration
@EnableConfigurationProperties(ImportProperties.class)
public class CatalogJobConfiguration {

  public static final String JOB_NAME = "catalogImport";
  public static final String STEP_NAME = "importStep";

  private static final String UPSERT =
      """
      INSERT INTO product (sku, name, category, price) VALUES (:sku, :name, :category, :price)
      ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, category = EXCLUDED.category, price = EXCLUDED.price
      """;

```

The imports show the parts: `Job`/`Step`/`JobBuilder`/`StepBuilder` build the structure; `FlatFileItemReader` reads a text file line by line; `JdbcBatchItemWriter` writes a list of items with one batched SQL statement; `@StepScope` makes a bean new for each step execution; `@Value` reads an expression; `TransientDataAccessException` is Spring's class for database errors that may go away if you try again. Lines 37 and 38 name the job and the step; these names are stored in the job repository and used by `StaleExecutionRecovery`. Lines 40 to 44 are a Java **text block** (a multi-line string between triple quotes) holding the SQL of the writer: named parameters `:sku`, `:name`, `:category`, `:price`, and `ON CONFLICT (sku) DO UPDATE SET ...`, an **upsert**: insert the row, but if a row with that SKU already exists, update it instead. `EXCLUDED` is PostgreSQL's name for the row that was being inserted. The key is the **business key** (the SKU), not a generated number, so writing the same row twice leaves one row.

```java
  @Bean
  Job catalogImport(JobRepository repository, Step importStep) {
    return new JobBuilder(JOB_NAME, repository)
        .start(importStep)
        .listener(new GracefulShutdown())
        .build();
  }

  @Bean
  Step importStep(
      JobRepository repository,
      PlatformTransactionManager transactions,
      ImportProperties properties,
      FlatFileItemReader<ProductRow> reader,
      ItemWriter<ProductRow> writer,
      RejectSkipListener skipListener) {
    return new StepBuilder(STEP_NAME, repository)
        .<ProductRow, ProductRow>chunk(properties.chunkSize(), transactions)
        .reader(reader)
        .processor(new ProductItemProcessor())
        .writer(writer)
        .faultTolerant()
        .skip(InvalidProductException.class)
        .skipLimit(properties.skipLimit())
        .retry(TransientDataAccessException.class)
        .retryLimit(properties.retryLimit())
        .listener(skipListener)
        .build();
  }
```

`catalogImport` is the job bean: `new JobBuilder(JOB_NAME, repository).start(importStep).listener(new GracefulShutdown()).build()` creates a job with one step and a listener for shutdown signals. `importStep` is the step: `.<ProductRow, ProductRow>chunk(properties.chunkSize(), transactions)` makes it chunk-oriented (1,000 items per transaction by default; the generic arguments are the input and output item types); `.reader(...)`, `.processor(new ProductItemProcessor())` and `.writer(...)` attach the three roles. Then the fault tolerance: `.faultTolerant()` switches it on; `.skip(InvalidProductException.class)` makes that one exception type skippable; `.skipLimit(properties.skipLimit())` sets the limit (500); `.retry(TransientDataAccessException.class)` makes that type retryable; `.retryLimit(properties.retryLimit())` is the number of attempts (3); `.listener(skipListener)` registers the listener that records skipped rows. A constraint violation (`DataIntegrityViolationException`) is neither skippable nor retryable, so it fails the step at once, which is correct: it would fail the same way every time.

```java
  @Bean
  @StepScope
  FlatFileItemReader<ProductRow> reader(@Value("#{jobParameters['file']}") String file) {
    return new FlatFileItemReaderBuilder<ProductRow>()
        .name("productReader")
        .resource(new FileSystemResource(file))
        .linesToSkip(1)
        .lineMapper(new ProductLineMapper())
        .build();
  }

  @Bean
  JdbcBatchItemWriter<ProductRow> upsertWriter(DataSource dataSource) {
    return new JdbcBatchItemWriterBuilder<ProductRow>()
        .dataSource(dataSource)
        .sql(UPSERT)
        .itemSqlParameterSourceProvider(CatalogJobConfiguration::parameters)
        .assertUpdates(false)
        .build();
  }

  @Bean
  ItemWriter<ProductRow> writer(
      JdbcBatchItemWriter<ProductRow> upsertWriter, ImportProperties properties) {
    return properties.transientFaultEveryNthChunk() > 0
        ? new TransientFaultInjector(upsertWriter, properties.transientFaultEveryNthChunk())
        : upsertWriter;
  }
```

`reader` is step-scoped: `@Value("#{jobParameters['file']}")` reads the job parameter `file` when the step starts. `FlatFileItemReaderBuilder` sets: `name("productReader")` (the key under which its position is saved), `resource(new FileSystemResource(file))` (read from the file system), `linesToSkip(1)` (skip the header line) and `lineMapper(new ProductLineMapper())`. `upsertWriter` builds the writer: `itemSqlParameterSourceProvider(CatalogJobConfiguration::parameters)` says how to turn an item into named parameters (the private method at the bottom); `assertUpdates(false)` stops the writer from failing when a statement reports "0 rows updated" (an upsert that changes nothing is fine). `writer` chooses what the step really uses: the `TransientFaultInjector` when the setting `transientFaultEveryNthChunk` is above 0, else the plain writer (the conditional `? :` operator).

```java

  @Bean
  JdbcRejectLog rejectLog(JdbcTemplate jdbc) {
    return new JdbcRejectLog(jdbc);
  }

  @Bean
  @StepScope
  RejectSkipListener skipListener(
      @Value("#{jobParameters['file']}") String file, JdbcRejectLog rejects) {
    return new RejectSkipListener(file, rejects);
  }

  private static MapSqlParameterSource parameters(ProductRow row) {
    return new MapSqlParameterSource()
        .addValue("sku", row.sku())
        .addValue("name", row.name())
        .addValue("category", row.category())
        .addValue("price", row.price());
  }
}
```

`rejectLog` creates the reject log (JDBC note). `skipListener` is step-scoped and gets the file name from the job parameters, so each import's skipped rows are recorded under that file. `parameters` converts a `ProductRow` into a `MapSqlParameterSource` by adding each value under the name used in the SQL.

### The reader, the processor and the listener

```java
package plainround.catalogimport.infrastructure.batch;

import java.util.Arrays;
import java.util.List;
import org.springframework.batch.item.file.LineMapper;
import plainround.catalogimport.domain.ProductRow;
import plainround.catalogimport.domain.ProductRules;

/**
 * Maps a CSV line to a row that keeps its line number. A short or garbled line becomes a row the
 * rules refuse.
 */
public class ProductLineMapper implements LineMapper<ProductRow> {

  @Override
  public ProductRow mapLine(String line, int lineNumber) {
    List<String> fields = Arrays.asList(line.split(",", -1));
    return new ProductRow(
        lineNumber,
        field(fields, 0),
        field(fields, 1),
        field(fields, 2),
        ProductRules.parsePrice(field(fields, 3)));
  }

  private String field(List<String> fields, int index) {
    return index < fields.size() ? fields.get(index).strip() : "";
  }
}
```

A **line mapper** converts one text line into an item. Spring Batch calls `mapLine(line, lineNumber)` with the physical line number (the header is line 1, so the first data row is line 2). `line.split(",", -1)` cuts the line at commas; the `-1` keeps empty trailing fields (without it `a,b,,` would lose them). `Arrays.asList` wraps the array in a list. `field(fields, i)` returns field `i` trimmed, or an empty text if the line is short; a garbled line therefore becomes a row that the rules refuse, not an exception from the reader. The price field goes through `ProductRules.parsePrice`.

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

The processor's whole job: `ProductRules.validate(row)` returns a `Result`; `.orOnErrorThrow(rejection -> new InvalidProductException(row, rejection))` returns the row when it is a success and throws the exception built by the lambda when it is an error. It writes nothing: a processor that touched the database would break the skip and restart logic. This is the **only** place where a `Result` turns into an exception, because Spring Batch's skip policy is driven by exceptions.

```java
package plainround.catalogimport.infrastructure.batch;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.SkipListener;
import plainround.catalogimport.domain.ProductRow;
import plainround.catalogimport.domain.RejectedRow;
import plainround.catalogimport.infrastructure.jdbc.JdbcRejectLog;

/**
 * Records every row the processor refuses, with its line number and the reason, in the chunk's
 * transaction.
 */
@RequiredArgsConstructor
public class RejectSkipListener implements SkipListener<ProductRow, ProductRow> {

  private final String sourceFile;
  private final JdbcRejectLog rejects;

  @Override
  public void onSkipInProcess(ProductRow row, Throwable reason) {
    rejects.record(sourceFile, RejectedRow.of(row, reason.getMessage()));
  }
}
```

A **skip listener** is called when an item is skipped. `onSkipInProcess(item, reason)` is called for a skip that happened in the processor; it records the row (`RejectedRow.of(row, reason.getMessage())`: the message of the exception is the rule that failed) through the reject log. Because the call happens inside the chunk's transaction, the record is committed or rolled back together with the chunk.

### Settings

```java
package plainround.catalogimport.infrastructure.batch;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The job's settings, from the environment or {@code application.yml}, validated at startup.
 *
 * @param chunkSize items read, processed and written in one transaction
 * @param skipLimit skips allowed over the whole step; one more fails it
 * @param retryLimit attempts for a transient write failure
 * @param transientFaultEveryNthChunk 0 for none; otherwise the writer fails the Nth chunk once (the
 *     lesson's step 6)
 */
@Validated
@ConfigurationProperties("import")
public record ImportProperties(
    @DefaultValue("1000") @Min(1) int chunkSize,
    @DefaultValue("500") @Min(0) int skipLimit,
    @DefaultValue("3") @Min(1) int retryLimit,
    @DefaultValue("0") @Min(0) int transientFaultEveryNthChunk) {}
```

A record bound to settings that start with `import.` (the Spring Boot note explains `@ConfigurationProperties`, `@Validated`, `@DefaultValue` and `@Min`). The defaults are the lesson's: chunk 1,000, skip limit 500 (`@Min(0)`), 3 retries, no injected faults.

### Starting the job and reporting

```java
package plainround.catalogimport.infrastructure.batch;

import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import plainround.catalogimport.application.ImportJob;
import plainround.catalogimport.application.ImportReport;
import plainround.catalogimport.application.ImportReport.Status;
import plainround.catalogimport.application.ImportRequest;

/**
 * Launches the job with the request's identity as job parameters. The same file and run id after a
 * crash is the same job instance, so the reader resumes from the last committed chunk; a new run id
 * starts a new instance.
 */
@RequiredArgsConstructor
public class SpringBatchImportJob implements ImportJob {

  private final JobLauncher launcher;
  private final Job job;
  private final JobExplorer explorer;

  @Override
  public ImportReport launch(ImportRequest request) {
    return Attempt.of(() -> launcher.run(job, parameters(request)))
        .map(this::report)
        .recoverError(problem -> failure(request, problem));
  }

  private JobParameters parameters(ImportRequest request) {
    return new JobParametersBuilder()
        .addString("file", request.file().toString())
        .addString("run.id", request.runId())
        .toJobParameters();
  }

  private ImportReport report(JobExecution execution) {
    StepExecution step = execution.getStepExecutions().stream().findFirst().orElseThrow();
    boolean resumed = explorer.getJobExecutions(execution.getJobInstance()).size() > 1;
```

This class implements the `ImportJob` port. `launch` runs the launcher inside `Attempt.of` (the Result4J note) and maps the outcome. `parameters` builds the identifying parameters `file` and `run.id` as strings: the pair is the job instance's identity, so the same pair after a crash is a restart.

```java
    return new ImportReport(
        status(execution.getStatus()),
        step.getReadCount(),
        step.getWriteCount(),
        step.getSkipCount(),
        step.getCommitCount(),
        resumed,
        causes(execution.getExitStatus().getExitDescription()));
  }

  /**
   * The exit description holds whole stack traces; the report keeps the lines that name the causes.
   */
  private static String causes(String description) {
    String text =
        description
            .lines()
            .map(String::strip)
            .filter(line -> !line.isEmpty() && !line.startsWith("at ") && !line.startsWith("..."))
            .collect(Collectors.joining(" "));
    return text.length() > 300 ? text.substring(0, 300) + "..." : text;
  }

  private Status status(BatchStatus batchStatus) {
    return switch (batchStatus) {
      case COMPLETED -> Status.COMPLETED;
      case STOPPED, STOPPING -> Status.STOPPED;
      default -> Status.FAILED;
    };
  }

  private ImportReport failure(ImportRequest request, Exception problem) {
    return problem instanceof JobInstanceAlreadyCompleteException
        ? ImportReport.alreadyComplete(request.runId())
        : new ImportReport(Status.FAILED, 0, 0, 0, 0, false, problem.toString());
  }
}
```

`report` reads the first (only) step execution and builds the report from its counters (`getReadCount`, `getWriteCount`, `getSkipCount`, `getCommitCount`); `resumed` is true when the job instance has more than one execution. `causes` shortens a stack trace to the lines that name causes. `status` is a `switch` expression that maps Spring Batch's `BatchStatus` to the repository's own `Status` (`default -> Status.FAILED` covers every other value). `failure` turns an exception into a report: `JobInstanceAlreadyCompleteException` (the same parameters already completed) becomes the `ALREADY_COMPLETE` report; anything else becomes `FAILED` with the exception text.

### Crash recovery and shutdown

```java
package plainround.catalogimport.infrastructure.batch;

import java.time.LocalDateTime;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import plainround.catalogimport.application.CrashRecovery;

/**
 * After a {@code kill -9} the job repository still says STARTED, and Spring Batch will not start a
 * job instance with a running execution. This marks such executions (and their steps) FAILED,
 * keeping the counts of the last commit, so the same parameters restart from there. Call it only
 * while holding the run lock, which proves the process is dead.
 */
@Slf4j
@RequiredArgsConstructor
public class StaleExecutionRecovery implements CrashRecovery {

  private static final String NOTE = "process died; marked failed by the next start";

  private final JobExplorer explorer;
  private final JobRepository repository;

  @Override
  public int recoverCrashedRuns() {
    Set<JobExecution> running = explorer.findRunningJobExecutions(CatalogJobConfiguration.JOB_NAME);
    running.forEach(this::markFailed);
    return running.size();
  }

```

After `kill -9` the process cannot mark its execution as failed, so the repository still says `STARTED`, and Spring Batch refuses to start an instance that has a running execution. This class repairs that. `JobExplorer` is the read-only view of the job repository; `findRunningJobExecutions(name)` returns the executions whose status says they run. The class is called only while the run lock is held, which proves no live process owns them.

```java
  private void markFailed(JobExecution execution) {
    execution.getStepExecutions().stream()
        .filter(step -> step.getStatus().isRunning())
        .forEach(this::fail);
    execution.setStatus(BatchStatus.FAILED);
    execution.setExitStatus(ExitStatus.FAILED.addExitDescription(NOTE));
    execution.setEndTime(LocalDateTime.now());
    repository.update(execution);
    log.warn("recovered crashed execution id={}", execution.getId());
  }

  private void fail(StepExecution step) {
    step.setStatus(BatchStatus.FAILED);
    step.setExitStatus(ExitStatus.FAILED.addExitDescription(NOTE));
    step.setEndTime(LocalDateTime.now());
    repository.update(step);
  }
}
```

`markFailed` marks the running steps failed (`fail` sets status `FAILED`, an exit status with a note, an end time, and saves with `repository.update`), then sets the job execution failed with the note `process died; marked failed by the next start`. Counts and the saved reader position are left untouched: they are what the last committed chunk wrote, and the restart continues from there.

```java
package plainround.catalogimport.infrastructure.batch;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.StepExecution;

/**
 * On SIGTERM or SIGINT, asks the running step to stop after the chunk in flight and waits for it.
 * The chunk commits, the position is saved, and the next start resumes cleanly. {@code kill -9}
 * cannot be handled; that is the point of the crash test.
 */
public class GracefulShutdown implements JobExecutionListener {

  private static final long WAIT_SECONDS = 60;
  private volatile CompletableFuture<Void> finished = new CompletableFuture<>();

  @Override
  public void beforeJob(JobExecution execution) {
    finished = new CompletableFuture<>();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> stopAndWait(execution), "graceful-stop"));
  }

  @Override
  public void afterJob(JobExecution execution) {
    finished.complete(null);
  }

  private void stopAndWait(JobExecution execution) {
    execution.getStepExecutions().forEach(StepExecution::setTerminateOnly);
    finished.orTimeout(WAIT_SECONDS, TimeUnit.SECONDS).exceptionally(timeout -> null).join();
  }
}
```

A **shutdown hook** is a thread the JVM starts when it is asked to end (on `SIGTERM`, the polite request, or Ctrl-C = `SIGINT`). `beforeJob` registers a hook that calls `stopAndWait`: it asks every step execution to stop after the current chunk (`StepExecution::setTerminateOnly`) and waits up to 60 seconds for the job to finish (`CompletableFuture`, a promise-like object completed by `afterJob`; `orTimeout` fails the wait after the time; `exceptionally(t -> null)` swallows that failure; `join()` waits). The chunk commits and the position is saved, so the next start resumes cleanly. `kill -9` (SIGKILL) cannot be caught by any program, which is why `StaleExecutionRecovery` exists.

### The retry demonstration

```java
package plainround.catalogimport.infrastructure.batch;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.dao.TransientDataAccessResourceException;
import plainround.catalogimport.domain.ProductRow;

/**
 * Wraps the writer and makes every Nth chunk fail once with a transient database error (the
 * lesson's step 6), so the retry policy can be seen working. A retried chunk reaches the writer
 * again with the same first row and succeeds.
 */
@Slf4j
@RequiredArgsConstructor
public class TransientFaultInjector implements ItemWriter<ProductRow> {

  private final ItemWriter<ProductRow> delegate;
  private final int everyNthChunk;
  private final AtomicLong chunks = new AtomicLong();
  private final Set<Integer> failedOnce = new HashSet<>();
  private final AtomicLong injected = new AtomicLong();

  @Override
  public void write(Chunk<? extends ProductRow> chunk) throws Exception {
    if (shouldFail(chunk)) {
      injected.incrementAndGet();
      log.warn(
          "injected transient failure for the chunk starting at line {}",
          chunk.getItems().getFirst().lineNumber());
      throw new TransientDataAccessResourceException("injected transient failure");
    }
    delegate.write(chunk);
  }

  private boolean shouldFail(Chunk<? extends ProductRow> chunk) {
    int first = chunk.getItems().getFirst().lineNumber();
    boolean fresh = !failedOnce.contains(first);
    return everyNthChunk > 0
        && fresh
        && chunks.incrementAndGet() % everyNthChunk == 0
        && failedOnce.add(first);
  }

  /** How many failures were injected, for the tests and the log. */
  public long injected() {
    return injected.get();
  }
}
```

The writer wrapper for the lesson's step 6. It is created only when the setting asks for it. `shouldFail` decides: only every N-th chunk (`chunks.incrementAndGet() % everyNthChunk == 0`), and only once per chunk (`failedOnce` remembers the first line number of chunks that already failed, so the retry of the same chunk passes). When it fails it logs a warning, counts the injection, and throws `TransientDataAccessResourceException`, a retryable type. `AtomicLong` is a counter that is safe to use from several threads.

## Try it yourself

1. Run the clean import and read the report: `make run`. Expect `COMPLETED read=200000 written=199900 skipped=100 commits=201`.
2. Fail the skip limit: `make too-many-bad`. Expect `FAILED ... SkipLimitExceededException: Skip limit of '500' exceeded`.
3. See retries: `make retry-demo`. Expect `transient failures injected and retried: 4` and still `skipped=100`.
4. Crash it: `make crash-test ROUNDS=2`. Each round prints the job repository right after the `kill -9` (status `STARTED`) and after the restart (first execution `FAILED`, second `COMPLETED`).
5. Look at the repository: `make summary` prints one line per step execution; `make queries` shows the same through SQL.

## Mistakes people make

- **A reader that is not thread-safe, shared by several threads.** A database cursor reader keeps a position; two threads sharing one would steal each other's rows. The fix is one reader per worker, which is exactly what `@StepScope` plus partitioning gives. (This repository's reader is used by one thread only.)
- **Writing with "add" instead of "set".** If a chunk is written twice (a retry, a restart, a second run) an increment doubles the value. A write keyed by the natural key, with `ON CONFLICT ... DO UPDATE SET x = EXCLUDED.x`, gives the same result every time.
- **Changing the job parameters on a restart.** The parameters are the job instance's identity. A different value starts a brand-new instance from the beginning instead of continuing the failed one.
- **A connection pool smaller than the work needs.** Each running worker may hold one connection for its reader's cursor and need a second one to write. With fewer connections than that, workers wait for each other and the job stalls or fails. The size of the pool must grow with the number of partitions. (This repository runs one worker, and its pool of 5 is plenty.)
- **A huge chunk.** A chunk is held in memory and committed at once. Too large a chunk uses a lot of memory and loses a lot of work on a crash; too small a chunk spends its time committing.
- **Expecting `kill -9` to leave a clean state.** A killed process cannot mark its execution failed; it stays `STARTED` until something repairs it. (The restartable-import project in this track handles that case.)

This repository avoids them by: an upsert keyed by SKU, the same job parameters on a restart, skip limited to one exception type, retry limited to transient errors, and recovery under a lock.

## Check yourself

1. What is skipped and what is retried here? *Rows that throw `InvalidProductException` are skipped (up to 500); a chunk whose write throws a `TransientDataAccessException` is retried (3 attempts).*
2. Why does the processor not write anything? *Skip and restart rely on the chunk's transaction; side effects in the processor would not roll back.*
3. What does `linesToSkip(1)` do? *Skips the header line.*
4. Why can a `kill -9` not be handled in code? *The process is ended by the operating system at once; no hook runs.*
5. What does `StaleExecutionRecovery` change in the repository? *It marks running executions and steps `FAILED`, keeping their counts.*

## Further reading

- Spring Batch reference documentation, version 5.2: https://docs.spring.io/spring-batch/reference/5.2/
- Chunk-oriented processing: https://docs.spring.io/spring-batch/reference/5.2/step/chunk-oriented-processing.html
- Partitioning (scaling and parallel processing): https://docs.spring.io/spring-batch/reference/5.2/scalability.html
- Step scope: https://docs.spring.io/spring-batch/reference/5.2/step/late-binding.html
