# PostgreSQL and the SQL files

## What it is

PostgreSQL ("Postgres") is a free, open-source relational database **server**: a long-running program that stores tables on disk, listens on a network port (5432 by default) and answers SQL sent by clients. It provides transactions with strong guarantees (ACID: a transaction is *atomic*, leaves the data *consistent*, is *isolated* from others and is *durable* once committed), many data types, and powerful SQL. **`psql`** is its command-line client: you give it a user, a database and SQL (from the keyboard, from a file with `<`, or from `-c "..."`).

Words you need: a **database** is a named collection of tables inside the server; a **role** (user) owns objects and logs in with a password; a **schema** groups tables inside a database (the default is `public`); a **sequence** is a counter object that hands out the next number; the **WAL** (write-ahead log) is the journal the server writes before changing data so it can recover after a crash; `fsync` is the call that forces that journal onto the disk.

SQL itself, the language of every `.sql` file in this repository, is explained next.

SQL (Structured Query Language) is the language used to talk to relational databases. A **relational database** stores data in **tables**; a table is a grid with named **columns** and one **row** per thing. SQL has four groups of statements:

- **DDL** (data definition) creates and changes the structure: `CREATE TABLE`, `CREATE INDEX`, `DROP INDEX`, `ALTER TABLE`.
- **DML** (data manipulation) changes the data: `INSERT`, `UPDATE`, `DELETE`, `TRUNCATE`.
- **Queries** read data: `SELECT ... FROM ... WHERE ... GROUP BY ... ORDER BY`.
- **DCL** (data control) manages permissions: `GRANT`, `REVOKE`.

Key ideas: a **primary key** is the column (or columns) whose value identifies a row and must be unique; a **foreign key** is a column that must match the primary key of another table; a **constraint** (`NOT NULL`, `CHECK (...)`, `UNIQUE`) is a rule the database enforces on every write; an **index** is an extra structure that lets the database find rows quickly without reading the whole table; `NULL` means "no value" and is neither equal nor unequal to anything, which is why SQL has `IS NULL` and `COALESCE(x, default)`. Comments start with `--` and run to the end of the line.

## Why this repository uses it

The import's target (the product table), its record of refused rows, and Spring Batch's bookkeeping (the job repository, which is what makes a restart possible) all live in PostgreSQL. The lesson's central claims, "one row per SKU after any number of runs" and "a restart resumes where the last commit ended", are database facts, so the SQL is kept in numbered files a reader can run and read: `make db` runs them in order. PostgreSQL also provides the **advisory lock** that stops two imports from running at once.

## Where it appears

| Path | What it does |
| --- | --- |
| `sql/001_spring_batch_schema.sql` | The job repository tables. |
| `sql/002_catalog_schema.sql` | `product` and the view `step_summary`. |
| `sql/migrations/001_import_reject.sql` | `import_reject`, one row per refused line. |
| `sql/003_queries.sql` | The lesson's checks (`make queries`). |
| `sql/reset.sql` | Forgets everything (`make reset`, the crash test, the tests). |
| `docker-compose.yml` | The server and its settings. |

## How it works underneath

The server starts one **backend process** per connection. A client (the Java program, `psql`) connects over TCP, authenticates, and sends statements. For a write, the server records the change in the WAL, changes the pages in its memory cache (`shared_buffers`), and flushes them to disk later in the background (a **checkpoint**). With `fsync=on` (the default) a commit waits until its WAL record is safely on disk; with `fsync=off` it does not wait, which is fast but can lose or corrupt data if the machine crashes. The benchmark database in this repository turns it off on purpose and says so.

`pg_isready` asks the server whether it accepts connections. `pg_stat_activity` is a built-in view listing every connection and its state (`active`, `idle`, `idle in transaction`); `pg_locks` lists locks, with `granted = false` for those waiting. A connection that is `idle in transaction` holds its transaction open without running anything.

A SQL statement describes **what** you want, not **how** to get it. The database's **planner** chooses the method: it can scan the whole table (a *sequential scan*) or use an index. A `JOIN` combines rows of two tables that match a condition; `LEFT JOIN` keeps the rows of the left table even when there is no match. `GROUP BY` collapses rows that share values and lets you compute totals with functions such as `SUM`, `COUNT`, `MAX`. An aggregate over no rows gives `NULL` (and `COUNT` gives 0).

