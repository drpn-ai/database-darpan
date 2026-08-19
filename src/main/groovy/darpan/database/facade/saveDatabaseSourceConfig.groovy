import darpan.database.JdbcUrlSupport
import darpan.facade.common.TenantAccessSupport

import java.sql.Timestamp

ec.context.ok = false

if (!TenantAccessSupport.canEditActiveTenantData(ec)) {
    ec.message.addError("You do not have permission to change database sources in this tenant.")
    return
}
String tenantId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
if (!tenantId) { ec.message.addError("No active tenant."); return }

// Validate the address at save time so a blocked host is rejected here rather than surfacing later
// as a confusing extraction failure on a scheduled run.
try {
    JdbcUrlSupport.buildUrl([dialect: dialect, host: host, databaseName: databaseName, port: port])
} catch (IllegalArgumentException e) {
    ec.message.addError(e.message); return
}

Timestamp now = ec.user.nowTimestamp
Map values = [description: description, dialect: dialect, host: host, port: port,
              databaseName: databaseName, username: username, requireTls: requireTls,
              caCertPem: caCertPem, connectTimeoutSeconds: connectTimeoutSeconds,
              queryTimeoutSeconds: queryTimeoutSeconds, maxRows: maxRows,
              companyUserGroupId: tenantId, isActive: "Y", lastUpdatedDate: now]
// A blank password on update means "leave the stored credential alone". The UI never receives the
// password back, so echoing an empty field must not wipe it.
if (password) values.put("password", password)

if (databaseSourceConfigId) {
    def existing = ec.entity.find("darpan.database.DatabaseSourceConfig")
            .condition("databaseSourceConfigId", databaseSourceConfigId).disableAuthz().useCache(false).one()
    TenantAccessSupport.requireTenantRecordAccess(ec, existing,
            "Database source config ${databaseSourceConfigId} not found.",
            "Database source config ${databaseSourceConfigId} is not available in your active tenant.")
    if (ec.message.hasError()) return
    values.put("databaseSourceConfigId", databaseSourceConfigId)
    ec.service.sync().name("update#darpan.database.DatabaseSourceConfig").parameters(values).call()
    ec.context.databaseSourceConfigId = databaseSourceConfigId
} else {
    values.put("createdDate", now)
    Map created = ec.service.sync().name("create#darpan.database.DatabaseSourceConfig").parameters(values).call()
    ec.context.databaseSourceConfigId = created?.databaseSourceConfigId
}
ec.context.ok = !ec.message.hasError()
