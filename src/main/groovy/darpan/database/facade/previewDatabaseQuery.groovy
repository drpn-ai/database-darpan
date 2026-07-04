import darpan.database.DatabaseExtractionSupport
import darpan.database.JdbcUrlSupport
import darpan.database.SqlSelectValidator
import darpan.facade.common.TenantAccessSupport

import java.sql.Connection
import java.sql.DriverManager
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
        if (!configRow) {
            ec.message.addError("Database connection ${queryRow.databaseSourceConfigId} not found.")
        } else if (configRow.companyUserGroupId?.toString()?.trim() != queryRow.companyUserGroupId?.toString()?.trim()) {
            ec.message.addError("Database connection and query belong to different tenants.")
        } else {
            config = new HashMap(configRow)
        }
    }
} else if (databaseSourceConfigId) {
    def configRow = ec.entity.find("darpan.database.DatabaseSourceConfig")
            .condition("databaseSourceConfigId", databaseSourceConfigId).disableAuthz().useCache(false).one()
    TenantAccessSupport.requireTenantRecordAccess(ec, configRow,
            "Database connection ${databaseSourceConfigId} not found.",
            "Database connection ${databaseSourceConfigId} is not available in your active tenant.")
    if (!ec.message.hasError()) config = new HashMap(configRow)
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
    conn = DriverManager.getConnection(JdbcUrlSupport.buildUrl(config), JdbcUrlSupport.connectionProperties(config))
    conn.setReadOnly(true)
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
    if (conn != null) try { conn.close() } catch (Exception ignored) { }
}