A **transaction** groups statements: `BEGIN` (or the first statement in a session with autocommit off) starts, `COMMIT` makes the changes permanent and visible to others, `ROLLBACK` throws them away. By default `psql` runs each statement in its own transaction (autocommit).

## Walkthrough of this repository's use

### `sql/002_catalog_schema.sql`: the product table and the view

```sql
-- DDL: the target of the import. One row per SKU; the primary key is what makes a repeated write harmless
-- (the writer upserts on it) and the CHECK is the database's last word on the price rule.

CREATE TABLE IF NOT EXISTS product (
    sku      VARCHAR(20)   PRIMARY KEY,
    name     VARCHAR(100)  NOT NULL,
    category VARCHAR(20)   NOT NULL,
    price    NUMERIC(10,2) NOT NULL CHECK (price > 0)
);
COMMENT ON TABLE product IS 'The product catalog. One row per SKU, loaded by the restartable import.';
```

Lines 1 and 2 are comments (SQL comments start with two dashes) saying why the table looks as it does. `CREATE TABLE IF NOT EXISTS product (...)` creates the table only when it is missing, so `make db` can be repeated. `sku VARCHAR(20) PRIMARY KEY` is text of at most 20 characters that identifies the row: no two rows may have the same SKU, and the import's upsert (`INSERT ... ON CONFLICT (sku) DO UPDATE`) relies on exactly this rule. `name VARCHAR(100) NOT NULL` is required text of at most 100 characters (a longer name makes the database refuse the row, which `CrashAndRecoveryIT` uses as a "failure that can never succeed"). `category VARCHAR(20) NOT NULL`. `price NUMERIC(10,2) NOT NULL CHECK (price > 0)` is an exact decimal with up to 10 digits, 2 after the point (money is never stored as a floating-point number), and the `CHECK` is the database's last word on the price rule the Java code also applies. `COMMENT ON TABLE` stores a description in the database.

```sql
-- Spring Batch splits the skip counter in three (read, process, write). The lesson's queries and this project's reports
-- want one number, so this view adds it: skip_count is the sum of the three.
CREATE OR REPLACE VIEW step_summary AS
SELECT step_execution_id,
       job_execution_id,
       step_name,
       status,
       read_count,
       write_count,
       read_skip_count + process_skip_count + write_skip_count AS skip_count,
       commit_count,
       rollback_count,
       start_time,
       end_time
FROM batch_step_execution;
```

A **view** is a saved query that behaves like a table. Spring Batch keeps three skip counters per step (`read_skip_count`, `process_skip_count`, `write_skip_count`); the lesson's queries want one number, so the view adds them: `read_skip_count + process_skip_count + write_skip_count AS skip_count`. `CREATE OR REPLACE VIEW` can be run again to change the definition. The other columns are copied from `batch_step_execution`, the job repository's table of step attempts (defined in the file below).

### `sql/migrations/001_import_reject.sql`: the record of refused rows

```sql
-- Migration 001 (expand only): the record of every row the import refused, added in the lesson's step 3.
--
-- The primary key (source_file, line_number) makes recording idempotent: a chunk that is processed again after a
-- crash writes the same reject rows again and changes nothing. The row is written inside the chunk's transaction, so
-- a chunk that rolls back takes its rejects with it. The file `rejects.txt` is exported from this table at the end
-- of a run, so it is complete after any number of crashes and restarts.
```

A **migration** is a change to an existing database's structure, kept as a numbered file. The comment says why the table is built this way: the primary key `(source_file, line_number)` makes recording idempotent, so a chunk processed again after a crash writes the same reject rows again and changes nothing; the row is written inside the chunk's transaction, so a chunk that rolls back takes its rejects with it; and `rejects.txt` is exported from this table at the end of a run, so it is complete after any number of crashes and restarts.

```sql
CREATE TABLE IF NOT EXISTS import_reject (
    source_file TEXT          NOT NULL,
    line_number INTEGER       NOT NULL,
    sku         TEXT,
    category    TEXT,
    price       TEXT,
    reason      TEXT          NOT NULL,
    rejected_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (source_file, line_number)
);
COMMENT ON TABLE import_reject IS 'Rows the import skipped, with the line number in the source file and the reason.';
```

