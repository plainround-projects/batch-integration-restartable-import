# Spring Boot 3.5

## What it is

Spring is a very widely used Java toolkit for building applications out of small parts that are given to each other, instead of each part building the others itself. **Spring Boot** is the layer on top that removes the setup work: it looks at which libraries are on the classpath (the list of code the program can use), creates sensible default configuration for them, and starts the program. You add a library, and Boot sets it up (this is called **auto-configuration**).

The central idea is **dependency injection**. Imagine a class `ImportCatalog` that needs an `ImportJob`. Without Spring it would write `new SpringBatchImportJob(...)` itself, which ties it to one implementation and makes testing hard. With Spring, the class only declares what it needs (a constructor parameter), and Spring creates the objects and passes them in. The objects Spring creates and manages are called **beans**; the box that holds them is the **application context**.

You tell Spring about beans in two ways. A class annotated `@Component` (or `@Service`, `@Configuration`) is found by **component scanning** and one instance is created. A method annotated `@Bean` inside a `@Configuration` class is a recipe: Spring calls the method and keeps the returned object as a bean, giving the method its parameters from other beans.

## Why this repository uses it

Spring Batch runs inside a Spring application, and Boot is the simplest way to start one. Boot also reads the settings (database address, chunk size, skip limit) from the environment, builds the database connection pool, and gives the program its command-line shape: it is started with `--command=import` and decides what to do.

## Where it appears

| Path | What it does with Spring Boot |
| --- | --- |
| `${I}/CatalogImportApplication.java` | The `main` method: starts Boot and turns the result into an exit code. |
| `${I}/ApplicationWiring.java` | The composition root: the one place where the use case is given its adapters. |
| `${I}/ImportRunner.java` | An `ApplicationRunner`: reads the command line and runs `import`, `generate`, `summary` or `health`. |
| `${B}/ImportProperties.java` | The typed, validated settings. |
| `src/main/resources/application.yml` | Default settings and the environment variables that override them. |
| `pom.xml` | Pulls in Spring Boot and its starters. |

## How it works underneath

`SpringApplication.run(MainClass.class, args)` does the following. It builds an **environment**: a collection of settings gathered from several places, in this order of priority (the first that has a value wins): command-line arguments such as `--import.chunk-size=500`, operating-system **environment variables**, the file `application.yml` on the classpath, and defaults written in code. An environment variable is a named text value that the shell passes to a program (for example `IMPORT_CHUNK_SIZE=500 java -jar app.jar`). Boot's **relaxed binding** lets the same setting be written `import.chunk-size` in a file, `IMPORT_CHUNK_SIZE` as an environment variable and `--import.chunk-size=500` on the command line.

Next Boot scans the packages named by `@SpringBootApplication` for annotated classes, applies the auto-configurations for the libraries it finds (a database connection pool, the batch infrastructure, the logging system), creates every bean in dependency order, and calls any `ApplicationRunner` beans. When `run` returns, the program normally ends unless something keeps a non-daemon thread alive. A **daemon thread** is a background thread that does not keep the program alive; a normal (non-daemon) thread does.

`SpringApplication.exit(context)` closes the application context in an orderly way (stopping beans, closing the database pool) and returns the **exit code** collected from beans that implement `ExitCodeGenerator`. `System.exit(code)` then ends the process with that code. The exit code is how a program tells the shell whether it succeeded: 0 means success, anything else means failure.

## Walkthrough of this repository's use

### `CatalogImportApplication.java`: the entry point

```java
package plainround.catalogimport.interfaces;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point. The job does not start by itself; {@link ImportRunner} runs the command named on the
 * command line.
 */
@SpringBootApplication(scanBasePackages = "plainround.catalogimport")
public class CatalogImportApplication {

  public static void main(String[] args) {
    System.exit(
        SpringApplication.exit(SpringApplication.run(CatalogImportApplication.class, args)));
  }
}
```

`@SpringBootApplication(scanBasePackages = "plainround.catalogimport")` is three annotations in one: it marks a configuration class, switches on auto-configuration, and scans the named package and everything below it for `@Component` and `@Configuration` classes. `main` is where the operating system enters the program. It runs the application, hands the context to `SpringApplication.exit` and passes the number it returns to `System.exit`. So the program's exit code is the report's: 0 completed, 1 failed or stopped, 2 unknown command, 3 already complete, 4 blocked. The Javadoc notes the job does not start by itself: in `application.yml` `spring.batch.job.enabled` is false, otherwise Boot would run the job at startup without parameters.

### `ApplicationWiring.java`: the composition root

A **composition root** is the single place where the parts of a program are plugged together. The use case in `application` knows only interfaces; this class chooses the real implementations.

```java
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

```

The imports show every layer. `@Configuration` (line 20) says this class defines beans.

```java
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
```

