# 2. The writer is an upsert keyed by the SKU

## Context

A restart repeats the chunk that was in flight, and a retry repeats a chunk by design. A plain `INSERT` makes the repeat fail on the key or, with a generated key, double the rows.

## Decision

`INSERT ... ON CONFLICT (sku) DO UPDATE SET name, category, price`. The key is the business key. A chunk written twice leaves one row per SKU, and a later run with a changed price in the file updates the row.

## Alternatives

- `INSERT ... ON CONFLICT DO NOTHING`: a repeat is harmless, but a corrected row in a later file is silently ignored.
- A staging table and a final merge: needed for very large loads or constraint-heavy targets; unnecessary here.
- A generated primary key: the lesson's named mistake.

## Consequences

- Idempotence is a property of the write, not of the scheduler: the job may be restarted, retried or run again and the table does not depend on how many times.
- The cost is the unique index check on every row; at 200,000 rows it is a few seconds.
