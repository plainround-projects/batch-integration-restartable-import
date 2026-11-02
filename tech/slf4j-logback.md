# SLF4J and Logback

## What it is

A **log** is the running diary of a program: lines saying what happened and when. **SLF4J** (Simple Logging Facade for Java) is the small API that code uses to write log lines (`log.info("...")`, `log.warn("...")`). It does not decide where lines go. **Logback** is the engine that does: it filters by **level**, formats each line and sends it to an **appender** (a destination such as the console or a file). The levels, from quietest to loudest: `ERROR` (something failed), `WARN` (something is wrong but the program goes on), `INFO` (normal milestones), `DEBUG` and `TRACE` (detail for developers).

A **structured log** writes each line as a small JSON object (fields such as `message`, `level`, `@timestamp`) instead of free text, so tools can search and count fields without guessing at the format.

## Why this repository uses it

An import that runs unattended and can be killed needs a trustworthy diary. The log is JSON (one object per line) so tools can search it, and it goes to standard error so the report on standard output stays clean for scripts. The crash test relies on this: it captures the report and the log apart.

## Where it appears

| Path | What it does with logging |
| --- | --- |
| `src/main/resources/logback-spring.xml` | Where and how log lines are written. |
| `${J}/application/ImportCatalog.java` | `import starting` and `import finished` lines. |
| `${J}/infrastructure/batch/StaleExecutionRecovery.java` | A warning for each recovered execution. |
| `${J}/infrastructure/batch/TransientFaultInjector.java` | A warning for each injected failure. |
| `src/main/resources/application.yml` | The `logging.level` section. |

## How it works underneath

Every process has three standard streams: **standard input** (what you type), **standard output** (normal results) and **standard error** (messages about problems and diagnostics). In a terminal both outputs show on screen, but a shell can separate them: `command > results.txt` redirects only standard output and `command 2> log.txt` only standard error. This repository sends the program's *results* to standard output and the *log* to standard error so that a script can capture the report without the log lines mixed in.

Logback reads `logback-spring.xml` at startup (the `-spring` suffix lets Spring Boot process it). An appender named `STDERR` is a console appender whose `<target>` is `System.err`; its encoder is Spring Boot's `StructuredLogEncoder` with the `logstash` format, which writes one JSON object per line. The `<root>` element sets the minimum level (`INFO`) for every logger and lists which appenders receive the lines. Settings in `application.yml` under `logging.level` then raise or lower particular loggers.

## Walkthrough of this repository's use

### `logback-spring.xml`

XML is a text format made of nested **elements** written `<name attribute="value">...</name>`; an element with nothing inside can close in one tag, `<appender-ref ref="STDERR"/>`.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- Structured logging: one JSON object per line on STANDARD ERROR, so standard output carries only the program's
     results (the report line, the step table) and the two can be redirected apart. -->
<configuration>
  <appender name="STDERR" class="ch.qos.logback.core.ConsoleAppender">
    <target>System.err</target>
    <encoder class="org.springframework.boot.logging.logback.StructuredLogEncoder">
      <format>logstash</format>
    </encoder>
  </appender>
  <root level="INFO">
    <appender-ref ref="STDERR"/>
  </root>
</configuration>
```

Line 1 is the XML declaration. Lines 2 and 3 are an XML comment (`<!-- ... -->`) explaining the choice. `<configuration>` (line 4) is the root. The `<appender>` on line 5 is named `STDERR` and uses `ConsoleAppender`, Logback's class for writing to the console. `<target>System.err</target>` (line 6) selects standard error. The `<encoder>` (line 7) decides how an event becomes text: Spring Boot's `StructuredLogEncoder` with `<format>logstash</format>` (line 8) writes one JSON object per line using Logstash's field names (`@timestamp`, `message`, `logger_name`, `thread_name`, `level`). `<root level="INFO">` (line 11) sets the default level; `<appender-ref ref="STDERR"/>` (line 12) sends the root logger's lines to that appender.

### Writing lines

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
```

`log.info("import starting file={} runId={}", request.file(), request.runId());` writes an INFO event. Each `{}` is replaced by the next argument, only if INFO is enabled. The `key=value` style makes the line easy to search.

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
```

`log.warn("recovered crashed execution id={}", execution.getId());` records each repaired execution, so after a `kill -9` the log says what the next start did.

```yaml
logging:
  level:
    root: INFO
    org.springframework.batch: WARN

```

`logging.level.root: INFO` shows INFO and above everywhere; `org.springframework.batch: WARN` silences Spring Batch's routine messages.

## Try it yourself

1. See a log line: `make health 2>&1 >/dev/null | head -3` (the `2>&1 >/dev/null` order sends standard error to the pipe and discards standard output). Expect JSON.
2. See only results: `make health 2>/dev/null` prints `ok database reachable, ...`.
3. Pretty-print: `make health 2>&1 >/dev/null | head -1 | python3 -m json.tool`.
4. Find the recovery warning: after `make crash-test ROUNDS=1`, `grep -h "recovered crashed" logs/restart-*.err` shows the JSON line.
5. Raise detail: `java -jar target/restartable-import-0.1.0.jar --command=health --logging.level.org.springframework.batch=DEBUG 2>&1 >/dev/null | wc -l`.

## Mistakes people make

- **Logging secrets.** Never write passwords or tokens. This repository logs counts, names and durations only.
- **Building the message with `+` before the call.** `log.info("a " + x)` builds the text even if INFO is off. Use the `{}` placeholder: `log.info("a {}", x)`.
- **Mixing results and logs on the same stream.** A script that parses the output then chokes on log lines. Keep results on standard output and logs on standard error.
- **Logging in a tight loop.** One line per item for millions of items fills disks; `RunProgress` limits its progress line to one every five seconds.

## Check yourself

1. Which stream carries the log, and which the report? *Standard error the log; standard output the report.*
2. Why JSON? *Tools can search and count fields.*
3. What does `<root level="INFO">` mean? *INFO, WARN and ERROR are logged unless a more specific setting says otherwise.*
4. Why `{}` placeholders? *The message is built only when the level is on.*
5. Which line proves a crashed run was repaired? *The `recovered crashed execution id=...` warning.*

## Further reading

- SLF4J manual: https://www.slf4j.org/manual.html
- Logback manual: https://logback.qos.ch/manual/index.html
- Spring Boot logging (structured logging, Boot 3.5): https://docs.spring.io/spring-boot/3.5/reference/features/logging.html
