import darpan.database.JdbcUrlSupport
import darpan.facade.common.TenantAccessSupport

import java.sql.Connection
import java.sql.DriverManager

Map config
if (databaseSourceConfigId) {
    def row = ec.entity.find("darpan.database.DatabaseSourceConfig")
            .condition("databaseSourceConfigId", databaseSourceConfigId).disableAuthz().useCache(false).one()
    TenantAccessSupport.requireTenantRecordAccess(ec, row,
            "Database connection ${databaseSourceConfigId} not found.",
            "Database connection ${databaseSourceConfigId} is not available in your active tenant.")
    if (ec.message.hasError()) { ok = false; error = ec.message.getErrors().join("; "); ec.message.clearErrors(); return }
    config = new HashMap(row)
} else {
    config = [host: host, port: port, databaseName: databaseName, username: username,
              password: password, requireTls: requireTls ?: "Y", connectTimeoutSeconds: connectTimeoutSeconds ?: 10L]
}

Connection conn = null
try {
    conn = DriverManager.getConnection(JdbcUrlSupport.buildUrl(config), JdbcUrlSupport.connectionProperties(config))
    def st = conn.createStatement(); st.setQueryTimeout(10)
    st.executeQuery(JdbcUrlSupport.testQuery(JdbcUrlSupport.dialectOf(config))).close()
    serverVersion = conn.metaData.databaseProductVersion
    ok = true
    error = null
} catch (Exception e) {
    ok = false
    error = "${e.class.simpleName}: ${e.message}".toString()
} finally {
    if (conn != null) try { conn.close() } catch (Exception ignored) { }
}
