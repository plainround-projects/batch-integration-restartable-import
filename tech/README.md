# Technology notes: the restartable catalog import

These notes teach every technology used in this repository, from zero, through the repository's own files. You do not need to know any of them before you start. Each note explains the idea, shows the repository's own lines, explains them one by one, gives commands to try, lists common mistakes and ends with five questions.

## The files

| File | Role in this repository |
| --- | --- |
| [java.md](java.md) | The language: the domain rules, the use cases and their ports. |
| [spring-batch.md](spring-batch.md) | The batch framework: the chunk step, skip and retry, the job repository, restart. |
| [spring-boot.md](spring-boot.md) | The application framework: start-up, beans, settings, the command line. |
| [bean-validation.md](bean-validation.md) | Rules on the settings (`@Min`, `@NotNull`). |
| [lombok.md](lombok.md) | Generated constructors and loggers. |
| [result4j.md](result4j.md) | A value that is either a success or an error: how a row is accepted or refused. |
| [jdbc.md](jdbc.md) | How Java talks to the database, the upsert and the advisory lock. |
| [postgresql.md](postgresql.md) | The database and every `.sql` file. |
| [slf4j-logback.md](slf4j-logback.md) | Logging as JSON lines on standard error. |
| [junit.md](junit.md) | Unit, integration and end-to-end tests, and every test file. |
| [assertj.md](assertj.md) | The assertion style used in the tests. |
| [archunit.md](archunit.md) | The test that enforces the dependency rule. |
| [awaitility.md](awaitility.md) | Waiting for asynchronous work in tests (declared, not needed). |
| [instancio.md](instancio.md) | Test data with only the interesting fields fixed. |
| [maven.md](maven.md) | The build tool and `pom.xml`. |
| [maven-wrapper.md](maven-wrapper.md) | `mvnw`, which fetches the right Maven. |
| [jacoco.md](jacoco.md) | The coverage gate. |
| [spotless.md](spotless.md) | The code formatter check. |
| [ossindex-audit.md](ossindex-audit.md) | The dependency vulnerability audit. |
| [make.md](make.md) | The `Makefile`: every `make` command. |
| [bash.md](bash.md) | `scripts/crash-test.sh`, the `kill -9` rounds. |
| [env-files.md](env-files.md) | `.env` and `.env.example`. |
| [docker.md](docker.md) | The `Dockerfile` and `.dockerignore`. |
| [docker-compose.md](docker-compose.md) | `docker-compose.yml`: the PostgreSQL service. |
| [github-actions.md](github-actions.md) | The CI workflow. |
| [yaml.md](yaml.md) | The file format of the settings, compose and workflow files. |

## The order to read them in

1. The language: [java.md](java.md).
2. The build: [maven.md](maven.md), then [maven-wrapper.md](maven-wrapper.md), [make.md](make.md) and [bash.md](bash.md).
3. The frameworks: [spring-boot.md](spring-boot.md), [spring-batch.md](spring-batch.md), then [lombok.md](lombok.md), [result4j.md](result4j.md) and [bean-validation.md](bean-validation.md).
4. The data: [postgresql.md](postgresql.md), then [jdbc.md](jdbc.md).
5. Watching it run: [slf4j-logback.md](slf4j-logback.md), [yaml.md](yaml.md).
6. Packaging and delivery: [docker.md](docker.md), [docker-compose.md](docker-compose.md), [env-files.md](env-files.md), [github-actions.md](github-actions.md), [spotless.md](spotless.md), [jacoco.md](jacoco.md), [ossindex-audit.md](ossindex-audit.md).
7. The tests: [junit.md](junit.md), [assertj.md](assertj.md), [archunit.md](archunit.md), [awaitility.md](awaitility.md), [instancio.md](instancio.md).

## If you have ten minutes

Read [spring-batch.md](spring-batch.md) (what a chunk, a skip and the job repository are, and why a restart resumes), [postgresql.md](postgresql.md) (the tables and the upsert that makes a repeated import safe) and [bash.md](bash.md) (how the `kill -9` test works).

## A note on what is not shown

The sample file `.env.example` holds throwaway sample credentials for the local database container. [env-files.md](env-files.md) explains the mechanism and shows where every variable is used, without printing the sample values.
