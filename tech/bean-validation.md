# Bean Validation (Jakarta Validation)

## What it is

Bean Validation is a standard way to state rules about the fields of an object with annotations and have them checked by a library: `@NotNull` (a value must be present), `@Min(1)` (a number must be at least 1) and many more. The checking engine is Hibernate Validator 8, an implementation of Jakarta Validation 3. "Bean" just means an ordinary Java object.

## Why this repository uses it

The job's settings (chunk size, skip limit, retry limit) come from outside the program. A chunk size of zero or a negative retry limit would make the import misbehave in confusing ways. Rules on the settings object stop the program at startup with a clear message.

## Where it appears

| Path | What it does |
| --- | --- |
| `src/main/java/plainround/catalogimport/infrastructure/batch/ImportProperties.java` | Annotates each setting with `@Min` and the class with `@Validated`. |
| `pom.xml` | Declares `spring-boot-starter-validation`. |

## How it works underneath

An annotation like `@Min(1)` does nothing by itself. A **validator** reads the annotations of an object through reflection and returns a set of **violations**. Spring Boot does this automatically for a `@ConfigurationProperties` class marked `@Validated`: after binding the settings it validates the object and refuses to start the application if there are violations.

## Walkthrough of this repository's use

```java
package plainround.catalogimport.infrastructure.batch;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

```

Line 3 imports `@Min` from `jakarta.validation.constraints` (the package name changed from `javax` to `jakarta` in Spring Boot 3). `@DefaultValue` and `@Validated` are Spring's.

```java
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

`@Validated` switches validation on. `@ConfigurationProperties("import")` binds settings that start with `import.`. Each component carries a default and a rule: `chunkSize` at least 1, `skipLimit` at least 0 (zero means "skip nothing"), `retryLimit` at least 1 (one attempt is the minimum), `transientFaultEveryNthChunk` at least 0 (0 means "never inject a fault").

## Try it yourself

1. Trigger a startup failure: `java -jar target/restartable-import-0.1.0.jar --command=health --import.chunk-size=0`. The message names `chunkSize` and says it must be at least 1.
2. Allow zero where allowed: `--import.skip-limit=0` is accepted; `--import.skip-limit=-1` is refused.
3. Run the unit test that checks the defaults: `./mvnw -q test -Dtest=BatchComponentsTest`.
4. Find the engine: `./mvnw dependency:tree | grep hibernate-validator`.
5. See the binding: set `IMPORT_RETRY_LIMIT=2` in the environment and run `make retry-demo`; the log shows two attempts at most.

## Mistakes people make

- **Forgetting `@Validated`.** The annotations are then ignored.
- **Validating late.** A check inside the job runs after hours of work; a check at startup fails in a second.
- **A rule that rejects the "off" value.** Zero is a valid way to say "never"; `@Min(0)` allows it.

## Check yourself

1. What does `@Min(1)` do? *Rejects values below 1.*
2. Who runs the checks? *A validator (Hibernate Validator); Spring Boot calls it for `@Validated` settings.*
3. Why is `skipLimit` allowed to be 0? *Zero means no skips are tolerated.*
4. What changed from `javax` to `jakarta`? *The package name, in Spring Boot 3.*
5. When is validation done? *At startup, after the settings are bound.*

## Further reading

- Jakarta Bean Validation 3.0: https://jakarta.ee/specifications/bean-validation/3.0/
- Hibernate Validator 8 reference: https://docs.jboss.org/hibernate/validator/8.0/reference/en-US/html_single/
- Spring Boot validated configuration properties: https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.validation