`source_file TEXT NOT NULL` and `line_number INTEGER NOT NULL` identify the refused line. `sku`, `category` and `price` are kept as `TEXT` and may be empty (`NULL` is allowed because they have no `NOT NULL`): the line was refused precisely because a field may be wrong, so the raw text is stored. `reason TEXT NOT NULL` is why it was refused. `rejected_at TIMESTAMPTZ NOT NULL DEFAULT now()` is a timestamp with time zone that the database fills in with the current time when the insert does not give one. `PRIMARY KEY (source_file, line_number)` makes the pair unique.

### `sql/reset.sql`

```sql
-- Outside the numbered run order: forget everything the import wrote and every run it remembers (used by
-- `make reset`, the crash test and the integration tests).
TRUNCATE product, import_reject;
TRUNCATE batch_step_execution_context, batch_job_execution_context, batch_step_execution,
         batch_job_execution_params, batch_job_execution, batch_job_instance;
```

`TRUNCATE` empties tables instantly without scanning their rows. Line 3 forgets the import's output (the products and the refused rows). Lines 4 and 5 forget every run: all six Spring Batch tables in one statement, listed so that tables that refer to each other can be emptied together.

### `sql/003_queries.sql`: the lesson's checks

```sql
-- The queries the lesson runs, each with a one-line comment on what it shows.

-- The end state: 199,900 rows, one per valid SKU.
SELECT count(*) AS products, count(DISTINCT sku) AS distinct_skus FROM product;

-- The job repository after a clean run: one COMPLETED step with the counts (200000 read, 199900 written, 100 skipped).
-- step_summary is a view over batch_step_execution that adds up Spring Batch's three skip counters (see 002).
SELECT step_name, status, read_count, write_count, skip_count, commit_count
FROM step_summary
ORDER BY step_execution_id;
```

The first query counts the rows and the distinct SKUs: both must be 199,900 after a good run. `count(*) AS products` names the result column. The second reads the job repository through the view: `FROM step_summary ORDER BY step_execution_id` lists the step attempts oldest first with their read, write and skip counts; after a clean run it is one `COMPLETED` row.

```sql
-- After a kill -9 and a restart: the first execution stays STARTED (or FAILED once recovered), with the counts of
-- its last commit; the second reads only what was left. The two add up to the file.
SELECT s.job_execution_id, s.step_name, s.status, s.read_count, s.write_count, s.skip_count, s.commit_count
FROM step_summary s
ORDER BY s.step_execution_id;

-- The skipped rows with their reasons, in file order.
SELECT line_number, sku, category, price, reason FROM import_reject ORDER BY line_number LIMIT 10;

-- Reject reasons, counted: the four flavors the sample file plants, 25 each.
SELECT reason, count(*) FROM import_reject GROUP BY reason ORDER BY reason;

-- Products by category.
SELECT category, count(*) AS products FROM product GROUP BY category ORDER BY category;
```

The third query shows what a crash leaves behind: the first execution stays `STARTED` (or `FAILED` once recovered) with the counts of its last commit, and the second reads only what was left. `s.` is an alias for `step_summary`. The fourth lists the refused rows in file order, ten of them (`LIMIT 10`). The fifth groups them by reason and counts each (`GROUP BY reason`), which shows the four kinds of bad row, 25 each. The sixth counts products by category.

### `sql/001_spring_batch_schema.sql`: the job repository

These are Spring Batch's own tables, copied from the library's PostgreSQL script with `IF NOT EXISTS` added so they can be created repeatedly. The header comment says why the application does not create them itself: the schema is a reviewable file.

