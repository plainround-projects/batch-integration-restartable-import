# Changelog

All notable changes to this project are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [0.1.0] - 2026-10-27

### Added

- Spring Batch 5.2 import of a 200,000-row product catalog (a chunk of 1,000, one step): a flat-file reader that keeps each line number, a validator that returns a Result and refuses a bad price, category or SKU, and an upsert writer keyed by SKU.
- A skip limit of 500 on exactly one exception, with each refused row recorded (file, line, raw line, reason) in the chunk's transaction, and `rejects.txt` exported from that table.
- Retry of transient database errors only (three attempts); a constraint violation is written once and not retried.
- Restart after `kill -9`: a PostgreSQL advisory lock proves no live process owns a run, executions left running by a dead process are marked failed, and the same parameters resume at the last committed chunk. Graceful stop on SIGTERM and SIGINT.
- The `step_summary` view (the three skip counters added up) and SQL for the job repository, the product table, the reject table and the lesson's queries.
- Commands `import`, `generate`, `summary` and `health`, with exit codes for completed, failed, already complete and blocked; `make crash-test` runs the kill-and-restart rounds.
- Layered packages (`domain`, `application`, `infrastructure`, `interfaces`) with the dependency rule enforced by ArchUnit; unit, integration and end-to-end tests with 95 percent line coverage required on domain and application; google-java-format, `-Xlint:all -Werror`, a Dockerfile, a compose file and a CI workflow.
