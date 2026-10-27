# 4. Recover executions left running by a dead process, but only while holding an advisory lock

## Context

Spring Batch will not start a job instance whose latest execution is recorded as running. After `kill -9` that is always the case: the process never wrote the failure. The standard remedy is to mark the execution failed by hand. Doing that automatically is dangerous if the "running" execution belongs to a live process on another machine.

## Decision

Before launching, `ImportCatalog` takes a PostgreSQL advisory lock (`pg_try_advisory_lock`) on a connection held for the whole run. The server releases the lock when that connection ends, so a killed process cannot leave it behind, and a held lock means a live process. With the lock held, `StaleExecutionRecovery` marks every execution of the job that is still recorded as running (and its running steps) as FAILED, keeps their counts, and adds an exit description. The restart then resumes from the reader's saved position. If the lock is held, the run ends with status BLOCKED and exit code 4.

## Alternatives

- Mark running executions failed without a lock: wrong when two instances run at once.
- A heartbeat column updated by the running job: works, and needs a thread, a timeout and a policy for clock skew.
- Leave recovery to a person: correct, and means a 3 a.m. page for a routine event.

## Consequences

- The recovery is exercised on every restart in the crash test, and unit-tested through a fake lock (`ImportCatalogTest`) and a real one (`CatalogImportIT`).
- The lock key is fixed for the project. A second job against the same database would need its own key (a limit in ARCHITECTURE.md).