```sql
-- DDL: Spring Batch's own job repository tables (the "persistent job repository" of the lesson), for PostgreSQL.
-- This is spring-batch-core 5.2.6's schema-postgresql.sql, unchanged except that every CREATE is IF NOT EXISTS so
-- that `make db` can be repeated. Keep it in step with the Spring Batch version in pom.xml (Spring Boot 3.5.x).
-- The application runs with spring.batch.jdbc.initialize-schema=never: the schema is a file you can read and review,
-- not something the application creates at startup.


CREATE TABLE IF NOT EXISTS BATCH_JOB_INSTANCE  (
	JOB_INSTANCE_ID BIGINT  NOT NULL PRIMARY KEY ,
	VERSION BIGINT ,
	JOB_NAME VARCHAR(100) NOT NULL,
	JOB_KEY VARCHAR(32) NOT NULL,
	constraint JOB_INST_UN unique (JOB_NAME, JOB_KEY)
) ;

CREATE TABLE IF NOT EXISTS BATCH_JOB_EXECUTION  (
	JOB_EXECUTION_ID BIGINT  NOT NULL PRIMARY KEY ,
	VERSION BIGINT  ,
	JOB_INSTANCE_ID BIGINT NOT NULL,
	CREATE_TIME TIMESTAMP NOT NULL,
	START_TIME TIMESTAMP DEFAULT NULL ,
	END_TIME TIMESTAMP DEFAULT NULL ,
	STATUS VARCHAR(10) ,
	EXIT_CODE VARCHAR(2500) ,
	EXIT_MESSAGE VARCHAR(2500) ,
	LAST_UPDATED TIMESTAMP,
	constraint JOB_INST_EXEC_FK foreign key (JOB_INSTANCE_ID)
	references BATCH_JOB_INSTANCE(JOB_INSTANCE_ID)
) ;

```

`BATCH_JOB_INSTANCE` has one row per distinct job and parameters: `JOB_NAME`, and `JOB_KEY`, a 32-character hash of the identifying parameters; `unique (JOB_NAME, JOB_KEY)` is why the same job with the same parameters is the same instance. `BATCH_JOB_EXECUTION` has one row per attempt: times, `STATUS` (`STARTED`, `COMPLETED`, `FAILED`) and an exit code and message; the **foreign key** `JOB_INST_EXEC_FK` ties each execution to its instance.

```sql
CREATE TABLE IF NOT EXISTS BATCH_JOB_EXECUTION_PARAMS  (
	JOB_EXECUTION_ID BIGINT NOT NULL ,
	PARAMETER_NAME VARCHAR(100) NOT NULL ,
	PARAMETER_TYPE VARCHAR(100) NOT NULL ,
	PARAMETER_VALUE VARCHAR(2500) ,
	IDENTIFYING CHAR(1) NOT NULL ,
	constraint JOB_EXEC_PARAMS_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE TABLE IF NOT EXISTS BATCH_STEP_EXECUTION  (
	STEP_EXECUTION_ID BIGINT  NOT NULL PRIMARY KEY ,
	VERSION BIGINT NOT NULL,
	STEP_NAME VARCHAR(100) NOT NULL,
	JOB_EXECUTION_ID BIGINT NOT NULL,
	CREATE_TIME TIMESTAMP NOT NULL,
	START_TIME TIMESTAMP DEFAULT NULL ,
	END_TIME TIMESTAMP DEFAULT NULL ,
	STATUS VARCHAR(10) ,
	COMMIT_COUNT BIGINT ,
	READ_COUNT BIGINT ,
	FILTER_COUNT BIGINT ,
	WRITE_COUNT BIGINT ,
	READ_SKIP_COUNT BIGINT ,
	WRITE_SKIP_COUNT BIGINT ,
	PROCESS_SKIP_COUNT BIGINT ,
	ROLLBACK_COUNT BIGINT ,
	EXIT_CODE VARCHAR(2500) ,
	EXIT_MESSAGE VARCHAR(2500) ,
	LAST_UPDATED TIMESTAMP,
	constraint JOB_EXEC_STEP_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE TABLE IF NOT EXISTS BATCH_STEP_EXECUTION_CONTEXT  (
	STEP_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
	SHORT_CONTEXT VARCHAR(2500) NOT NULL,
	SERIALIZED_CONTEXT TEXT ,
	constraint STEP_EXEC_CTX_FK foreign key (STEP_EXECUTION_ID)
	references BATCH_STEP_EXECUTION(STEP_EXECUTION_ID)
) ;

CREATE TABLE IF NOT EXISTS BATCH_JOB_EXECUTION_CONTEXT  (
	JOB_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
	SHORT_CONTEXT VARCHAR(2500) NOT NULL,
	SERIALIZED_CONTEXT TEXT ,
	constraint JOB_EXEC_CTX_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE SEQUENCE IF NOT EXISTS BATCH_STEP_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE IF NOT EXISTS BATCH_JOB_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE IF NOT EXISTS BATCH_JOB_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
```

