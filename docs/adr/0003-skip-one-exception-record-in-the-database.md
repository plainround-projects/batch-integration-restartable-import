# 3. Skip exactly one exception, and record each skip in the chunk's transaction

## Context

The lesson skips invalid rows up to a limit and writes each skipped row with its line number and reason to a reject file through a skip listener, appending to the file. Two things go wrong with that after a crash: the chunk that was in flight is processed again and its rejects are appended again, and a file written by a process that was killed can be cut off in the middle of a line.

## Decision

The step skips `InvalidProductException` only (`skip(InvalidProductException.class).skipLimit(500)`). `RejectSkipListener` writes each skipped row to the table `import_reject`, keyed by (source file, line number), with an upsert, through the same transaction as the chunk. `rejects.txt` is exported from that table, in line order, when the run ends.

## Alternatives

- `skip(Exception.class)`: swallows real bugs, up to the limit.
- Appending to a file from the listener: duplicates after a crash and a possibly torn last line.
- Writing the table outside the chunk's transaction: a chunk that rolls back would leave rejects for rows that were not committed (harmless here because of the upsert, but untidy).

## Consequences

- After any number of crashes and restarts the reject file has exactly one line per refused row (100 in the sample), tested by the crash script.
- The record survives in the database, where it can be queried, counted by reason and joined to the job repository.
