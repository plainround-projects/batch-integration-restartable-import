# Make

## What it is

`make` is a command-line tool that runs named recipes from a file called `Makefile`. Each recipe is a **target** with the shell commands that produce it. It was invented to rebuild only what is out of date, but it is also widely used as a simple, shared list of project commands: `make test`, `make up`, `make down`. A person who has never seen the project can type `make help` and see what is possible.

The syntax of a rule:

```text
target: dependency-1 dependency-2   ## optional comment
<TAB>command
<TAB>command
```

The commands must start with a **Tab** character (not spaces). Before running a target, `make` first makes each **dependency** (another target, or a file that must be up to date). A target that is not a real file name, such as `test`, is declared in `.PHONY` so make never confuses it with a file called `test`.

## Why this repository uses it

The lesson has many steps, each with its own long command line (start the database, load the schema, generate a file, import it, import it twice, kill the import, restart it, test, audit). The Makefile gives each a short name so the README can say `make crash-test` and a reader never has to learn the long form. `make help` prints the list.

## Where it appears

| Path | What it does with Make |
| --- | --- |
| `Makefile` | Every target the README tells you to run. |
| `.github/workflows/ci.yml` | Calls `make lint`, `make test`, `make audit` and the integration targets, so CI and a laptop run identical commands. |
| `scripts/crash-test.sh` | Started by `make crash-test`. |

## How it works underneath

`make` reads the whole `Makefile`, builds a graph of targets and dependencies, then runs the commands of the target you asked for after those of its dependencies. For every command line it starts a **new shell**, so a variable set on one line is gone on the next unless you join the lines with `;` and a backslash `\`. A command line that starts with `@` is not echoed; one that starts with `-` ignores failures.

Variables: `NAME := value` assigns once when the file is read; `NAME ?= value` assigns only if `NAME` has no value yet (so the environment or the command line can override it: `make run ROWS=1000`); `$(NAME)` reads it. In a recipe `$$` is how you write a single `$` for the shell (`$$x` becomes `$x`). `-include .env` reads another file of assignments if it exists, and `export` with no names passes every variable to the commands as environment variables. A value given on the make command line (`make run ROWS=1000`) beats the file; a value in the Makefile beats the environment unless the variable was written with `?=`.

## Walkthrough of this repository's use

```make
# make up | db | generate | run | crash-test | test | lint | down   (make help lists all)
MVN := ./mvnw -q -B
JAR := target/restartable-import-0.1.0.jar
ROWS ?= 200000
BAD_EVERY ?= 2000
ROUNDS ?= 5

POSTGRES_USER ?= catalog
POSTGRES_DB ?= catalog

-include .env
export

PSQL := docker compose exec -T postgres psql -U $(POSTGRES_USER) -d $(POSTGRES_DB) -v ON_ERROR_STOP=1 -q
APP := java -jar $(JAR)

.PHONY: help env up db reset build generate run run-twice run-new-id too-many-bad retry-demo summary queries crash-test \
        health lint format test test-integration test-e2e audit check docker-build down clean

```

Line 2, `MVN := ./mvnw -q -B`, is the Maven command: the wrapper, `-q` (quiet) and `-B` (batch mode: no colors or progress bars, so logs are clean). Line 3 is the name of the jar the build produces. Lines 4 to 6 are defaults with `?=`, which assigns only when the variable has no value yet: 200,000 rows, a bad row every 2,000 rows, 5 crash rounds. They can be overridden on the command line, `make crash-test ROUNDS=3`. Lines 8 and 9 give the database user and name defaults. Line 11, `-include .env`, reads `.env` (made by `make env` from `.env.example`) if it exists; the leading dash means "no error if the file is missing". Line 12, `export`, passes every variable to every command, so the Java program sees `DB_PASSWORD` and the rest. Line 14 defines `PSQL`, the command that runs `psql` (PostgreSQL's command-line client) inside the container: `docker compose exec -T postgres psql -U ... -d ... -v ON_ERROR_STOP=1 -q`. `exec` runs a command in a running container; `-T` turns off the pseudo-terminal so input can be piped in; `-v ON_ERROR_STOP=1` makes `psql` stop at the first SQL error; `-q` is quiet. Line 15 is the command that runs the jar. Lines 17 and 18 list the **phony targets** (names that are commands, not files); the backslash continues the line.

```make
help: ## list the targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | sed -E 's/:.*## /\t/' | sort

env:
	@[ -f .env ] || { cp .env.example .env; echo "created .env from .env.example"; }

up: env ## start PostgreSQL
	docker compose up -d --wait