`BATCH_JOB_EXECUTION_PARAMS` stores each parameter with its name, type, value and whether it identifies the instance. `BATCH_STEP_EXECUTION` has one row per step attempt with all the counters the reports use: `COMMIT_COUNT`, `READ_COUNT`, `FILTER_COUNT`, `WRITE_COUNT`, the three skip counts and `ROLLBACK_COUNT`. The two `..._CONTEXT` tables keep each execution's saved state (a short form in `SHORT_CONTEXT` and, when long, in `SERIALIZED_CONTEXT`). The three `CREATE SEQUENCE` lines make the counters that hand out new ids (`NO CYCLE` means they never wrap around).

## Try it yourself

1. Open a SQL prompt: `docker compose exec postgres psql -U catalog -d catalog`. Type `\dt` to list tables, `\d product` to describe one, `\q` to leave.
2. Check the end state after `make run`: `select count(*), count(distinct sku) from product;` prints `199900 | 199900`.
3. Look at the refused rows: `select line_number, sku, reason from import_reject order by line_number limit 5;` starts with line 2001.
4. See the view: `select step_name, status, read_count, write_count, skip_count from step_summary;` prints `importStep | COMPLETED | 200000 | 199900 | 100`.
5. Break a constraint: `insert into product values ('SKU-X', 'x', 'books', 0);` fails with a message that the check constraint `product_price_check` is violated.

## Mistakes people make

- **`fsync=off` on real data.** Only for disposable databases.
- **Too few connections allowed.** `max_connections` must exceed what the application's pool opens (here up to 80 for the scaling probe; the compose file sets 200).
- **Forgetting that `TRUNCATE` is immediate and cannot be filtered.** It empties the whole table.
- **Running the server without enough shared memory in a container.** Parallel queries fail with "No space left on device" on `/dev/shm`; the compose file sets `shm_size`.
- **Using the `postgres` superuser for the application.** The application here uses an ordinary role created for the project.

- **`= NULL` instead of `IS NULL`.** The first is never true.
- **Forgetting the index** on the column you search by. A query that is fine with a thousand rows becomes minutes with millions (the lesson's step 1 to 2).
- **`UPDATE` or `DELETE` without `WHERE`.** It changes every row.
- **Adding instead of setting** in an upsert (`points = points + 1`): a retry doubles the effect.
- **Building SQL from strings that include user input.** Use parameters.
- **Assuming row order.** Without `ORDER BY` the order is not defined.

## Check yourself

1. What makes a repeated import safe? *The primary key on `sku` plus the writer's `ON CONFLICT (sku) DO UPDATE`: a second write of the same SKU replaces the row instead of adding one.*
2. Why does `import_reject` have the key `(source_file, line_number)`? *So recording the same refused line twice, as happens after a crash, leaves one row.*
3. What is `step_summary`? *A view that adds Spring Batch's three skip counters into one `skip_count`.*
4. What does `TRUNCATE` do compared with `DELETE`? *It empties the table at once without scanning rows.*
5. Why are the reject fields stored as `TEXT`? *The line was refused because a field may be wrong, so the raw text is kept.*

## Further reading

- PostgreSQL 16 documentation: https://www.postgresql.org/docs/16/
- `psql` reference: https://www.postgresql.org/docs/16/app-psql.html
- Monitoring views (`pg_stat_activity`): https://www.postgresql.org/docs/16/monitoring-stats.html
- Server configuration (`max_connections`, `fsync`, `shared_buffers`): https://www.postgresql.org/docs/16/runtime-config.html
- Docker image `postgres:16`: https://hub.docker.com/_/postgres

- PostgreSQL 16 SQL language reference: https://www.postgresql.org/docs/16/sql.html
- SQL commands index: https://www.postgresql.org/docs/16/sql-commands.html
- Indexes: https://www.postgresql.org/docs/16/indexes.html