`importCatalog` builds the use case from four adapters: `new AdvisoryRunLock(dataSource)` is the `RunLock` port, `new StaleExecutionRecovery(explorer, repository)` is the `CrashRecovery` port, `new SpringBatchImportJob(launcher, catalogImport, explorer)` is the `ImportJob` port, and `rejects` (the `JdbcRejectLog` bean) is the `RejectLog` port. The parameter named `catalogImport` is how Spring picks the `Job` bean with that method name. `generateCatalogFile` and `stepExecutionTable` create the two other helpers.

### `ImportRunner.java`: the command line

`ApplicationRunner` is a Boot interface: every bean that implements it has its `run` method called after startup. `ExitCodeGenerator` lets the bean supply the exit code.

```java
package plainround.catalogimport.interfaces;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import plainround.catalogimport.application.GenerateCatalogFile;
import plainround.catalogimport.application.ImportCatalog;
import plainround.catalogimport.application.ImportReport;
import plainround.catalogimport.application.ImportRequest;
import plainround.catalogimport.infrastructure.jdbc.StepExecutionTable;

/**
 * The command-line program. {@code --command=import|generate|summary|health}. Results go to
 * standard output, the structured log to standard error; the exit code is the report's.
 */
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

`@Component` makes the class a bean found by scanning; `@RequiredArgsConstructor` (Lombok) writes the constructor for the four `final` fields; `@Slf4j` adds a logger. `System.out` is **standard output** (results); the JSON log goes to **standard error** (the Logback note), so `> report.txt` captures only the report.

```java
  @Override
  public void run(ApplicationArguments args) {
    Map<String, Function<ApplicationArguments, Integer>> commands =
        Map.of(
            "import",
            this::importCommand,
            "generate",
            this::generateCommand,
            "summary",
            this::summaryCommand,
            "health",
            this::healthCommand);
    String name = option(args, "command", "import");
    exitCode = commands.getOrDefault(name, unknown(name)).apply(args);
  }

  @Override
  public int getExitCode() {
    return exitCode;
  }

  private int importCommand(ApplicationArguments args) {
    ImportRequest request =
        new ImportRequest(
            Path.of(option(args, "import.file", "data/products.csv")),
            option(args, "run.id", "default"),
            Path.of(option(args, "import.reject-file", "rejects.txt")));
    ImportReport report = importCatalog.run(request);
    System.out.println(describe(report));
    return report.exitCode();
  }

```

`run` builds a map from command name to the method that handles it (`this::importCommand` is a method reference), reads the command from `--command=` (default `import`), looks it up with `getOrDefault` (falling back to the unknown-command handler) and calls it; the integer it returns becomes the exit code. `importCommand` reads `--import.file`, `--run.id` and `--import.reject-file` (with defaults), runs the use case, prints the report line and returns the report's exit code.

```java
  private String describe(ImportReport report) {
    return "%s read=%d written=%d skipped=%d commits=%d resumed=%s %s"
        .formatted(
            report.status(),
            report.read(),
            report.written(),
            report.skipped(),
            report.commits(),
            report.resumed(),
            report.message() == null ? "" : report.message())
        .strip();
  }

  private int generateCommand(ApplicationArguments args) {
    int bad =
        generator.write(
            Path.of(option(args, "out", "data/products.csv")),
            Integer.parseInt(option(args, "rows", "200000")),
            Integer.parseInt(option(args, "bad-every", "2000")),
            Long.parseLong(option(args, "seed", "7")));
    System.out.println(
        "wrote " + option(args, "out", "data/products.csv") + " with " + bad + " bad rows");
    return 0;
  }

  private int summaryCommand(ApplicationArguments args) {
    System.out.println(StepExecutionTable.heading());
    table.lines().forEach(System.out::println);
    System.out.printf("products=%d distinct_skus=%d%n", table.products(), table.distinctSkus());
    return 0;
  }

  private int healthCommand(ApplicationArguments args) {
    Long products = jdbc.queryForObject("SELECT count(*) FROM product", Long.class);
    System.out.println("ok database reachable, " + products + " products");
    return 0;
  }

  private Function<ApplicationArguments, Integer> unknown(String name) {
    return args -> {
      System.err.println("unknown command " + name);
      return 2;
    };
  }

  private static String option(ApplicationArguments args, String name, String fallback) {
    return args.containsOption(name) ? args.getOptionValues(name).getFirst() : fallback;
  }
}
```

`describe` formats the report: `%s` text, `%d` whole numbers, `%s` for the boolean; the ternary prints nothing instead of the word `null` for a missing message. `generateCommand` reads `--out`, `--rows`, `--bad-every` and `--seed` (`Integer.parseInt` and `Long.parseLong` turn text into numbers) and prints how many bad rows were planted. `summaryCommand` prints the heading and a line per step execution from the job repository, then `System.out.printf("products=%d distinct_skus=%d%n", ...)` (`%n` is a line break); the two counts must be equal after a good run. `healthCommand` counts the products (it fails if the database is unreachable). `unknown(name)` returns a handler that prints `unknown command <name>` to **standard error** and returns 2. `option` reads `--name=value` from the arguments or returns the fallback.

### `application.yml`: defaults and the environment

YAML is a settings format (the YAML note).

```yaml
# Configuration from the environment (twelve-factor); the values below are defaults for the compose stack.
spring:
  application:
    name: restartable-import
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:55531/catalog}
    username: ${DB_USER:catalog}
    password: ${DB_PASSWORD:}
    hikari:
      maximum-pool-size: 5
      connection-timeout: 20000
