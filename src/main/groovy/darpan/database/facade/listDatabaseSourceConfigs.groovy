import darpan.facade.common.TenantAccessSupport

String tenantId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
List configs = []
if (tenantId) {
    ec.entity.find("darpan.database.DatabaseSourceConfig")
            .condition("companyUserGroupId", tenantId).disableAuthz().useCache(false).list()?.each { row ->
        // Explicit allowlist rather than the whole row: the credential must never leave the server,
        // and an allowlist stays correct when new columns are added to the entity.
        configs << [databaseSourceConfigId: row.databaseSourceConfigId, description: row.description,
                    dialect: row.dialect, host: row.host, port: row.port,
                    databaseName: row.databaseName, username: row.username,
                    requireTls: row.requireTls, isActive: row.isActive]
    }
}
ec.context.configs = configs
