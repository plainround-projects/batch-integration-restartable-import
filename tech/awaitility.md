# Awaitility

## What it is

Awaitility is a small Java library for tests that have to **wait** for something that happens in the background: a message arriving, a row appearing in a database, a thread finishing. Instead of `Thread.sleep(5000)` ("wait five seconds and hope"), you write `await().atMost(Duration.ofMinutes(1)).until(() -> condition)`; Awaitility checks the condition again and again (by default every 100 milliseconds) and continues the moment it is true, or fails with a clear message if the time runs out.

## Why this repository uses it

The project rule for tests is "Awaitility for asynchronous flows". It is declared in `pom.xml` (`org.awaitility:awaitility`, version managed by Spring Boot 3.5, test scope) so a test that must wait for background work has the tool at hand.

## Where it appears

| Path | What it does with Awaitility |
| --- | --- |
| `pom.xml` | Declares the dependency with `<scope>test</scope>`. |

No test in this repository needs to wait. The batch job runs synchronously inside each test (`launcher.run` returns when the job has finished), and the crash is made in two ways that do not need waiting: inside a test by a writer that throws, and in `scripts/crash-test.sh` by a shell loop that polls the product count itself (see the Bash note).

## How it works underneath

Awaitility polls: it runs your condition (a lambda that returns `true` or `false`, or an assertion that throws until it holds) on a schedule, with an initial delay, a poll interval and a deadline. Polling happens on a separate thread by default, so a slow condition does not block the clock. When the condition is true the call returns; when the deadline passes it throws `ConditionTimeoutException` with a description of what was awaited.

## Walkthrough of this repository's use

The only place is the dependency declaration:

```xml
    <dependency>
      <groupId>org.awaitility</groupId>
      <artifactId>awaitility</artifactId>
      <scope>test</scope>
```

`<groupId>org.awaitility</groupId>` and `<artifactId>awaitility</artifactId>` are the coordinates; there is no `<version>` because the Spring Boot parent manages it; the next line is `<scope>test</scope>`, which keeps it out of the shipped program.

## Try it yourself

1. Find the declaration: `grep -n -B1 -A3 awaitility pom.xml`.
2. See that Spring Boot manages the version: `./mvnw -q dependency:tree -Dincludes=org.awaitility` prints `org.awaitility:awaitility:jar:...:test`.
3. Confirm no test uses it today: `grep -rn "await()" src/test` prints nothing.
4. Write a throwaway test that waits for a background thread to set a flag: `await().atMost(Duration.ofSeconds(2)).untilTrue(flag)`; delete it afterwards.
5. Make that test fail on purpose by never setting the flag; the failure message says the condition was not fulfilled within 2 seconds.

## Mistakes people make

- **`Thread.sleep` in tests.** It is either too short (a flaky test) or too long (a slow suite). Poll for the condition.
- **Waiting for something that is already synchronous.** If the call returns when the work is done, assert directly.
- **A very long timeout hiding a hang.** Keep `atMost` close to the real worst case.

## Check yourself

1. What does Awaitility do instead of sleeping? *It checks a condition repeatedly until it is true or a deadline passes.*
2. What does it throw on timeout? *`ConditionTimeoutException`.*
3. Why does no test here use it? *The job runs synchronously, so there is nothing to wait for.*
4. Why is there no `<version>` in the pom? *Spring Boot's parent manages it.*
5. What does `<scope>test</scope>` mean? *The library is on the test classpath only.*

## Further reading

- Awaitility usage guide: https://github.com/awaitility/awaitility/wiki/Usage
- Awaitility on Maven Central (version managed by Spring Boot 3.5.x): https://central.sonatype.com/artifact/org.awaitility/awaitility
