import darpan.facade.common.TenantAccessSupport

String tenantId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
List queries = []
if (tenantId) {
    def finder = ec.entity.find("darpan.database.DatabaseSourceQuery")
            .condition("companyUserGroupId", tenantId).disableAuthz().useCache(false)
    if (databaseSourceConfigId) finder = finder.condition("databaseSourceConfigId", databaseSourceConfigId)
    finder.list()?.each { row ->
        queries << [databaseSourceQueryId: row.databaseSourceQueryId,
                    databaseSourceConfigId: row.databaseSourceConfigId,
                    description: row.description, sqlSelect: row.sqlSelect, isActive: row.isActive]
    }
}
ec.context.queries = queries
