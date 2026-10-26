# database-darpan

Database reconciliation source for Darpan. It adds a first-class database source (MySQL, PostgreSQL, IBM Db2) alongside the file, SFTP, and API sources. A run reads one side (or both sides) with a saved, validated SELECT, writes the rows to a staged JSON file, and hands that file to the normal reconciliation pipeline.

The component depends on `darpan` (`component.xml`). Core `darpan` never references it: the `DATABASE` connector row ships in this component's `data/DatabaseConnectorSeedData.xml`. If the component is absent, the `DATABASE` system is not dispatchable. The `darpan` connector seed carries the same row with `healthCheckServiceName` set to `probe#DatabaseConnection`.

## Entities (`entity/DatabaseConfigEntities.xml`)

| Entity | Holds |
|---|---|
| `darpan.database.DatabaseSourceConfig` | One connection: `dialect` (`MYSQL`, `POSTGRES`, `DB2`), host, optional port (dialect default 3306 / 5432 / 50000), database, username, encrypted `password`, `requireTls` (default `Y`), optional `caCertPem`, `connectTimeoutSeconds` (10), `queryTimeoutSeconds` (300), `maxRows` (500000), `isActive` |
| `darpan.database.DatabaseSourceQuery` | One extractable dataset: a `sqlSelect` against a config. Optional named placeholders `:windowStart` and `:windowEnd` |

Both rows carry `companyUserGroupId` and belong to one tenant. They are not shareable across tenants.

## Services

Facade (`service/facade/DatabaseFacadeServices.xml`):

| Service | Remote | Behaviour |
|---|---|---|
| `save#DatabaseSourceConfig` | yes | Create or update a connection in the active tenant. Tenant-admin only (the check is in the script). A blank password on update keeps the stored one |
| `list#DatabaseSourceConfigs` | yes | Connections in the active tenant. Credentials are never returned (explicit field allow-list) |
| `delete#DatabaseSourceConfig` | yes | Tenant-admin only. Refuses while any saved query uses the connection |
| `save#DatabaseSourceQuery` | yes | Create or update a query. Tenant-admin only. The SELECT is validated before it is stored |
| `list#DatabaseSourceQueries` | yes | Queries in the active tenant, optionally for one connection |
| `delete#DatabaseSourceQuery` | yes | Tenant-admin only |
| `preview#DatabaseQuery` | yes | Validate and run a SELECT, returning at most 10 rows (30 s timeout). Accepts a saved query id, or a connection id plus `sqlSelect` |
| `validate#DatabaseConnection` | no | Connect and run the dialect test query, from a saved config or transient fields (pre-save test) |
| `probe#DatabaseConnection` | no | Connection diagnostics, called only through `facade.SettingsFacadeServices.test#SourceConnection`. Returns check rows; never writes service errors |

Extraction (`service/reconciliation/DatabaseExtractionServices.xml`):

- `extract#DatabaseRecords` (internal) runs a saved query for a window and writes a staged JSON file. Same output contract as the OMS and Shopify extractors (`dataAvailable`, `fileLocation`, `fileName`, `recordCount`, `requestMetadata`, `warnings`, `errors`).
- The automation executor resolves it from the `DATABASE` connector row. The config id is `databaseSourceQueryId`, read from the automation source row, so each side can use a different query. Two OFBiz instances can be compared this way: same schema, a different SELECT per side.

The remote facade methods are not yet listed in `darpan/docs/api-contract` because the contract generator does not scan this component.

## Access rules

- Writes need tenant edit rights in the active tenant (`TenantAccessSupport.canEditActiveTenantData`).
- Reads by id go through `TenantAccessSupport.requireTenantRecordAccess`. A row from another tenant gets the same "not found" as a missing one, so the answer never says which (DAR-BE-135).
- A query and its connection must belong to the same tenant. A blank tenant on either side fails closed.
- When an automation runs the extract, the query must belong to the automation's tenant.

## Read-only fence

The SELECT check is defence in depth. The primary fence is the connection plus the database grant.

1. Deployment requirement: the Darpan database user has SELECT-only grants.
2. Every connection is opened with `setReadOnly(true)` and autocommit off. MySQL and PostgreSQL also run a session-level read-only statement. PostgreSQL adds `readOnlyMode=always`. Db2 relies on the grant plus `setReadOnly`.
3. `SqlSelectValidator` accepts a single `SELECT` (or `WITH … SELECT`). It rejects write and session keywords outside literals and comments, MySQL executable comments, unterminated literals or comments, and any placeholder other than `:windowStart` / `:windowEnd`.

## Limits

- The host is resolved when a config is saved and again when a connection is built. A host that resolves to loopback, link-local, private, wildcard, multicast, or the cloud metadata address is rejected, as is a host that does not resolve. The JVM property `database_source_allow_private_hosts=true` lifts this (used by the integration tests).
- `requireTls=Y` requires TLS (MySQL `sslMode=REQUIRED`, PostgreSQL `sslmode=require`, Db2 `sslConnection=true`). With `N`, MySQL and PostgreSQL prefer TLS but allow plain connections, and Db2 connects without TLS.
- MySQL reads stream with a cursor fetch; all dialects use a fetch size of 1000.
- `queryTimeoutSeconds` bounds each statement.
- Exceeding `maxRows` FAILS the extraction with zero rows. It never truncates. Narrow the window or raise `maxRows`.

## Drivers

The MySQL driver comes from `darpan/lib`. The PostgreSQL and Db2 (`jcc`) drivers are dependencies of this component and are copied into `lib/` by the build.

## Tests

- Unit tests use H2: `./gradlew :runtime:component:database-darpan:test` from `darpan-backend`.
- Real-database tests are env-gated and skip by default. See `docs/database-integration-testing.md`.
