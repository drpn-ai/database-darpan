import darpan.database.JdbcUrlSupport
import darpan.facade.common.TenantAccessSupport

import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

// Diagnostics only. Deliberately never calls ec.message.addError: test#SourceConnection renders
// these rows into the connection-diagnostics popup, and a rejected credential is a normal result
// there, not a service failure.
List checks = []
boolean ok = false

def configRow = ec.entity.find("darpan.database.DatabaseSourceConfig")
        .condition("databaseSourceConfigId", databaseSourceConfigId).disableAuthz().useCache(false).one()
TenantAccessSupport.requireTenantRecordAccess(ec, configRow,
        "Database source config ${databaseSourceConfigId} not found.",
        "Database source config ${databaseSourceConfigId} is not available in your active tenant.")

if (!ec.message.hasError() && configRow != null) {
    Map config = configRow.getMap()
    boolean allowPrivateHosts = "true".equalsIgnoreCase(System.getProperty("database_source_allow_private_hosts") ?: "false")

    String url = null
    try {
        url = JdbcUrlSupport.buildUrl(config, allowPrivateHosts)
        checks << [name: "Address", status: "PASS", detail: url]
    } catch (IllegalArgumentException e) {
        checks << [name: "Address", status: "FAIL", detail: e.message]
    }

    if (url) {
        Connection conn = null
        Statement stmt = null
        try {
            conn = DriverManager.getConnection(url, JdbcUrlSupport.connectionProperties(config))
            checks << [name: "Connection", status: "PASS", detail: "Connected."]
            stmt = conn.createStatement()
            boolean readable = stmt.execute("SELECT 1")
            checks << [name: "Read access", status: readable ? "PASS" : "FAIL", detail: "SELECT 1"]
            ok = readable
        } catch (Exception e) {
            checks << [name: "Connection", status: "FAIL", detail: e.message]
        } finally {
            try { stmt?.close() } catch (Exception ignored) {}
            try { conn?.close() } catch (Exception ignored) {}
        }
    }
}

ec.context.ok = ok
ec.context.checks = checks