```

`${DB_URL:jdbc:postgresql://localhost:55531/catalog}` means "the environment variable `DB_URL`, or this default": the address of the container started by `make up`. `hikari` is the connection pool; `maximum-pool-size: 5` allows five connections (the import uses one for the reader's job repository work, one for the writer and one for the advisory lock, so five is plenty); `connection-timeout` is how many milliseconds to wait for a free connection.

```yaml
  batch:
    job:
      enabled: false          # the runner starts the job, with parameters, when asked
    jdbc:
      initialize-schema: never # the schema is sql/001_spring_batch_schema.sql
  main:
    banner-mode: off
    web-application-type: none
logging:
  level:
    root: INFO
    org.springframework.batch: WARN

import:
  chunk-size: ${IMPORT_CHUNK_SIZE:1000}
  skip-limit: ${IMPORT_SKIP_LIMIT:500}
  retry-limit: ${IMPORT_RETRY_LIMIT:3}
  transient-fault-every-nth-chunk: ${IMPORT_TRANSIENT_FAULT_EVERY_NTH_CHUNK:0}
```

`spring.batch.job.enabled: false` stops Boot from running the job at startup; `spring.batch.jdbc.initialize-schema: never` stops it from creating the job repository tables (the SQL file does); `main.banner-mode: off` hides the logo; `web-application-type: none` means no web server. `logging.level` sets how chatty each logger is (`WARN` for Spring Batch). The `import:` block lists the job settings with their environment variable names and defaults; they bind to `ImportProperties`.

## Try it yourself

1. Start the health command: `make health` (after `make up db`). Expect `ok database reachable, 0 products` and exit code 0.
2. See the exit code of an unknown command: `java -jar target/restartable-import-0.1.0.jar --command=nope; echo $?` prints `unknown command nope` and `2`.
3. Override a setting three ways: `IMPORT_SKIP_LIMIT=5 make run`, `java -jar target/restartable-import-0.1.0.jar --import.skip-limit=5 ...`, and by editing `application.yml`. The first two fail with `Skip limit of '5' exceeded`.
4. Trigger validation: `java -jar target/restartable-import-0.1.0.jar --command=health --import.chunk-size=0` stops at startup, naming `chunkSize`.
5. List the beans created: add `--debug` to a command and read the "positive matches" of auto-configuration in the output.

## Mistakes people make

- **Putting the setting in the wrong place.** A value in `application.yml` is overridden by an environment variable, which is overridden by a command-line argument. A test that passes settings as "default properties" is silently overridden by the file; pass them as arguments instead.
- **Two beans of the same type.** When Spring finds two candidates for one parameter it fails or picks by parameter name. Name parameters like the bean (`orderMemory`, `fileMemory`) or use a qualifier.
- **Asking for a bean by its concrete class when the declaration is an interface.** Spring Boot declares its admin bean as `AmqpAdmin`; asking for `RabbitAdmin` finds nothing. Ask for the interface.
- **A program that exits at once.** A command-line runner that returns ends the program, which is wanted for a tool and wrong for a service. A service must have something that keeps the process alive (a listener, or a blocked main thread).
- **Business logic inside `@Configuration` methods.** Configuration should only wire objects together; logic belongs in plain classes that can be tested without Spring.

## Check yourself

1. What is a composition root? *The single class where the parts of the program are plugged together.*
2. Which wins: an environment variable or `application.yml`? *The environment variable.*
3. Why is `spring.batch.job.enabled` false? *So the job does not start without parameters; the runner starts it.*
4. What does the program's exit code mean? *0 completed, 1 failed or stopped, 2 unknown command, 3 already complete, 4 blocked.*
5. Why does the `ImportRunner` print results to standard output and the log to standard error? *So a script can capture the report without log lines.*

## Further reading

- Spring Boot reference documentation, version 3.5: https://docs.spring.io/spring-boot/3.5/reference/
- Externalized configuration (the order settings are read): https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html
- Spring Framework: the IoC container and beans: https://docs.spring.io/spring-framework/reference/core/beans.html
- Application exit and exit codes: https://docs.spring.io/spring-boot/3.5/reference/features/spring-application.html#features.spring-application.application-exit
