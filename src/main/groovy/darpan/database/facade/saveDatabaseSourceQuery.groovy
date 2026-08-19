import darpan.database.SqlSelectValidator
import darpan.facade.common.TenantAccessSupport

ec.context.ok = false
if (!TenantAccessSupport.canEditActiveTenantData(ec)) {
    ec.message.addError("You do not have permission to change database sources in this tenant."); return
}
String tenantId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
if (!tenantId) { ec.message.addError("No active tenant."); return }

// The parent config must belong to this tenant: databaseSourceConfigId is a plain FK with no
// DB-level tenant scoping, so without this cross-check a tenant could attach a query to another
// tenant's connection. Mirrors the guard in extractDatabaseRecords.groovy.
def configRow = ec.entity.find("darpan.database.DatabaseSourceConfig")
        .condition("databaseSourceConfigId", databaseSourceConfigId).disableAuthz().useCache(false).one()
TenantAccessSupport.requireTenantRecordAccess(ec, configRow,
        "Database source config ${databaseSourceConfigId} not found.",
        "Database source config ${databaseSourceConfigId} is not available in your active tenant.")
if (ec.message.hasError()) return

List errors = SqlSelectValidator.validate(sqlSelect)
if (errors) { errors.each { ec.message.addError(it.toString()) }; return }

Map values = [databaseSourceConfigId: databaseSourceConfigId, description: description,
              sqlSelect: sqlSelect, companyUserGroupId: tenantId, isActive: "Y"]

if (databaseSourceQueryId) {
    def existing = ec.entity.find("darpan.database.DatabaseSourceQuery")
            .condition("databaseSourceQueryId", databaseSourceQueryId).disableAuthz().useCache(false).one()
    TenantAccessSupport.requireTenantRecordAccess(ec, existing,
            "Database source query ${databaseSourceQueryId} not found.",
            "Database source query ${databaseSourceQueryId} is not available in your active tenant.")
    if (ec.message.hasError()) return
    values.put("databaseSourceQueryId", databaseSourceQueryId)
    ec.service.sync().name("update#darpan.database.DatabaseSourceQuery").parameters(values).call()
    ec.context.databaseSourceQueryId = databaseSourceQueryId
} else {
    Map created = ec.service.sync().name("create#darpan.database.DatabaseSourceQuery").parameters(values).call()
    ec.context.databaseSourceQueryId = created?.databaseSourceQueryId
}
ec.context.ok = !ec.message.hasError()
