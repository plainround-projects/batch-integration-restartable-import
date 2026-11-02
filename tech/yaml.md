# YAML

## What it is

YAML is a text format for writing structured data and settings in a way that is easy for people to read. It is used by Spring Boot's `application.yml`, Docker Compose files, and GitHub Actions workflows. The structure comes from **indentation** (spaces, never Tabs): lines indented under a key belong to it.

The building blocks:

- A **mapping** is a set of `key: value` pairs, one per line.
- A **list** is lines that start with `- `.
- A **scalar** is a single value: text, a number, `true`/`false`. Text usually needs no quotes; use quotes when the text contains `:` or starts with a special character, or to keep a number as text (`"21"`).
- `#` starts a comment that runs to the end of the line.
- `{a: 1, b: 2}` and `[x, y]` are compact (**flow**) forms of a mapping and a list.
- `>` and `|` start multi-line text.

## Why this repository uses it

Three tools in this repository read YAML: Spring Boot (`application.yml`), Docker Compose (`docker-compose.yml`) and GitHub Actions (`.github/workflows/ci.yml`). The same few rules apply to all three.

## Where it appears

| Path | What reads it |
| --- | --- |
| `src/main/resources/application.yml` | Spring Boot. |
| `docker-compose.yml` | Docker Compose. |
| `.github/workflows/ci.yml` | GitHub Actions. |

## How it works underneath

A YAML parser reads the file into nested maps and lists, exactly like JSON. Indentation decides nesting, so a one-space slip changes the meaning or makes the file invalid. Each program that reads YAML decides what the keys mean; YAML itself only gives the shape. A key written twice is usually a mistake; the last or the first wins depending on the reader.

## Walkthrough of this repository's use

Each file is walked through in the note of the tool that reads it. Here is how the YAML rules show up in them.

**Mapping and nesting.** In the compose file, a key with indented lines below it contains them:

```yaml
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -h 127.0.0.1 -U $${POSTGRES_USER} -d $${POSTGRES_DB}"]
      interval: 10s
      timeout: 10s
      retries: 30
      start_period: 5s
```

`healthcheck` is a mapping with four keys. `test` has a list value written in the compact form `["CMD-SHELL", "pg_isready ..."]` (square brackets, items separated by commas); the strings are quoted because they contain spaces and a dollar sign. `interval: 10s` is text that the tool understands as ten seconds; YAML itself just sees the characters. `retries: 30` is a number.

**Lists of mappings.** In the workflow, each step starts with a dash and holds keys:

```yaml
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
          cache: maven
      - run: make lint
      - run: make test
      - run: make audit
```

A line that starts with `- ` begins a new list item. `- uses: actions/checkout@v4` is an item with one key. `- uses: actions/setup-java@v4` followed by an indented `with:` mapping is an item with two keys (`uses` and `with`). `- run: make lint` is an item with one key. The value `"21"` is quoted so it stays text and is not turned into a number.

**Comments and nesting in a settings file.** In `application.yml`, a comment explains a choice and dotted names become nested keys:

```yaml
  batch:
    job:
      enabled: false          # the runner starts the job, with parameters, when asked
    jdbc:
      initialize-schema: never # the schema is sql/001_spring_batch_schema.sql
```

`#` starts a comment. `spring.batch.job.enabled` is written as nested keys `spring:` `batch:` `job:` `enabled:`; the text `false` is a boolean. `initialize-schema: never` is a text value that Spring reads as an enum.

**Placeholders.** The same file uses `${NAME:default}`:

```yaml
import:
  chunk-size: ${IMPORT_CHUNK_SIZE:1000}
  skip-limit: ${IMPORT_SKIP_LIMIT:500}
  retry-limit: ${IMPORT_RETRY_LIMIT:3}
  transient-fault-every-nth-chunk: ${IMPORT_TRANSIENT_FAULT_EVERY_NTH_CHUNK:0}
```

This is not YAML but Spring's own syntax inside a YAML text value: the setting is the environment variable `IMPORT_CHUNK_SIZE` or, if it is not set, `1000`.

## Try it yourself

1. Parse a file and print it as JSON: `python3 -c "import yaml,json; print(json.dumps(yaml.safe_load(open('docker-compose.yml')), indent=1))" | head -30` (needs PyYAML; `pip install pyyaml` in a virtual environment if missing).
2. See what a tab does: copy `docker-compose.yml` to a temporary file, replace two leading spaces on one line with a Tab and run step 1 on it; the parser reports an error.
3. See quoting matter: in a temporary YAML file write `a: 1.10` and `b: "1.10"` and print them with step 1: `a` becomes the number 1.1, `b` stays text.
4. Validate Compose's reading of the file: `docker compose config | head -20` prints the merged result.
5. Count the jobs of the workflow: `grep -c "^  [a-z]*:$" .github/workflows/ci.yml` prints 2.

## Mistakes people make

- **Tabs for indentation.** YAML forbids them; use spaces.
- **Unquoted values that look like something else.** `no`, `on`, `1.10` may be read as booleans or numbers by older parsers. Quote them when in doubt.
- **A colon inside text.** `summary: job: failed` is a parse error; quote the text.
- **Wrong indentation level.** A key at the wrong depth silently becomes a different setting.

## Check yourself

1. How does YAML show that one key belongs to another? *By indentation with spaces.*
2. How do you write a list? *One item per line starting with `- `, or `[a, b]`.*
3. Why quote `"21"`? *To keep it text instead of the number 21.*
4. What starts a comment? *`#`.*
5. Who decides what a key means? *The program that reads the file, not YAML.*

## Further reading

- YAML 1.2.2 specification: https://yaml.org/spec/1.2.2/
- Spring Boot YAML in `application.yml`: https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html#features.external-config.files
