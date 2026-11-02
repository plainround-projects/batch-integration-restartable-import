# AssertJ

## What it is

AssertJ is a library of **assertions**: statements in a test that say what must be true. You write `assertThat(actual).isEqualTo(expected)` and, if the claim is false, AssertJ throws an error whose message shows both values. Compared with JUnit's own `assertEquals`, AssertJ reads left to right like a sentence and has methods for lists (`hasSize`, `containsExactly`), text (`contains`, `startsWith`), numbers (`isZero`, `isGreaterThanOrEqualTo`) and exceptions (`assertThatThrownBy(...).hasMessageContaining(...)`).

## Why this repository uses it

Every assertion in the tests is AssertJ. The chained style lets a test read like a sentence ("the report has status BLOCKED, the message contains the holder and the exit code is 4"), and a failure shows both values.

## Where it appears

| Path | What it does |
| --- | --- |
| Every test class under `src/test/java/plainround/catalogimport/` | `assertThat`, `assertThatThrownBy`. |

## How it works underneath

`assertThat(x)` looks at the **type** of `x` and returns the matching assertion object (one for lists, one for strings, one for numbers). Each method on it checks one thing and returns the same object, which is why calls chain: `assertThat(text).contains("a").contains("b")`. The first failing check throws `AssertionError`, which JUnit reports as a failure. `.as("label")` adds a description to the failure message. `assertThatThrownBy(() -> code)` runs the lambda and captures the exception it throws, so you can check its type and message; if no exception is thrown the test fails.

## Walkthrough of this repository's use

The tests are walked through in the JUnit note; this note lists the AssertJ calls with one quoted example each.

```java
  @Test
  void a_held_lock_means_nothing_runs_and_the_report_says_blocked() {
    ImportReport report = useCase(new FakeRunLock(false), r -> completed()).run(request);
    assertThat(calls).isEmpty();
    assertThat(report.status()).isEqualTo(Status.BLOCKED);
    assertThat(report.message()).contains("held by pid 42");
    assertThat(report.exitCode()).isEqualTo(4);
  }
```

`isEmpty()` on a list, `isEqualTo` on an enum, `contains` on text, and `isEqualTo(4)` on a number.

```java
  @Test
  void the_lock_is_released_even_when_the_job_throws() {
    FakeRunLock lock = new FakeRunLock(true);
    ImportJob exploding =
        r -> {
          throw new IllegalStateException("database gone");
        };
    assertThatThrownBy(() -> useCase(lock, exploding).run(request)).hasMessage("database gone");
    assertThat(lock.released).hasValue(1);
  }
```

`assertThatThrownBy(...).hasMessage(...)` checks the exception and its exact message; `assertThat(lock.released).hasValue(1)` reads an `AtomicInteger`.

```java
  @Test
  void a_valid_row_comes_back_unchanged() {
    ProductRow row = valid();
    assertThat(ProductRules.validate(row).discardError()).contains(row);
  }
```

`discardError()` gives an `Optional`; `contains(row)` on an optional asserts it holds that value.

```java
  @ParameterizedTest
  @CsvSource({"87.12,87.12", "64.5,64.5", "0,0", "-5,-5", "' 3.10 ',3.10"})
  void a_price_field_is_parsed_exactly(String field, String expected) {
    assertThat(ProductRules.parsePrice(field)).isEqualByComparingTo(new BigDecimal(expected));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @CsvSource({"abc", "'1,5'", "1e3", "--2"})
  void anything_else_in_the_price_field_is_no_price(String field) {
    assertThat(ProductRules.parsePrice(field)).isNull();
  }
```

`isEqualByComparingTo` compares decimals by numeric value; `isNull()` checks for `null`.

```java
  @Test
  void the_categories_are_the_lessons_five() {
    assertThat(ProductRules.categories())
        .containsExactlyInAnyOrder("books", "toys", "garden", "kitchen", "sport");
  }
```

`containsExactlyInAnyOrder` checks the elements without regard to order.

