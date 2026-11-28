import darpan.database.DatabaseExtractionSupport
import darpan.database.SqlSelectValidator
import darpan.facade.common.TenantAccessSupport

import java.sql.Connection
import java.sql.Timestamp

// OC200: a preview runs a SELECT the caller writes against the tenant's database with the stored credentials, so it takes
// run access, as the saves do; a view-only member is refused before any read.
if (!TenantAccessSupport.canRunActiveTenantReconciliation(ec)) {
    error = "You do not have permission to run database queries in this tenant."; rows = []; columns = []; return
}

String sql = sqlSelect
Map config = null
if (databaseSourceQueryId) {
    def queryRow = ec.entity.find("darpan.database.DatabaseSourceQuery")
            .condition("databaseSourceQueryId", databaseSourceQueryId).disableAuthz().useCache(false).one()
    TenantAccessSupport.requireTenantRecordAccess(ec, queryRow,
            "Database source query ${databaseSourceQueryId} not found.")
    if (!ec.message.hasError()) {
        sql = queryRow.sqlSelect
        def configRow = ec.entity.find("darpan.database.DatabaseSourceConfig")
                .condition("databaseSourceConfigId", queryRow.databaseSourceConfigId).disableAuthz().useCache(false).one()
        // The query row is tenant-checked above; its databaseSourceConfigId is a plain FK, so the pair is checked too.
        String mismatch = DatabaseExtractionSupport.queryConfigMismatch(queryRow, configRow)
        if (mismatch) ec.message.addError(mismatch)
        else config = new HashMap(configRow)
    }
} else if (databaseSourceConfigId) {
    if (!sqlSelect) {
        ec.message.addError("sqlSelect is required when previewing by connection.")
    } else {
        def configRow = ec.entity.find("darpan.database.DatabaseSourceConfig")
                .condition("databaseSourceConfigId", databaseSourceConfigId).disableAuthz().useCache(false).one()
        TenantAccessSupport.requireTenantRecordAccess(ec, configRow,
                "Database connection ${databaseSourceConfigId} not found.")
        if (!ec.message.hasError()) config = new HashMap(configRow)
    }
} else {
    ec.message.addError("Provide databaseSourceQueryId or databaseSourceConfigId + sqlSelect.")
}
if (ec.message.hasError()) { error = ec.message.getErrors().join("; "); ec.message.clearErrors(); rows = []; columns = []; return }

List<String> validation = SqlSelectValidator.validate(sql)
if (validation) { error = validation.join(" "); rows = []; columns = []; return }

Timestamp end = windowEnd ?: ec.user.nowTimestamp
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
