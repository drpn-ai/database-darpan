import darpan.database.DatabaseExtractionSupport
import darpan.facade.common.TenantAccessSupport

import java.sql.DriverManager

// Diagnostics only. Deliberately never calls ec.message.addError: test#SourceConnection renders
// these rows into the connection-diagnostics popup, and a rejected credential is a normal result
// there, not a service failure.
List checks = []
boolean ok = false

def configRow = ec.entity.find("darpan.database.DatabaseSourceConfig")
        .condition("databaseSourceConfigId", databaseSourceConfigId).disableAuthz().useCache(false).one()
TenantAccessSupport.requireTenantRecordAccess(ec, configRow,
        "Database source config ${databaseSourceConfigId} not found.")

if (!ec.message.hasError() && configRow != null) {
    Map probe = DatabaseExtractionSupport.probeChecks(configRow.getMap()) { String url, Properties properties ->
        DriverManager.getConnection(url, properties)
    }
    ok = probe.ok == true
    checks = probe.checks as List
}

ec.context.ok = ok
ec.context.checks = checks
