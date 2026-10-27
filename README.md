# batch-integration-restartable-import

A Spring Batch job that imports a product catalog of 200,000 CSV rows into PostgreSQL, skips bad rows up to a limit and records each one with its line and reason, retries transient write errors, and restarts after a `kill -9` without loading anything twice.

| | |
| --- | --- |
| **Lesson** | `/prep/batch-integration/project-restartable-import`, "The restartable catalog import" (`data-structure-explainer/src/content/tracks/batch-integration/13-project-restartable-import.md` in the Plainround app) |
| **Planned hours** | 6 |
| **Level** | Beginner |
| **Architecture** | [ARCHITECTURE.md](ARCHITECTURE.md), decisions: [docs/adr](docs/adr) |

## Prerequisites

| Tool | Version | Used for |
| --- | --- | --- |
| Java (JDK) | 21 or newer (tested with 25; the code targets 21 and CI uses 21) | build and run |
| Docker with Compose v2 | 24 or newer | PostgreSQL 16 |
| GNU Make | 3.81 or newer | every command below |
| Maven | none to install, the wrapper `./mvnw` is in the repository | build |
| Libraries | Spring Boot 3.5.x (Spring Batch 5.2), Lombok, Result4J, Instancio, Awaitility, ArchUnit, JaCoCo, Spotless (google-java-format) | pinned in `pom.xml` |

No cloud account. The database password is read from the environment: `make up` copies `.env.example` to `.env` the first time (a throwaway value for a database that lives only on this machine while the container runs). Port 55531 must be free (`POSTGRES_PORT` in `.env` changes it).

## Setup, run, test, stop

```bash
make up                 # PostgreSQL in a container, .env from .env.example
make db                 # sql/001 (job repository), 002 (product, step_summary view), migrations (reject table)
make run                # generate the 200,000-row file (100 bad rows) and import it
make test               # unit and architecture tests with coverage (no services needed)
make lint               # google-java-format check and the compiler's -Xlint:all -Werror
make test-integration   # the job against PostgreSQL: skip, retry, crash and recovery, the reject log
make test-e2e           # the lesson's 200,000-row scenario and the retry scenario
make audit              # known-vulnerability check of the dependencies (OSS Index)
make down               # stops PostgreSQL and deletes its data
```

The lesson's steps, one command each: `make run` (steps 2 and 3), `make too-many-bad` (the skip limit), `make run-new-id` and `make run-twice` (step 4), `make crash-test ROUNDS=5` (step 5), `make retry-demo` (step 6). Also `make summary`, `make queries`, `make health`, `make generate ROWS=n`; `make help` lists every target. The log (one JSON object per line) goes to standard error; the report goes to standard output. Exit codes: 0 completed, 1 failed or stopped, 2 unknown command, 3 the same run already completed, 4 another run holds the lock.

## The SQL

Every statement the project runs against PostgreSQL is a file under [sql/](sql/), run in this order:

| File | Kind | What it holds | Run by |
| --- | --- | --- | --- |
| [001_spring_batch_schema.sql](sql/001_spring_batch_schema.sql) | DDL | the job repository: Spring Batch's own tables, `IF NOT EXISTS` | `make db` |
| [002_catalog_schema.sql](sql/002_catalog_schema.sql) | DDL | `product` (primary key `sku`) and the view `step_summary` (read, write and the three skip counters of a step added up) | `make db` |
| [migrations/001_import_reject.sql](sql/migrations/001_import_reject.sql) | DDL | `import_reject`, one row per refused line, keyed by (file, line) | `make db` |
| [003_queries.sql](sql/003_queries.sql) | queries | executions and their counts, rows per SKU, the rejects, what the crash left behind | `make queries` |
| [reset.sql](sql/reset.sql) | DML | forget every product, reject and run | `make reset` |

## Done when: the lesson's checklist and the proof for each line

Output below is from this repository on a Mac laptop with PostgreSQL 16 in a container (`make run`, `make crash-test` and the others, in that order).

