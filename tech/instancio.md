# Instancio

## What it is

Instancio is a Java library that creates **test objects filled with random values**. Writing the data for a test by hand (`new Order("A1", "Ann", 12.5, ...)`) is slow and hides which fields matter. With Instancio you write `Instancio.create(Order.class)` and get a complete object; you set only the fields the test cares about (`Instancio.of(Order.class).set(field(Order::status), "PAID").create()`). Because the data is random, tests also catch code that accidentally depends on a particular value. Instancio prints the **seed** of every failing test so you can replay the exact data.

## Why this repository uses it

The project rule for tests is "Instancio for test data". The rule tests need many rows that differ in one field only (a bad price, a bad category), so each test starts from a complete valid row and changes the one field it is about. The library is pinned in `pom.xml` through the property `instancio.version` (5.5.1), because Spring Boot does not manage it.

## Where it appears

| Path | What it does with Instancio |
| --- | --- |
| `pom.xml` | The property `instancio.version` and the test-scoped dependency `org.instancio:instancio-junit`. |
| `${T}/domain/ProductRulesTest.java` | Builds the rows the validation rules are tested with. |

## How it works underneath

Instancio uses Java's reflection (the ability of a program to inspect classes and fields at run time) to find the fields of the class you ask for, and fills each with a random value of the right type: numbers, strings, dates, collections, nested objects. A **generator** controls values (`gen.string().length(5)`); a **selector** (`field(...)`, `allInts()`) picks which fields a rule applies to. The JUnit extension `instancio-junit` seeds the random generator per test and reports the seed on failure.

## Walkthrough of this repository's use

```xml
    <archunit.version>1.4.1</archunit.version>
    <instancio.version>5.5.1</instancio.version>
```

`archunit.version` (line 24) and `instancio.version` (line 25) are versions of libraries outside Spring Boot's managed list, kept in properties so each version appears once.

```xml
    <dependency>
      <groupId>org.instancio</groupId>
      <artifactId>instancio-junit</artifactId>
      <version>${instancio.version}</version>
      <scope>test</scope>
    </dependency>
```

The dependency: the artifact `instancio-junit` brings the core library and the JUnit 5 extension; the version comes from the property; `<scope>test</scope>`.

```java
package plainround.catalogimport.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;

import java.math.BigDecimal;
import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

```

Line 4, `import static org.instancio.Select.field;`, lets the test write `field(...)` for a **selector**; line 7, `import org.instancio.Instancio;`, brings the library's entry point. The method `valid()` is the use:

```java

  private ProductRow valid() {
    return Instancio.of(ProductRow.class)
        .set(field(ProductRow::sku), "SKU-000123")
        .set(field(ProductRow::category), "books")
        .set(field(ProductRow::price), new BigDecimal("12.50"))
        .create();
```

`Instancio.of(ProductRow.class)` starts a builder for the record. Each `.set(field(ProductRow::sku), "SKU-000123")` fixes one field: `ProductRow::sku` is a method reference to the record's accessor, so the selector is checked by the compiler (a renamed field breaks the build instead of a string). The three fields the rules look at (SKU, category, price) are fixed to values that pass; the **line number** and the **name** stay random, because no rule looks at them. `.create()` builds the object. Every test then takes this valid row and changes one thing, for example the helper `withPrice` creates a copy with another price, so a failing assertion points at exactly one field.

## Try it yourself

1. Find the version: `grep -n instancio pom.xml`.
2. See the use: `grep -n "Instancio" src/test/java/plainround/catalogimport/domain/ProductRulesTest.java`.
3. Run the class: `./mvnw -q test -Dtest=ProductRulesTest`; expect it to pass.
4. Remove one `.set(...)` for the price in `valid()` and run again: a random price breaks the "valid row is accepted" test, which shows why the fields the rules read must be fixed. Undo it.
5. Reproduce a run: Instancio prints a seed on a failure; run with `-Dinstancio.seed=123` (or annotate the test with `@Seed(123)`) and the same random line numbers come back.

## Mistakes people make

- **Random data where the exact value is the point.** A rule such as "price must be above zero" needs chosen numbers; set those fields.
- **Not replaying the seed.** A failure that "only happens sometimes" is reproducible once you set the printed seed.
- **Filling objects that have validation rules** without constraining the fields; set the fields that rules check.

## Check yourself

1. What does `Instancio.create(X.class)` return? *An `X` with all fields filled with random values.*
2. How do you fix one field? *`.set(field(X::name), value)` before `.create()`.*
3. Why use `ProductRow::sku` and not the text `"sku"`? *The compiler checks the method reference; a rename cannot silently break the test.*
4. How do you reproduce a failing random test? *Reuse the seed Instancio printed.*
5. Which mechanism lets Instancio fill fields it has never seen? *Reflection.*

## Further reading

- Instancio user guide: https://www.instancio.org/user-guide/
- Instancio 5.5.1 on Maven Central: https://central.sonatype.com/artifact/org.instancio/instancio-junit/5.5.1
