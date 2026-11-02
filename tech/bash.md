# Bash and shell scripts

## What it is

A **terminal** is a window where you type commands as text and read the answers as text. The program inside it that reads what you type and runs it is called a **shell**. The shell used on most Macs and Linux machines is **Bash**; a smaller, older language that Bash also understands is the **POSIX shell** (`sh`). A **shell script** is a text file of commands that the shell runs top to bottom, so you can do in one command what would otherwise be twenty typed lines.

A command is a program name followed by **arguments**: in `ls -l src`, `ls` is the program, `-l` is an **option** (a flag that changes behavior) and `src` is an argument. Every program, when it ends, returns a number called its **exit code**: `0` means success and any other number means failure. The shell remembers the last exit code in the variable `$?`.

## Why this repository uses it

The lesson's step 5 needs a sequence no single command can express and no Java test can do: start the import, wait until it has written some rows, kill it without warning with `kill -9`, restart it with the same parameters, and compare the table with what a correct run would leave. A test cannot kill its own process and then look at the result; a shell script can, from outside. It runs that sequence several times, each from an empty database. A `make` target calls it, so a reader types `make crash-test`.

## Where it appears

| Path | What it does |
| --- | --- |
| `scripts/crash-test.sh` | The `kill -9` rounds (`make crash-test`). |
| `mvnw` | The Maven Wrapper, itself a long shell script (see the Maven Wrapper note). |
| `Makefile` | Its recipes are shell commands. |
| `.github/workflows/ci.yml` | `run:` steps are shell commands. |

## How it works underneath

The first line of a script, `#!/bin/sh`, is the **shebang**: it tells the operating system which program should run the file. When you start `sh scripts/benchmark.sh` the shell reads the file one line at a time and executes each command in order.

The pieces this repository's scripts use:

- **Variables.** `name=value` stores text (no spaces around `=`); `$name` or `${name}` reads it. `${name:-default}` means "the value of `name`, or `default` if it is unset or empty". Variables the shell passes on to programs it starts are **environment variables**; `export name` or writing `NAME=value command` makes a variable visible to that command.
- **Quotes.** Double quotes `"..."` keep the text together as one word and still expand `$variables`; single quotes `'...'` keep everything literally. Without quotes the shell splits on spaces, which breaks file names that contain spaces. Always quote variables.
- **Command substitution.** `$(command)` runs the command and puts its output in place.
- **Pipes.** `a | b` connects the standard output of `a` to the standard input of `b`, so data flows from one program into the next. `tr`, `sed`, `grep`, `head`, `cut` are small programs built for pipelines.
- **Redirection.** `> file` writes standard output to a file (replacing it), `>> file` appends, `< file` reads standard input from a file, `2> file` redirects standard error, `2>&1` sends standard error to wherever standard output goes, `> /dev/null` throws output away.
- **Lists.** `a && b` runs `b` only if `a` succeeded; `a || b` runs `b` only if `a` failed; `a; b` runs both; `a &` starts `a` in the background and `wait` waits for background jobs.
- **Conditions and loops.** `if ...; then ...; fi`, `case x in pattern) ...;; esac`, `for x in list; do ...; done`, `while ...; do ...; done`, `until ...; do ...; done`.
- **Functions.** `name() { ...; }` defines a reusable block; `$1`, `$2` are its arguments.
- **`set -eu`.** `set -e` stops the script at the first command that fails; `set -u` stops it when a variable that was never set is used. Together they turn silent mistakes into loud ones.
- **`trap`.** `trap 'command' EXIT` runs `command` when the script ends, whatever the reason; used to delete temporary files.

## Walkthrough of this repository's use

### `scripts/crash-test.sh`

```bash
#!/bin/sh
# The lesson's step 5: kill -9 the import at a random point, restart it with the same parameters, and check that the
# table has exactly one row per valid SKU. Repeated ROUNDS times (default 5), each from an empty database.
#
# Needs: make up, make db, make build, and data/products.csv (make generate).
set -eu

ROUNDS="${1:-5}"
JAR="${JAR:-target/restartable-import-0.1.0.jar}"
FILE="${FILE:-data/products.csv}"
EXPECTED_PRODUCTS="${EXPECTED_PRODUCTS:-199900}"
EXPECTED_REJECTS="${EXPECTED_REJECTS:-100}"
PSQL="docker compose exec -T postgres psql -U ${POSTGRES_USER:-catalog} -d ${POSTGRES_DB:-catalog} -v ON_ERROR_STOP=1 -q"
mkdir -p logs
```

Line 1 is the shebang; `/bin/sh` is the POSIX shell (the portable subset, so the script runs on a Mac, in CI and in a container). Lines 2 to 5 are comments (everything after `#`) saying what the script does and what it needs. Line 6, `set -eu`, stops the script on the first failing command (`-e`) and on any unset variable (`-u`).

