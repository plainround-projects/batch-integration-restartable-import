# 1. Ports and adapters around Spring Batch, with the rules as plain Java returning a Result

## Context

A Spring Batch job is mostly framework: a reader, a processor, a writer and listeners wired by builders. The part that is the business is tiny (three validation rules), and the part that is operational (what to do after a crash) is easy to bury in the configuration. A job whose rules need a database and a running job repository to be tested is slow to change.

## Decision

Four layers in `plainround.catalogimport`. `domain` holds the rules (`ProductRules.validate` returns a `Result<ProductRow, Rejection>` from Result4J), the records and the named exception; `application` holds the use case (`ImportCatalog`) and the ports it needs (`RunLock`, `CrashRecovery`, `ImportJob`, `RejectLog`); `infrastructure` holds Spring Batch (`CatalogJobConfiguration`, the mapper, the processor, listeners) and JDBC; `interfaces` holds the command-line runner and the composition root. ArchUnit fails the build if a lower layer depends on a higher one or if `domain` or `application` touch Spring or JDBC. The one place a `Result` becomes an exception is `ProductItemProcessor`, because Spring Batch's skip policy is driven by exceptions.

## Alternatives

- Everything in one `@Configuration` class (the lesson's sketch): shorter, and the validation can only be tested through a running step.
- Throwing exceptions from the domain: simpler, but then "expected failure" and "bug" look alike, and the skip list would have to be `Exception`.

## Consequences

- The rules and the use case are unit-tested in milliseconds at 95 percent coverage or more; the job is tested against a real PostgreSQL.
- Adding a rule is a method in `ProductRules` and a test, with no change to the batch wiring.
