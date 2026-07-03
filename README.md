# database-darpan

Database reconciliation source component for Darpan, adding a first-class database
source (MySQL, PostgreSQL, IBM Db2) alongside the existing file, SFTP, and API sources.
It ships tenant-scoped config entities, extract and facade services, and a
`SourceSystemConnector` registry seed row that dispatch a staged-file JDBC extractor
into the existing reconciliation pipeline. See the design spec in `drpn-ai/darpan`
docs: `docs/superpowers/specs/2026-07-03-database-darpan-db-source-integration-design.md`.
