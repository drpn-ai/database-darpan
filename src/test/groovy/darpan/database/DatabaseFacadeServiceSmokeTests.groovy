package darpan.database

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseFacadeServiceSmokeTests {
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "database-facade-smoke")
        // seedCompanyScope creates the KREWE tenant, logs in TEST_CUSTOMER_USER, and sets KREWE as the
        // active tenant — the state the interactive (no-companyUserGroupId) path reads through
        // TenantAccessSupport.requireTenantRecordAccess (same fixture as Task 8's smoke tests).
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)

        // DatabaseSourceConfig/Query.companyUserGroupId is a real FK (DBSRCCONF_COMPANY/DBSRCQRY_COMPANY)
        // to moqui.security.UserGroup, so every tenant id used below must exist as a UserGroup first.
        // KREWE is already created by seedCompanyScope. Fixture rows below reuse Task 8's fixtures verbatim.
        ["SMOKE_TENANT", "TENANT_A", "TENANT_B"].each { String tenantId ->
            ec.entity.makeValue("moqui.security.UserGroup")
                    .setAll([userGroupId: tenantId, description: tenantId]).create()
        }

        ec.entity.makeValue("darpan.database.DatabaseSourceConfig").setAll([
                databaseSourceConfigId: "SMOKE_CONF", companyUserGroupId: "SMOKE_TENANT",
                host: "unreachable.invalid", databaseName: "orders", username: "u", password: "p",
                connectTimeoutSeconds: 1L]).create()
        ec.entity.makeValue("darpan.database.DatabaseSourceQuery").setAll([
                databaseSourceQueryId: "SMOKE_QUERY", databaseSourceConfigId: "SMOKE_CONF",
                companyUserGroupId: "SMOKE_TENANT", sqlSelect: "SELECT 1 AS one"]).create()
        ec.entity.makeValue("darpan.database.DatabaseSourceQuery").setAll([
                databaseSourceQueryId: "SMOKE_BAD_QUERY", databaseSourceConfigId: "SMOKE_CONF",
                companyUserGroupId: "SMOKE_TENANT", sqlSelect: "DROP TABLE orders"]).create()

        // Cross-tenant fixture: config owned by TENANT_A, query owned by TENANT_B but pointing at
        // TENANT_A's config (item 2 in DatabaseExtractionServiceSmokeTests).
        ec.entity.makeValue("darpan.database.DatabaseSourceConfig").setAll([
                databaseSourceConfigId: "TENANT_A_CONF", companyUserGroupId: "TENANT_A",
                host: "unreachable.invalid", databaseName: "orders", username: "u", password: "p",
                connectTimeoutSeconds: 1L]).create()
        ec.entity.makeValue("darpan.database.DatabaseSourceQuery").setAll([
                databaseSourceQueryId: "TENANT_B_QUERY", databaseSourceConfigId: "TENANT_A_CONF",
                companyUserGroupId: "TENANT_B", sqlSelect: "SELECT 1 AS one"]).create()

        // Interactive-path fixture: KREWE-owned config + query the active tenant CAN see.
        ec.entity.makeValue("darpan.database.DatabaseSourceConfig").setAll([
                databaseSourceConfigId: "KREWE_CONF", companyUserGroupId: "KREWE",
                host: "unreachable.invalid", databaseName: "orders", username: "u", password: "p",
                connectTimeoutSeconds: 1L]).create()
        ec.entity.makeValue("darpan.database.DatabaseSourceQuery").setAll([
                databaseSourceQueryId: "KREWE_QUERY", databaseSourceConfigId: "KREWE_CONF",
                companyUserGroupId: "KREWE", sqlSelect: "SELECT 1 AS one"]).create()

        // Preview-specific cross-tenant fixture: a query OWNED BY the active tenant (KREWE), so its own
        // tenant fence passes, but its databaseSourceConfigId FK points at a config owned by TENANT_A.
        // Exercises the config/query same-tenant cross-check inside previewDatabaseQuery.groovy.
        ec.entity.makeValue("darpan.database.DatabaseSourceQuery").setAll([
                databaseSourceQueryId: "KREWE_QUERY_CROSS_TENANT_CONF", databaseSourceConfigId: "TENANT_A_CONF",
                companyUserGroupId: "KREWE", sqlSelect: "SELECT 1 AS one"]).create()
    }

    @BeforeEach
    void resetMessages() {
        // Test-only isolation: same rationale as DatabaseExtractionServiceSmokeTests — clear any error
        // left on the shared ExecutionContext between @Test methods.
        ec.message.clearErrors()
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @Test
    void validateConnectionTransientUnreachableHostReturnsOkFalseWithoutThrowing() {
        Map out = ec.service.sync().name("facade.DatabaseFacadeServices.validate#DatabaseConnection")
                .parameters([host: "unreachable.invalid", port: 3306L, databaseName: "orders",
                             username: "u", password: "p", connectTimeoutSeconds: 1L])
                .disableAuthz().call()
        assertEquals(false, out.ok)
        assertNotNull(out.error)
        assertFalse((out.error as String).isEmpty())
    }

    @Test
    void validateConnectionWithConfigInAnotherTenantErrorsMentioningTenant() {
        // Active tenant is KREWE (seedCompanyScope); TENANT_A_CONF is owned by TENANT_A.
        Map out = ec.service.sync().name("facade.DatabaseFacadeServices.validate#DatabaseConnection")
                .parameters([databaseSourceConfigId: "TENANT_A_CONF"])
                .disableAuthz().call()
        assertEquals(false, out.ok)
        assertNotNull(out.error)
        assertTrue((out.error as String).toLowerCase().contains("tenant"),
                "expected tenant-scoped rejection, got: ${out.error}")
    }

    @Test
    void previewQueryValidatorRejectionReturnsErrorWithoutConnecting() {
        // KREWE_CONF is owned by the active tenant (KREWE) so the tenant fence passes and the SQL
        // validator runs; it also points at an unreachable host, so if the script connected before
        // validating SQL this would surface a connection error instead of the validator's rejection.
        Map out = ec.service.sync().name("facade.DatabaseFacadeServices.preview#DatabaseQuery")
                .parameters([databaseSourceConfigId: "KREWE_CONF", sqlSelect: "DELETE FROM x"])
                .disableAuthz().call()
        assertNotNull(out.error)
        assertTrue((out.error as String).contains("DELETE"),
                "expected validator rejection mentioning the denied keyword, got: ${out.error}")
        assertTrue((out.rows as List).isEmpty())
    }

    @Test
    void previewQueryRejectsQueryWhoseConfigBelongsToADifferentTenant() {
        // KREWE_QUERY_CROSS_TENANT_CONF's own tenant check passes (owned by the active tenant, KREWE)
        // but its config (TENANT_A_CONF) is owned by TENANT_A — the config/query cross-check must catch
        // this even though the top-level query lookup alone would not.
        Map out = ec.service.sync().name("facade.DatabaseFacadeServices.preview#DatabaseQuery")
                .parameters([databaseSourceQueryId: "KREWE_QUERY_CROSS_TENANT_CONF"])
                .disableAuthz().call()
        assertNotNull(out.error)
        assertTrue((out.error as String).toLowerCase().contains("different tenants"),
                "expected cross-tenant rejection, got: ${out.error}")
        assertTrue((out.rows as List).isEmpty())
    }
}
