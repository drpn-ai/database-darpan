# Real-database integration testing

`H2` proves `DatabaseExtractionSupport` and `JdbcUrlSupport` unit-level, but it cannot prove several
things that only a real driver + real server can:

- MySQL/Postgres session-level read-only statements (`SET SESSION TRANSACTION READ ONLY`, `SET SESSION
  CHARACTERISTICS AS TRANSACTION READ ONLY`) actually block a write on that vendor's engine.
- MySQL Connector/J's `useCursorFetch` driver property is honored (H2 has no equivalent cursor mode).
- pgjdbc's `readOnlyMode=always` property combined with `Connection.setReadOnly(true)` actually fences
  writes (H2 does not implement pgjdbc's property surface at all).
- Real driver property handling/parsing per dialect (Connector/J vs pgjdbc vs Db2 jcc all parse and
  validate connection properties differently; H2 accepts a much looser set).

To close that gap this component ships three **env-gated** JUnit 5 test classes under
`src/test/groovy/darpan/database/`:

- `MySqlIntegrationTests` — gated on `DARPAN_MYSQL_TEST_HOST`
- `PostgresIntegrationTests` — gated on `DARPAN_PG_TEST_HOST`
- `Db2IntegrationTests` — gated on `DARPAN_DB2_TEST_HOST`

Each class is annotated `@EnabledIfEnvironmentVariable(named = "...", matches = ".+")`. When the
matching env var is unset (the default for `./gradlew test` and CI), JUnit reports the whole class as
**skipped**, not failed, and nothing in it ever opens a socket. This keeps the component's default test
task hermetic — no external dependency, no network call, no flakiness — while still giving engineers
and a future CI job a real, live proof path against each dialect.

## Running each dialect locally

All commands assume you are at the repo root running `cd darpan-backend` first, and that Docker is
available locally. Always stop the container afterwards (`docker stop <name>`; the `--rm` flag deletes
it on stop, so no separate `docker rm` is needed for MySQL/Postgres).

### MySQL

```bash
docker run --rm -d --name darpan-mysql-test -e MYSQL_ROOT_PASSWORD=test -e MYSQL_DATABASE=recon_test -p 13306:3306 mysql:8
sleep 25   # mysql:8 image needs ~20s to initialize
DARPAN_MYSQL_TEST_HOST=127.0.0.1 ./gradlew :runtime:component:database-darpan:test --tests darpan.database.MySqlIntegrationTests
docker stop darpan-mysql-test
```

Env vars: `DARPAN_MYSQL_TEST_HOST` (required), `DARPAN_MYSQL_TEST_PORT` (default `13306`),
`DARPAN_MYSQL_TEST_PASSWORD` (default `test`, matches the container's `MYSQL_ROOT_PASSWORD`).

`readOnlySessionBlocksWrites` is the load-bearing test here: it opens the connection exactly the way
`runExtraction` does (`setReadOnly(true)`, `SET SESSION TRANSACTION READ ONLY`, `setAutoCommit(false)`)
and asserts a subsequent `INSERT` throws. If it ever stops throwing, that is a real regression in the
read-only fence, not a test to relax.

### Postgres

```bash
docker run --rm -d --name darpan-pg-test -e POSTGRES_PASSWORD=test -e POSTGRES_DB=recon_test -p 15432:5432 postgres:16
sleep 5
DARPAN_PG_TEST_HOST=127.0.0.1 ./gradlew :runtime:component:database-darpan:test --tests darpan.database.PostgresIntegrationTests
docker stop darpan-pg-test
```

Env vars: `DARPAN_PG_TEST_HOST` (required), `DARPAN_PG_TEST_PORT` (default `15432`),
`DARPAN_PG_TEST_PASSWORD` (default `test`, matches `POSTGRES_PASSWORD`).

### Db2

```bash
docker run --rm -d --name darpan-db2-test --privileged -e LICENSE=accept \
  -e DB2INST1_PASSWORD=test -e DBNAME=RECONTST -p 50000:50000 icr.io/db2_community/db2
# Db2 needs several minutes to initialize; wait for "Setup has completed":
until docker logs darpan-db2-test 2>&1 | grep -q "Setup has completed"; do sleep 15; done
DARPAN_DB2_TEST_HOST=127.0.0.1 ./gradlew :runtime:component:database-darpan:test --tests darpan.database.Db2IntegrationTests
docker stop darpan-db2-test
```

Env vars: `DARPAN_DB2_TEST_HOST` (required), `DARPAN_DB2_TEST_PORT` (default `50000`),
`DARPAN_DB2_TEST_PASSWORD` (default `test`, matches `DB2INST1_PASSWORD`).

**Db2 caveats:**

- The community image is ~3GB and requires `--privileged` (it manages its own kernel resources inside
  the container); it will not run on hosts that disallow privileged containers.
- Cold init commonly takes several minutes (watch for `"Setup has completed"` in `docker logs`) — do not
  assume a fast health check.
- Db2 database names are limited to 8 characters, hence `RECONTST` rather than something longer/more
  descriptive.
- Db2 LUW has **no session-level read-only transaction statement** (unlike MySQL/Postgres). Its
  read-only fence is `Connection.setReadOnly(true)` plus the documented SELECT-only grant on the
  extraction user in the target database — there is nothing session-level for
  `JdbcUrlSupport.readOnlySessionStatements("DB2")` to return (`[]` is the correct, validated result, not
  a gap). `Db2IntegrationTests` therefore only proves the extraction path end-to-end; it does not carry
  a `readOnlySessionBlocksWrites` test, because there is no session statement to prove.

## CI follow-up

None of these three classes run in the default `./gradlew test` / current CI job — they are gated
specifically so CI stays fast and hermetic. Wiring them into CI as per-dialect service containers
(MySQL and Postgres are small/fast enough for a per-PR job; Db2's size and `--privileged` requirement
make it a better fit for a nightly/scheduled job rather than per-PR) is a follow-up for this component's
CI setup, not part of this change.