db: ## apply sql/001 (job repository), 002 (product) and the migrations (reject record)
	$(PSQL) < sql/001_spring_batch_schema.sql
	$(PSQL) < sql/002_catalog_schema.sql
	@for file in sql/migrations/*.sql; do echo "applying $$file"; $(PSQL) < $$file; done

reset: ## forget every product, reject and run
	$(PSQL) < sql/reset.sql
```

`help` (20 to 21) prints every target that has a `##` comment: `grep` selects lines that look like `name: ... ## text`, `sed` replaces the `: ... ## ` part with a Tab, `sort` orders them; `$(MAKEFILE_LIST)` is the list of makefiles read. `env` (23 to 24) copies `.env.example` to `.env` only if `.env` does not exist (`[ -f .env ] || { ...; }`: if the test fails, run the block). `up` (26 to 27) depends on `env` and runs `docker compose up -d --wait`: start the services in the background (`-d`) and wait until their health checks pass. `db` (29 to 32) loads the two schema files by redirecting each into `psql` with `<`, then loops over the migration files: `for file in sql/migrations/*.sql; do echo "applying $$file"; $(PSQL) < $$file; done` (the double dollar is the shell's own `$`; make would otherwise try to expand it). `reset` (34 to 35) runs `reset.sql`, which forgets every product, reject and run.

```make
$(JAR): pom.xml $(shell find src/main -type f)
	$(MVN) -DskipTests package

build: $(JAR) ## build the jar

generate: build ## write data/products.csv: ROWS rows, a bad row every BAD_EVERY rows
	mkdir -p data
	$(APP) --command=generate --out=data/products.csv --rows=$(ROWS) --bad-every=$(BAD_EVERY)
```

Line 37 is a **file target**: `$(JAR): pom.xml $(shell find src/main -type f)` says the jar depends on the pom and on every file under `src/main`; `$(shell ...)` runs a command while reading the file. Because it is a real file, make rebuilds the jar only when one of those is newer. `build` depends on it. `generate` depends on `build`, creates the `data` folder (`mkdir -p` does nothing if it exists) and runs the program with `--command=generate` to write the sample file: `--rows` and `--bad-every` come from the variables.

```make
# The happy path: a clean import of the whole file, then the job repository and the end state.
run: build reset generate ## import data/products.csv (200,000 rows, 100 bad): 199,900 products, 100 rejects
	$(APP) --command=import --import.file=data/products.csv --run.id=run-1
	@echo "rejects.txt: $$(wc -l < rejects.txt) lines; first two:"; head -2 rejects.txt
	$(APP) --command=summary

run-new-id: build ## the same file again under a new run.id: still one row per SKU (step 4)
	$(APP) --command=import --import.file=data/products.csv --run.id=run-$$(date +%s)
	$(APP) --command=summary

run-twice: build ## the same file under the SAME run.id: refused as already complete
	-$(APP) --command=import --import.file=data/products.csv --run.id=run-1

too-many-bad: build reset ## a file with 600 bad rows fails the step: Skip limit of '500' exceeded
	mkdir -p data
	$(APP) --command=generate --out=data/too-many-bad.csv --rows=20000 --bad-every=33
	-$(APP) --command=import --import.file=data/too-many-bad.csv --run.id=too-many-1
	$(APP) --command=summary

retry-demo: build reset generate ## step 6: every fiftieth chunk fails once; the job completes with skip_count 100
	@mkdir -p logs
	IMPORT_TRANSIENT_FAULT_EVERY_NTH_CHUNK=50 $(APP) --command=import --import.file=data/products.csv --run.id=retry-1 2> logs/retry-demo.err
	@echo "transient failures injected and retried: $$(grep -c 'injected transient failure' logs/retry-demo.err)"
	$(APP) --command=summary
```

`run` is the happy path. It depends on `build`, `reset` and `generate`, so one command gives an empty database and a fresh file. It imports the file with the run id `run-1`, prints the line count of `rejects.txt` and its first two lines (`wc -l < rejects.txt` counts lines; the `@` in front of `echo` stops make from printing the command itself), then the summary. `run-new-id` imports the same file again under an id made from the clock (`$$(date +%s)` is the number of seconds since 1970), so Spring Batch accepts it as a new run (lesson step 4). `run-twice` repeats the first id; the leading dash on the command (`-$(APP) ...`) tells make to ignore the non-zero exit code, because the refusal (exit 3) is the point. `too-many-bad` writes a file with a bad row every 33 rows (606 bad in 20,000) and imports it, again ignoring the failure, then shows the summary. `retry-demo` sets `IMPORT_TRANSIENT_FAULT_EVERY_NTH_CHUNK=50` for one command (a variable written before a command applies only to it), sends the log (standard error) to a file with `2>`, and counts the lines containing `injected transient failure` with `grep -c`.

```make
summary: build ## the job repository and the product count
	$(APP) --command=summary

queries: ## run sql/003_queries.sql
	$(PSQL) < sql/003_queries.sql

crash-test: build generate ## step 5: ROUNDS times kill -9 the import at a random point, restart it, check the table
	sh scripts/crash-test.sh $(ROUNDS)

health: build ## exit 0 when the database is reachable and has the product table
	$(APP) --command=health
```

`summary` prints the job repository and the product count. `queries` runs `sql/003_queries.sql`. `crash-test` depends on `build` and `generate` and hands over to the shell script with the number of rounds (`sh scripts/crash-test.sh $(ROUNDS)`). `health` runs the health command, which exits 0 when the database answers and has the product table.

```make
lint: ## spotless (google-java-format) check and the compiler's -Xlint:all -Werror
	$(MVN) spotless:check compile

format: ## apply google-java-format
	$(MVN) spotless:apply

test: ## unit and architecture tests with coverage (no services needed); the domain and application stay at 95%
	$(MVN) verify -DskipITs

test-integration: ## the job against the running PostgreSQL (make up and make db first)
	$(MVN) test-compile failsafe:integration-test failsafe:verify -Dfailsafe.groups=integration

test-e2e: ## the lesson's 200,000-row scenario (make up and make db first)
	$(MVN) test-compile failsafe:integration-test failsafe:verify -Dfailsafe.groups=e2e

audit: ## check the dependencies against the OSS Index vulnerability database
	$(MVN) org.sonatype.ossindex.maven:ossindex-maven-plugin:audit

check: lint test ## everything that needs no services

docker-build: ## build the container image
	docker build -t restartable-import:dev .

down: ## stop PostgreSQL and delete its data
	docker compose down -v

clean: down
	rm -rf target data logs rejects.txt
```

`lint` runs the Spotless check and the compiler. `format` applies the formatter. `test` runs `verify` with `-DskipITs`, so the unit tests and the JaCoCo check run but the integration tests are skipped. `test-integration` and `test-e2e` run only the Failsafe goals for the chosen tag group. `audit` runs the OSS Index plug-in. `check` depends on `lint` and `test`. `docker-build` builds the image. `down` stops the container and deletes its volume (`-v`). `clean` depends on `down`, then removes the build output, the data folder, the logs and `rejects.txt`.

## Try it yourself

1. List the targets: `make help`. Expect about 25 lines of `name<TAB>description`.
2. See what a target would do without doing it: `make -n crash-test ROUNDS=2`. The `-n` flag prints the commands.
3. Override a variable: `make -n generate ROWS=1000 BAD_EVERY=100` shows `--rows=1000 --bad-every=100`.
4. Watch the file dependency: run `make build` twice; the second prints nothing because the jar is up to date. `touch src/main/resources/application.yml` and run it again; it rebuilds.
5. Check the tab rule: `cat -A Makefile | sed -n 21p` shows `^I` at the start of the command line (that is a Tab).

## Mistakes people make

- **Spaces instead of a Tab** before a command: make stops with "missing separator".
- **A variable set on one line and used on the next.** Each line is a separate shell; join them or use a script.
- **Forgetting `.PHONY`.** A file named like a target makes make say "nothing to do".
- **`$` in recipes.** Write `$$` for the shell's `$`, or make will try to expand it itself.
- **Environment variable overridden by an included file.** `-include .env` assigns variables that beat the same names in the environment; pass overrides on the make command line instead.

## Check yourself

1. What does `-include .env` do when `.env` is missing? *Nothing; the dash suppresses the error.*
2. Why `$$file` and not `$file`? *make would try to expand `$f`; `$$` passes a single `$` to the shell.*
3. What does `?=` do? *Assigns only if the variable has no value yet, so users can override.*
4. Why does `run-twice` start with a dash? *The command is expected to fail (exit 3); the dash tells make to carry on.*
5. When is the jar rebuilt? *When `pom.xml` or a file under `src/main` is newer than the jar.*

## Further reading

- GNU Make manual (the macOS default `make` is GNU Make 3.81; the manual applies): https://www.gnu.org/software/make/manual/make.html
- Phony targets: https://www.gnu.org/software/make/manual/make.html#Phony-Targets
- Variables: https://www.gnu.org/software/make/manual/make.html#Using-Variables
