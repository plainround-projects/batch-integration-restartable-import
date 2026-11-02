# Docker Compose

## What it is

Docker Compose describes several containers (and the networks and volumes they share) in one YAML file, `docker-compose.yml`, and starts them all with a single command. Instead of typing a long `docker run` with ten options for every service, you write the options once and run `docker compose up`. Compose creates a private network so the containers can reach each other by service name.

## Why this repository uses it

The only service is PostgreSQL, but it needs a password from the environment, a fixed port and a health check so that `make up` returns only when the database can take connections. The named volume matters for the lesson: the database must keep its rows when the Java process is killed, and `make down` removes everything when you are finished.

## Where it appears

| Path | What it does |
| --- | --- |
| `docker-compose.yml` | The PostgreSQL service, its volume and its network. |
| `Makefile` | `make up` runs `docker compose up -d --wait`; `make down` runs `docker compose down -v`; `PSQL` uses `docker compose exec`. |
| `.github/workflows/ci.yml` | Brings the same service up in CI. |
| `scripts/crash-test.sh` | Runs `docker compose exec` to count rows during the test. |

## How it works underneath

`docker compose up -d` reads the file, creates the network and volumes, pulls missing images, and starts one container per service. `-d` (detached) returns the terminal at once; `--wait` also waits until each service's health check passes. A **health check** is a command Docker runs inside the container on a timer; when it succeeds enough times the container is marked *healthy*. `docker compose exec service command` runs a command inside a running container. `docker compose down -v` stops everything and also deletes the volumes (and so the data). The top-level `name:` becomes the prefix of the container, volume and network names, so two projects do not collide. Values like `${POSTGRES_PORT:-55541}` are filled from the environment or from a `.env` file next to the compose file, with the text after `:-` as the default; `${VAR:?message}` makes Compose stop with the message when the variable is missing.

## Walkthrough of this repository's use

```yaml
# The database for the import: the product table, the reject record and Spring Batch's job repository.
# Local use only. The import itself runs on the host (`make run`) or from the image (`make docker-build`).
name: batch-integration-restartable-import

services:
  postgres:
    image: postgres:16
    ports:
      - "${POSTGRES_PORT:-55531}:5432"
    environment:
      POSTGRES_USER: ${POSTGRES_USER:-catalog}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?copy .env.example to .env first}
      POSTGRES_DB: ${POSTGRES_DB:-catalog}
```

The comments say what the file is for. Line 3, `name: batch-integration-restartable-import`, prefixes every resource Compose creates. `services:` (line 5) lists the containers; `postgres:` is one service. `image: postgres:16` uses the official PostgreSQL 16 image. `ports` publishes container port 5432 on host port `${POSTGRES_PORT:-55531}` (default 55531, an unusual number so it does not collide with a PostgreSQL you may already run; `${NAME:-default}` means the variable's value or the default). `environment` sets the variables the image reads at first start to create the user, password and database; `POSTGRES_PASSWORD` uses `${POSTGRES_PASSWORD:?copy .env.example to .env first}`, so Compose refuses to start without a password and prints that message.

```yaml
    shm_size: 256mb
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -h 127.0.0.1 -U $${POSTGRES_USER} -d $${POSTGRES_DB}"]
      interval: 10s
      timeout: 10s
      retries: 30
      start_period: 5s
    volumes:
      - catalog-data:/var/lib/postgresql/data
```

`shm_size: 256mb` enlarges the shared memory (`/dev/shm`) PostgreSQL uses; the default 64 MB can be too small. `healthcheck`: `test` runs `pg_isready` inside the container (`CMD-SHELL` runs the text in a shell; `$${POSTGRES_USER}` has two dollars so Compose passes a single one to the shell inside the container instead of expanding it itself; `-h 127.0.0.1` checks over the network socket, which is only open once PostgreSQL has finished its start-up). It checks every 10 seconds (`interval`), allows 10 seconds per check (`timeout`), gives up after 30 failures (`retries`) and ignores failures for the first 5 seconds (`start_period`). `volumes` mounts the named volume `catalog-data` at PostgreSQL's data folder, so the rows survive a restart of the container.

```yaml
volumes:
  catalog-data:

networks:
  default:
    ipam:
      config:
        - subnet: 10.231.4.0/24
```

The top-level `volumes:` declares the named volume (an empty value means "use defaults"). The `networks` block configures the project's default network with a fixed address range (`ipam`, IP address management: `subnet: 10.231.4.0/24`). A fixed range avoids the "all predefined address pools have been fully subnetted" error when many projects run at once; each repository in this track uses its own range.

## Try it yourself

1. Start it: `make up`. Expect a line ending `Healthy` for the container `batch-integration-restartable-import-postgres-1`.
2. See the state: `docker compose ps` shows `healthy` in the STATUS column.
3. Run SQL inside: `docker compose exec -T postgres psql -U catalog -d catalog -c "select version()"` prints a line starting `PostgreSQL 16`.
4. Show the missing-password message: with no `.env` and the variable unset, `docker compose config 2>&1 | head -2` asks you to copy the example file.
5. Stop and delete: `make down`; `docker volume ls | grep catalog` prints nothing.

## Mistakes people make

- **Port already in use.** Two stacks that publish the same host port cannot both start. Each repository in this track uses its own high port.
- **Expecting data to persist after `down -v`.** The `-v` deletes volumes. That is the intent for a lesson database.
- **A health check that passes too early.** Checking only that the process runs, not that it accepts connections, lets `--wait` return before the database is usable.
- **Putting passwords in the file.** Here the password comes from `.env` through `${POSTGRES_PASSWORD:?...}`.
- **`localhost` inside a container.** It means the container itself. Containers reach each other by service name.

## Check yourself

1. What does `${POSTGRES_PORT:-55531}` mean? *The variable's value, or 55531 if it is not set.*
2. Why `$${POSTGRES_USER}` in the health check? *The double dollar passes a single dollar so the shell inside the container expands it.*
3. What does `--wait` add to `up -d`? *It waits until the service's health check passes.*
4. What does the named volume do? *Keeps the database files between container restarts, until `down -v`.*
5. Why a fixed subnet? *To avoid address-pool exhaustion when many compose projects run on one machine.*

## Further reading

- Compose file reference (Compose Specification): https://docs.docker.com/reference/compose-file/
- `docker compose up`: https://docs.docker.com/reference/cli/docker/compose/up/
- Interpolation (`${VAR:-default}`): https://docs.docker.com/reference/compose-file/interpolation/
- Healthchecks: https://docs.docker.com/reference/compose-file/services/#healthcheck
