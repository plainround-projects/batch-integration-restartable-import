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
