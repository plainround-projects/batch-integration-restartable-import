# make up | db | generate | run | crash-test | test | lint | down   (make help lists all)
MVN := ./mvnw -q -B
JAR := target/restartable-import-0.1.0.jar
ROWS ?= 200000
BAD_EVERY ?= 2000
ROUNDS ?= 5

POSTGRES_USER ?= catalog
POSTGRES_DB ?= catalog

-include .env
export

PSQL := docker compose exec -T postgres psql -U $(POSTGRES_USER) -d $(POSTGRES_DB) -v ON_ERROR_STOP=1 -q
APP := java -jar $(JAR)

.PHONY: help env up db reset build generate run run-twice run-new-id too-many-bad retry-demo summary queries crash-test \
        health lint format test test-integration test-e2e audit check docker-build down clean

help: ## list the targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | sed -E 's/:.*## /\t/' | sort

env:
	@[ -f .env ] || { cp .env.example .env; echo "created .env from .env.example"; }

up: env ## start PostgreSQL
	docker compose up -d --wait

db: ## apply sql/001 (job repository), 002 (product) and the migrations (reject record)
	$(PSQL) < sql/001_spring_batch_schema.sql
	$(PSQL) < sql/002_catalog_schema.sql
	@for file in sql/migrations/*.sql; do echo "applying $$file"; $(PSQL) < $$file; done

reset: ## forget every product, reject and run
	$(PSQL) < sql/reset.sql

$(JAR): pom.xml $(shell find src/main -type f)
	$(MVN) -DskipTests package

build: $(JAR) ## build the jar

generate: build ## write data/products.csv: ROWS rows, a bad row every BAD_EVERY rows
	mkdir -p data
	$(APP) --command=generate --out=data/products.csv --rows=$(ROWS) --bad-every=$(BAD_EVERY)

# The happy path: a clean import of the whole file, then the job repository and the end state.
run: build reset generate ## import data/products.csv (200,000 rows, 100 bad): 199,900 products, 100 rejects
	$(APP) --command=import --import.file=data/products.csv --run.id=run-1
	@echo "rejects.txt: $$(wc -l < rejects.txt) lines; first two:"; head -2 rejects.txt
	$(APP) --command=summary

run-new-id: build ## the same file again under a new run.id: still one row per SKU (step 4)
	$(APP) --command=import --import.file=data/products.csv --run.id=run-$$(date +%s)
	$(APP) --command=summary

run-twice: build ## the same file under the SAME run.id: refused as already complete
	-$(APP) --command=import --import.file=data/products.csv --run.id=run-1

too-many-bad: build reset ## a file with 600 bad rows fails the step: Skip limit of '500' exceeded
	mkdir -p data
	$(APP) --command=generate --out=data/too-many-bad.csv --rows=20000 --bad-every=33
	-$(APP) --command=import --import.file=data/too-many-bad.csv --run.id=too-many-1
	$(APP) --command=summary

retry-demo: build reset generate ## step 6: every fiftieth chunk fails once; the job completes with skip_count 100
	IMPORT_TRANSIENT_FAULT_EVERY_NTH_CHUNK=50 $(APP) --command=import --import.file=data/products.csv --run.id=retry-1
	$(APP) --command=summary

summary: build ## the job repository and the product count
	$(APP) --command=summary

queries: ## run sql/003_queries.sql
	$(PSQL) < sql/003_queries.sql

crash-test: build generate ## step 5: ROUNDS times kill -9 the import at a random point, restart it, check the table
	sh scripts/crash-test.sh $(ROUNDS)

health: build ## exit 0 when the database is reachable and has the product table
	$(APP) --command=health

lint: ## spotless (google-java-format) check and the compiler's -Xlint:all -Werror
	$(MVN) spotless:check compile

format: ## apply google-java-format
	$(MVN) spotless:apply

test: ## unit and architecture tests with coverage (no services needed); the domain and application stay at 95%
	$(MVN) verify -DskipITs

test-integration: ## the job against the running PostgreSQL (make up and make db first)
	$(MVN) test-compile failsafe:integration-test failsafe:verify -Dfailsafe.groups=integration

test-e2e: ## the lesson's 200,000-row scenario (make up and make db first)
	$(MVN) test-compile failsafe:integration-test failsafe:verify -Dfailsafe.groups=e2e

audit: ## check the dependencies against the OSS Index vulnerability database
	$(MVN) org.sonatype.ossindex.maven:ossindex-maven-plugin:audit

check: lint test ## everything that needs no services

docker-build: ## build the container image
	docker build -t restartable-import:dev .

down: ## stop PostgreSQL and delete its data
	docker compose down -v

clean: down
	rm -rf target data logs rejects.txt