Line 8, `ROUNDS="${1:-5}"`, sets `ROUNDS` to the first argument, or 5 if none was given (`${name:-default}`). Lines 9 to 12 do the same for the jar path, the file, and how many products and rejects a correct run leaves (199,900 and 100); each uses the value from the environment when there is one. Line 13 builds the `psql` command in a variable (`-U` user, `-d` database; the options are explained in the Make note). Line 14, `mkdir -p logs`, creates the log folder if needed.

```bash
products() { $PSQL -tAc "SELECT count(*) FROM product"; }

# Restart with the same parameters; the lock of the killed process is released as soon as the server sees the socket close.
restart() {
  attempt=1
  while [ "$attempt" -le 15 ]; do
    status=0
    java -jar "$JAR" --command=import --import.file="$FILE" --run.id="$1" > "logs/restart-$1.out" 2> "logs/restart-$1.err" || status=$?
    [ "$status" -ne 4 ] && return "$status"
    attempt=$((attempt + 1)); sleep 2
  done
  return 4
}
```

Line 16 defines a **function**, `products`, that asks the database how many rows the product table has: `-tAc` is three options together, `-t` print only the rows (no headers), `-A` unaligned, `-c` run this one command.

The function `restart` (19 to 28) starts the import again with the same run id, which is its first argument (`$1`). Line 20 sets a counter. The `while [ "$attempt" -le 15 ]` loop (`-le` means "less than or equal", the brackets are the `test` command) runs up to 15 times. Line 22 sets `status=0`. Line 23 runs the jar with standard output going to one file (`>`) and standard error to another (`2>`); `|| status=$?` means "if it fails, store its exit code" (`$?` is the exit code of the last command) and also keeps `set -e` from ending the script. Line 24 says: if the code is not 4, return it. Code 4 means another run still holds the advisory lock, which happens for a moment after a kill because the database has not yet noticed the dead connection; so line 25 adds 1 to the counter (`$((attempt + 1))` is arithmetic) and sleeps 2 seconds, then the loop tries again. Line 27 returns 4 if all 15 attempts were refused.

```bash
failures=0
round=1
while [ "$round" -le "$ROUNDS" ]; do
  run_id="crash-$round-$(date +%s)"
  $PSQL < sql/reset.sql
  rm -f rejects.txt
  threshold=$(( 20000 + $(od -An -N2 -tu2 /dev/urandom | tr -d ' ') % 150000 ))
  java -jar "$JAR" --command=import --import.file="$FILE" --run.id="$run_id" > "logs/first-$run_id.out" 2> "logs/first-$run_id.err" &
  pid=$!
  while kill -0 "$pid" 2> /dev/null && [ "$(products)" -lt "$threshold" ]; do sleep 0.2; done
  if kill -0 "$pid" 2> /dev/null; then
    kill -9 "$pid"; wait "$pid" 2> /dev/null || true
    echo "round $round/$ROUNDS: killed with kill -9 after about $(products) products (threshold $threshold)"
  else
    echo "round $round/$ROUNDS: the job finished before the threshold ($threshold); nothing to crash" >&2
  fi
```

Lines 30 and 31 start the failure counter and the round number. The outer `while` loop runs once per round. Line 33 makes a unique run id from the round and the clock. Line 34 empties the database by redirecting `reset.sql` into `psql`; line 35 removes the old `rejects.txt`. Line 36 picks the random point at which to kill: `od -An -N2 -tu2 /dev/urandom` reads 2 random bytes and prints them as a number (`od` is "octal dump", `-An` hides the address column, `-N2` reads two bytes, `-tu2` prints unsigned 2-byte numbers), `tr -d ' '` removes the spaces, `% 150000` takes the remainder, and `20000 + ...` makes it at least 20,000; the whole expression sits in `$(( ... ))` for arithmetic.

Line 37 starts the first import **in the background** (the `&` at the end of the line), with its output sent to log files. Line 38, `pid=$!`, stores the process id of the background job. Line 39 is a waiting loop: `kill -0 "$pid"` sends no signal but succeeds only if the process still exists; `2> /dev/null` hides its error text; the loop continues while the process is alive and the product count is below the threshold, sleeping 0.2 seconds each pass. Lines 40 to 45: if the process is still alive (the threshold was reached), `kill -9 "$pid"` ends it at once, with no chance to clean up (signal 9 cannot be caught); `wait "$pid" 2> /dev/null || true` collects its exit status without failing the script; the script prints the round and the count. Otherwise the job finished before the threshold and the script says so on standard error (`>&2`).