| # | Done when (from the lesson) | Command | What proves it |
| --- | --- | --- | --- |
| 1 | The design says what happens to an invalid row and where the record of it goes. | read [ARCHITECTURE.md](ARCHITECTURE.md), the data flow and steps 6 and 7 | The processor throws `InvalidProductException`, the only exception the step may skip (limit 500). A skip listener first records the line, the raw fields and the reason in `import_reject`, in the chunk's transaction, and `rejects.txt` is exported from that table ([ADR 3](docs/adr/0003-skip-one-exception-record-in-the-database.md)). |
| 2 | A clean 200,000-row file loads completely, and the job repository's tables show one completed execution with the read and write counts. | `make run` | `COMPLETED read=200000 written=199900 skipped=100 commits=201 resumed=false`, then the job repository: `importStep \| COMPLETED \| 200000 \| 199900 \| 100 \| 201` (status, read, write, skip, commit counts, from the view `step_summary`), and `products=199900 distinct_skus=199900`. |
| 3 | The job completes with 100 skips, the reject file has 100 lines a person can act on, and a file with 600 bad rows fails the step. | `make run`, `make too-many-bad` | `rejects.txt: 100 lines; first two:` `line 2001: SKU-002000,weapons,295.31 -> unknown category weapons` and `line 4001: BAD4000,toys,205.08 -> bad SKU format` (physical line numbers: `sed -n 2001p data/products.csv` is that row). `make too-many-bad` writes a file with 606 bad rows: `FAILED read=17000 written=15516 skipped=500 commits=16 ... SkipLimitExceededException: Skip limit of '500' exceeded Caused by: ...InvalidProductException: unknown category weapons`, the step `FAILED` with 500 skips, and exit code 1. |
| 4 | Running the whole job twice on the same file leaves exactly one row per SKU. | `make run-new-id`, `make run-twice` | After the first run, `make run-new-id` imports the same file under a new `run.id`: `COMPLETED read=200000 written=199900 skipped=100 ...` and `products=199900 distinct_skus=199900` (the job repository now shows two completed executions). The same `run.id` is refused: `ALREADY_COMPLETE ... run.id 'run-1' already completed; use a new run.id to import again`, exit code 3. The writer is `INSERT ... ON CONFLICT (sku) DO UPDATE`, keyed by the business key ([ADR 2](docs/adr/0002-upsert-by-business-key.md)). |
| 5 | The restart resumes from the last committed chunk, the step's counts show it, and the table has exactly one row per valid SKU over five crash-and-restart runs. | `make crash-test ROUNDS=5` | Each round starts from an empty database, runs the import, `kill -9`s it at a random point, shows the job repository right after the kill (`STARTED`, the counts of the last commit), restarts with the same parameters and checks the table. Round 1: killed at `74000` read; the restart reports `COMPLETED read=126000 written=125937 skipped=63 commits=127 resumed=true`; 74,000 + 126,000 = 200,000; `products=199900 distinct_skus=199900`. The other four rounds: killed at 68,000, 92,000, 41,000 and 37,000 read, restarts reading 132,000, 108,000, 159,000 and 163,000, all ending at 199,900 rows, 199,900 distinct SKUs and 100 rejects. The last line: `5 of 5 crash-and-restart rounds ended with exactly one row per valid SKU`. `CrashAndRecoveryIT` repeats the check at test size and asserts that the restart reads only the lines after the last committed chunk. |
| 6 | The job completes without a skip for those chunks, and a non-transient error (a constraint violation) is not retried. | `make retry-demo` | The writer fails every fiftieth chunk once with a transient error: `transient failures injected and retried: 4` (201 chunks), and the result is still `COMPLETED read=200000 written=199900 skipped=100` with the same 100 skips, none added by the retries. A non-transient error is written once and fails the step at once: `CrashAndRecoveryIT#a_failure_that_can_never_succeed_is_written_once_and_not_retried` counts the writer's invocations. |

The lesson's text says the first bad row is on line 2002. Its own generator script puts that row (`SKU-002000`) on file line 2001, counting the header as line 1, so the reject lines here carry the physical line numbers (2001, 4001, ...).

## Stretch goals

| Stretch (from the lesson) | Built? |
| --- | --- |
| Read from a database table with a paging reader instead of a file, and check that restart still resumes correctly. | No. ARCHITECTURE.md, "How to extend it". |
| Add a second step that archives the input file only when the import step completed. | No. ARCHITECTURE.md describes the one-line change (`.next(archiveStep)` on `COMPLETED`). |
| Expose job status through an endpoint and start jobs through it with new parameters. | No. |

## What is simplified for teaching

Only what the lesson scopes out, plus where this build differs from the lesson's text:

- **The reject record is a table, and `rejects.txt` is exported from it.** The lesson's hint writes the file from a skip listener. A listener that appends to a file duplicates lines when a chunk is processed again after a crash, and a log line alone rotates away, so the record is a row keyed by (file, line) written in the chunk's transaction ([ADR 3](docs/adr/0003-skip-one-exception-record-in-the-database.md)).
- **Spring Batch has no single `skip_count` column.** The lesson reads one; the tables hold three (read, process, write skips). The view `step_summary` adds them up.
- **A step execution killed with `kill -9` stays `STARTED`.** Spring Batch refuses to restart it, so the job takes an advisory lock, marks executions left running by a dead process as failed, and then restarts ([ADR 4](docs/adr/0004-recover-crashed-runs-under-an-advisory-lock.md)).
- **One file, one node, one step**, a local path, a throwaway password in `.env`, a plain-text connection.
- **A skip limit counts per execution**, so a file can exceed it across restarts; ARCHITECTURE.md lists this with the other limits.

## Project structure

```text
src/main/java/plainround/catalogimport/
  domain/            ProductRow, ProductRules (a row to a Result), Rejection, RejectedRow, InvalidProductException.
                     Plain Java, no framework. Depends on nothing.
  application/       ImportCatalog and GenerateCatalogFile (use cases), the ports ImportJob, RunLock, CrashRecovery and
                     RejectLog, ImportRequest, ImportReport. Depends on domain only.
  infrastructure/    batch/ (the job, reader, processor, writer, skip listener, graceful shutdown, crash recovery) and jdbc/
                     (the advisory lock and the reject log). Depends on application and domain.
  interfaces/        the command-line runner and the composition root. Nothing depends on it.
src/test/java/       unit, architecture (ArchUnit), integration/ and e2e/ (need PostgreSQL)
sql/                 001, 002, migrations/, 003_queries.sql, reset.sql
scripts/             crash-test.sh (the kill -9 rounds)
docs/adr/            four decision records
docker-compose.yml   PostgreSQL 16      Dockerfile  multi-stage build, non-root user, pinned base image
pom.xml              Boot 3.5; plugins for JaCoCo (95 percent on domain and application), Spotless, audit; mvnw  the wrapper
```

`ArchitectureTest` enforces the dependency rule (domain depends on nothing; application on domain; infrastructure and interfaces on both; nothing on interfaces) and bans Spring from `domain` and `application`.

## Author and license

Written by Sumit Srivastava. Released under the MIT License, see [LICENSE](LICENSE). Changes are in [CHANGELOG.md](CHANGELOG.md).