```java
  @Test
  void a_clean_run_loads_every_valid_row_skips_the_bad_ones_and_records_them() throws Exception {
    ImportReport report = run("first");
    assertThat(report.status()).isEqualTo(Status.COMPLETED);
    assertThat(report)
        .extracting("read", "written", "skipped")
        .containsExactly(20_000L, 19_990L, 10L);
    assertThat(products()).isEqualTo(19_990);
    List<String> lines = Files.readAllLines(rejects);
    assertThat(lines).hasSize(10);
    assertThat(lines.getFirst())
        .startsWith("line 2001: SKU-002000,weapons,")
        .endsWith("-> unknown category weapons");
  }
```

`extracting("read", "written", "skipped")` reads those properties of the report and `containsExactly(20_000L, 19_990L, 10L)` compares them in order; `hasSize(10)` on a list; `.startsWith(...).endsWith(...)` chained on one text.

```java
  @Test
  void the_processor_returns_a_valid_row_and_throws_the_one_skippable_exception_for_a_bad_one() {
    ProductItemProcessor processor = new ProductItemProcessor();
    ProductRow good = mapper.mapLine("SKU-000001,n,books,10.00", 2);
    ProductRow bad = mapper.mapLine("SKU-000002,n,books,0", 3);
    assertThat(processor.process(good)).isSameAs(good);
    assertThatThrownBy(() -> processor.process(bad))
        .isInstanceOf(InvalidProductException.class)
        .hasMessage("price must be above zero");
  }
```

`isSameAs` checks object identity (the very same object), unlike `isEqualTo`; `isInstanceOf` checks a type.

```java
  @Test
  void the_exception_carries_the_row_and_the_reason() {
    ProductRow row = new ProductRow(9, "BAD9", "n", "books", BigDecimal.ONE);
    InvalidProductException error =
        new InvalidProductException(row, new Rejection("bad SKU format"));
    assertThat(error).hasMessage("bad SKU format");
    assertThat(error.getRow()).isEqualTo(row);
  }
```

`assertThat(error).hasMessage("bad SKU format")` on an exception object.

## Try it yourself

1. See a failure message: change `isEqualTo(4)` to `isEqualTo(5)` in `ImportCatalogTest` (the blocked test) and run `./mvnw -q test -Dtest=ImportCatalogTest`; the message prints `expected: 5 but was: 4`. Undo it.
2. Find every use: `grep -rn "assertThat" src/test | wc -l`.
3. Read a chained text check: `grep -n "startsWith" src/test/java/plainround/catalogimport/e2e/LessonScenarioE2E.java`.
4. Replace `isSameAs(good)` with `isEqualTo(good)` in `BatchComponentsTest` and see it still passes (records are equal by value); then change the processor to return a copy and see only `isSameAs` fail. Undo it.
5. List the library's assertion classes: `unzip -l ~/.m2/repository/org/assertj/assertj-core/*/assertj-core-*.jar | grep "api/Abstract.*Assert.class" | head`.

## Mistakes people make

- **Forgetting the final check.** `assertThat(x)` alone checks nothing; it must be followed by a method.
- **Comparing floating-point numbers with `isEqualTo`.** Use `isCloseTo` or exact decimals. This repository's money is `BigDecimal`.
- **Asserting on a message too exactly.** A test that repeats a long message breaks on every wording change; use `hasMessageContaining` for the essential words.

## Check yourself

1. What does `containsExactly` require? *Exactly those elements, in that order.*
2. What is the difference between `isEqualTo` and `isSameAs`? *Equal values versus the same object.*
3. How do you test that code throws? *`assertThatThrownBy(() -> code)` then check the type or message.*
4. What does `extracting("read", "written")` do? *Reads those properties from the object and checks them as a list.*
5. Why is `assertThat(x)` alone a bug? *It performs no check until a method follows.*

## Further reading

- AssertJ core documentation: https://assertj.github.io/doc/
- AssertJ Core API (the version Spring Boot 3.5 manages, 3.27): https://www.javadoc.io/doc/org.assertj/assertj-core/3.27.3/
