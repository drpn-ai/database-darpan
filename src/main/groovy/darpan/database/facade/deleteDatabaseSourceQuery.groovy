import darpan.facade.common.TenantAccessSupport

ec.context.ok = false
if (!TenantAccessSupport.canEditActiveTenantData(ec)) {
    ec.message.addError("You do not have permission to change database sources in this tenant."); return
}
def existing = ec.entity.find("darpan.database.DatabaseSourceQuery")
        .condition("databaseSourceQueryId", databaseSourceQueryId).disableAuthz().useCache(false).one()
TenantAccessSupport.requireTenantRecordAccess(ec, existing,
        "Database source query ${databaseSourceQueryId} not found.",
        "Database source query ${databaseSourceQueryId} is not available in your active tenant.")
if (ec.message.hasError()) return
ec.service.sync().name("delete#darpan.database.DatabaseSourceQuery")
        .parameters([databaseSourceQueryId: databaseSourceQueryId]).call()
ec.context.ok = !ec.message.hasError()