```bash
  echo "-- job repository right after the kill:"
  java -jar "$JAR" --command=summary 2> /dev/null | sed -n '1,3p'
  status=0; restart "$run_id" || status=$?
  count=$(products)
  distinct=$($PSQL -tAc "SELECT count(DISTINCT sku) FROM product")
  rejects=$(wc -l < rejects.txt | tr -d ' ')
  echo "-- after the restart (exit $status): $(cat "logs/restart-$run_id.out")"
  java -jar "$JAR" --command=summary 2> /dev/null | sed -n '1,4p'
  if [ "$status" -eq 0 ] && [ "$count" -eq "$EXPECTED_PRODUCTS" ] && [ "$distinct" -eq "$EXPECTED_PRODUCTS" ] && [ "$rejects" -eq "$EXPECTED_REJECTS" ]; then
    echo "round $round/$ROUNDS: OK ($count products, $distinct distinct SKUs, $rejects rejects)"
  else
    echo "round $round/$ROUNDS: WRONG ($count products, $distinct distinct, $rejects rejects, exit $status)" >&2
    failures=$((failures + 1))
  fi
  round=$((round + 1))
done
echo "$((ROUNDS - failures)) of $ROUNDS crash-and-restart rounds ended with exactly one row per valid SKU"
[ "$failures" -eq 0 ]
```

Line 46 prints a heading, and line 47 shows the job repository right after the kill: `--command=summary` prints a report and `sed -n '1,3p'` keeps only lines 1 to 3. Line 48 restarts: `status=0; restart "$run_id" || status=$?`. Lines 49 to 51 collect the evidence: the product count, the number of distinct SKUs (`count(DISTINCT sku)`), and the number of lines in `rejects.txt` (`wc -l < rejects.txt` counts lines; `tr -d ' '` removes the padding some systems add). Lines 52 and 53 print the restart's report (`$(cat file)` puts a file's contents into the text) and the first four lines of the summary. Lines 54 to 59 judge the round: `[ ... ] && [ ... ]` is "and"; the round is OK when the restart exited 0, the product count and distinct count are both 199,900 and there are 100 rejects; otherwise it prints WRONG on standard error and adds 1 to `failures`. Line 60 moves to the next round. Line 62 prints the verdict line the README quotes: `$((ROUNDS - failures))` of the rounds were correct. The last line, `[ "$failures" -eq 0 ]`, makes the script's own exit code 0 only if every round was right, so `make crash-test` fails when a round does.

## Try it yourself

1. Run the script: `make up db` then `make crash-test ROUNDS=2`. Expect two blocks that end `round 1/2: OK (199900 products, 199900 distinct SKUs, 100 rejects)` and the line `2 of 2 crash-and-restart rounds ended with exactly one row per valid SKU`.
2. Read the exit code of a failing command: `java -jar target/restartable-import-0.1.0.jar --command=nothing; echo $?` prints `unknown command` and then `2`.
3. See the difference quoting makes: `name="a b"; printf '[%s]\n' $name; printf '[%s]\n' "$name"` prints two lines then one.
4. Check that a process exists without disturbing it: `sleep 30 & kill -0 $!; echo $?` prints `0`; then `kill $!; kill -0 $! 2>/dev/null; echo $?` prints `1`.
5. Run the script with tracing to see every command: `sh -x scripts/crash-test.sh 1 2>&1 | head -40`.

## Mistakes people make

- **Unquoted variables.** `rm -rf $dir/x` with an empty `dir` removes `/x`. Quote, and use `set -u`.
- **Assuming a command failed when it only printed an error.** Look at the exit code (`$?`) or use `set -e`.
- **Pipelines hide failures.** In `a | b` the exit code is `b`'s unless `set -o pipefail` is on (Bash, not POSIX `sh`).
- **Parsing `ls`.** Use globs (`*.sql`) or `find`.
- **Bash-only features in a `#!/bin/sh` script.** Arrays and `[[ ]]` do not exist in POSIX `sh`; this repository's scripts stay within POSIX features.
- **Scripts that depend on the folder you run them from.** The scripts here are written to run from the repository root, which is how `make` runs them.

## Check yourself

1. What does the first line `#!/bin/sh` do? *Tells the system to run the file with the POSIX shell.*
2. What does `set -eu` change? *The script stops at the first failing command and at the first unset variable.*
3. Why does the script write `|| status=$?`? *To record the exit code of an expected failure without stopping the script.*
4. What does `kill -9` do that `kill` does not? *It cannot be caught, so the program gets no chance to clean up; that is what a crash looks like.*
5. Why does `restart` retry on exit code 4? *The killed process's lock is released only when the database notices the closed connection; until then the new run is refused.*

## Further reading

- POSIX shell command language (the language of `#!/bin/sh`): https://pubs.opengroup.org/onlinepubs/9699919799/utilities/V3_chap02.html
- GNU Bash reference manual (Bash 3.2 is the macOS default; 5.x elsewhere): https://www.gnu.org/software/bash/manual/bash.html
- `grep`, `sed`, `tr`, `cut` manual pages: `man grep`, `man sed`, `man tr`, `man cut` in any terminal.
