import darpan.database.DatabaseExtractionSupport
import darpan.database.SqlSelectValidator
import darpan.facade.common.TenantAccessSupport

import java.sql.Connection
import java.sql.Timestamp

String sql = sqlSelect
Map config = null
if (databaseSourceQueryId) {
    def queryRow = ec.entity.find("darpan.database.DatabaseSourceQuery")
            .condition("databaseSourceQueryId", databaseSourceQueryId).disableAuthz().useCache(false).one()
    TenantAccessSupport.requireTenantRecordAccess(ec, queryRow,
            "Database source query ${databaseSourceQueryId} not found.",
            "Database source query ${databaseSourceQueryId} is not available in your active tenant.")
    if (!ec.message.hasError()) {
        sql = queryRow.sqlSelect
        def configRow = ec.entity.find("darpan.database.DatabaseSourceConfig")
                .condition("databaseSourceConfigId", queryRow.databaseSourceConfigId).disableAuthz().useCache(false).one()
        // Explicit tenant guard for this disableAuthz read: queryRow is already tenant-checked above,
        // but its databaseSourceConfigId FK is not itself tenant-scoped at the DB level, so a
        // same-tenant cross-check is required here too (mirrors extractDatabaseRecords.groovy's
        // config/query cross-tenant check — the branch flagged as most safety-critical there).
        String queryTenant = queryRow.companyUserGroupId?.toString()?.trim()
        String configTenant = configRow?.companyUserGroupId?.toString()?.trim()
        if (!configRow) {
            ec.message.addError("Database connection ${queryRow.databaseSourceConfigId} not found.")
        } else if (!queryTenant || !configTenant) {
            // Fail CLOSED on a null/blank tenant on EITHER side. companyUserGroupId is nullable on both
            // entities, so a bare equality below would read null==null as "same tenant" and let a
            // scope-less pair through unverified. Missing tenant scope is never a valid preview.
            ec.message.addError("Database source query or connection is missing tenant scope.")
        } else if (configTenant != queryTenant) {
            ec.message.addError("Database connection and query belong to different tenants.")
        } else {
            config = new HashMap(configRow)
        }
    }
} else if (databaseSourceConfigId) {
    if (!sqlSelect) {
        ec.message.addError("sqlSelect is required when previewing by connection.")
    } else {
        def configRow = ec.entity.find("darpan.database.DatabaseSourceConfig")
                .condition("databaseSourceConfigId", databaseSourceConfigId).disableAuthz().useCache(false).one()
        TenantAccessSupport.requireTenantRecordAccess(ec, configRow,
                "Database connection ${databaseSourceConfigId} not found.",
                "Database connection ${databaseSourceConfigId} is not available in your active tenant.")
        if (!ec.message.hasError()) config = new HashMap(configRow)
    }
} else {
    ec.message.addError("Provide databaseSourceQueryId or databaseSourceConfigId + sqlSelect.")
}
if (ec.message.hasError()) { error = ec.message.getErrors().join("; "); ec.message.clearErrors(); rows = []; columns = []; return }

List<String> validation = SqlSelectValidator.validate(sql)
if (validation) { error = validation.join(" "); rows = []; columns = []; return }

Timestamp end = windowEnd ?: new Timestamp(System.currentTimeMillis())
Timestamp start = windowStart ?: new Timestamp(end.time - 7L * 24 * 3600 * 1000)

Connection conn = null
try {
    // Same read-only fence as extraction (setReadOnly + setAutoCommit(false) + dialect
    // readOnlySessionStatements) — defense-in-depth applies equally to both read paths.
    conn = DatabaseExtractionSupport.openReadOnlyConnection(config)
    // capIsLimit=true: preview treats the 10-row cap as a silent LIMIT — return the first 10 rows
    // with no error, unlike the extraction path which fails closed instead of truncating.
    Map result = DatabaseExtractionSupport.extractRows(conn, sql, [windowStart: start, windowEnd: end], 10L, 30L, true)
    if (result.errors) {
        rows = []; columns = []; error = (result.errors as List).join(" ")
    } else {
        rows = result.rows
        columns = rows ? new ArrayList(rows[0].keySet()) : []
        error = null
    }
} catch (Exception e) {
    rows = []; columns = []; error = "${e.class.simpleName}: ${e.message}".toString()
} finally {
    if (conn != null) {
        try { conn.commit() } catch (Exception ignored) { }   // close out the read-only transaction (autoCommit is now false)
        try { conn.close() } catch (Exception ignored) { }
    }
}
