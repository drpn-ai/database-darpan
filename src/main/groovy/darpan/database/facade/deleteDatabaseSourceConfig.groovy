import darpan.facade.common.TenantAccessSupport

ec.context.ok = false
if (!TenantAccessSupport.canEditActiveTenantData(ec)) {
    ec.message.addError("You do not have permission to change database sources in this tenant."); return
}
def existing = ec.entity.find("darpan.database.DatabaseSourceConfig")
        .condition("databaseSourceConfigId", databaseSourceConfigId).disableAuthz().useCache(false).one()
TenantAccessSupport.requireTenantRecordAccess(ec, existing,
        "Database source config ${databaseSourceConfigId} not found.",
        "Database source config ${databaseSourceConfigId} is not available in your active tenant.")
if (ec.message.hasError()) return

long referencing = ec.entity.find("darpan.database.DatabaseSourceQuery")
        .condition("databaseSourceConfigId", databaseSourceConfigId).disableAuthz().count()
if (referencing > 0) {
    ec.message.addError("Cannot delete: ${referencing} saved quer${referencing == 1 ? 'y' : 'ies'} still use this connection.")
    return
}
ec.service.sync().name("delete#darpan.database.DatabaseSourceConfig")
        .parameters([databaseSourceConfigId: databaseSourceConfigId]).call()
ec.context.ok = !ec.message.hasError()
