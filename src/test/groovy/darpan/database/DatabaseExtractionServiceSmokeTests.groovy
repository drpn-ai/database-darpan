package darpan.database

import darpan.reconciliation.automation.SourceSystemConnectorSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseExtractionServiceSmokeTests {
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "database-extraction-smoke")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://database-darpan/data/DatabaseConnectorSeedData.xml")
        // seedCompanyScope creates the KREWE tenant, logs in TEST_CUSTOMER_USER, and sets KREWE as the
        // active tenant — the state the interactive (no-companyUserGroupId) path reads through
        // TenantAccessSupport.requireTenantRecordAccess. Automation-path tests below pass an explicit
        // companyUserGroupId and are unaffected by the active tenant.
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)

        // DatabaseSourceConfig/Query.companyUserGroupId is a real FK (DBSRCCONF_COMPANY/DBSRCQRY_COMPANY)
        // to moqui.security.UserGroup, so every tenant id used below must exist as a UserGroup first
        // (mirrors HotWaxOmsRestSourceConfigFacadeSmokeTests#seedTenant for the same FK shape). KREWE is
        // already created by seedCompanyScope.
        ["SMOKE_TENANT", "TENANT_A", "TENANT_B"].each { String tenantId ->
            ec.entity.makeValue("moqui.security.UserGroup")
                    .setAll([userGroupId: tenantId, description: tenantId]).create()
        }

        // Automation-path fixtures (explicit companyUserGroupId).
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

        // Cross-tenant fixture (item 2): config owned by TENANT_A, query owned by TENANT_B but pointing
        // at TENANT_A's config. The safety-critical branch is the config/query same-tenant cross-check.
        ec.entity.makeValue("darpan.database.DatabaseSourceConfig").setAll([
                databaseSourceConfigId: "TENANT_A_CONF", companyUserGroupId: "TENANT_A",
                host: "unreachable.invalid", databaseName: "orders", username: "u", password: "p",
                connectTimeoutSeconds: 1L]).create()
        ec.entity.makeValue("darpan.database.DatabaseSourceQuery").setAll([
                databaseSourceQueryId: "TENANT_B_QUERY", databaseSourceConfigId: "TENANT_A_CONF",
                companyUserGroupId: "TENANT_B", sqlSelect: "SELECT 1 AS one"]).create()

        // Interactive-path fixture (item 3): KREWE-owned config + query the active tenant CAN see.
        ec.entity.makeValue("darpan.database.DatabaseSourceConfig").setAll([
                databaseSourceConfigId: "KREWE_CONF", companyUserGroupId: "KREWE",
                host: "unreachable.invalid", databaseName: "orders", username: "u", password: "p",
                connectTimeoutSeconds: 1L]).create()
        ec.entity.makeValue("darpan.database.DatabaseSourceQuery").setAll([
                databaseSourceQueryId: "KREWE_QUERY", databaseSourceConfigId: "KREWE_CONF",
                companyUserGroupId: "KREWE", sqlSelect: "SELECT 1 AS one"]).create()

        // Null-tenant-scope fixtures (defense-in-depth: companyUserGroupId is nullable on BOTH entities,
        // so the config/query cross-check must fail CLOSED when either side has no tenant scope — a
        // null==null equality must never read as "same tenant"). Mirrors the Task 9 preview fixtures.
        //   NULL_TENANT_CONF   — config with no tenant.
        //   NULL_TENANT_QUERY  — query with no tenant, pointing at NULL_TENANT_CONF (the both-null case;
        //                        on the interactive path requireTenantRecordAccess rejects it upstream).
        //   KREWE_QUERY_NULL_CONF — query owned by the active tenant (its own fence passes) pointing at
        //                        the null-tenant config: the REACHABLE config-null case that actually
        //                        exercises the new "missing tenant scope" guard.
        ec.entity.makeValue("darpan.database.DatabaseSourceConfig").setAll([
                databaseSourceConfigId: "NULL_TENANT_CONF",
                host: "unreachable.invalid", databaseName: "orders", username: "u", password: "p",
                connectTimeoutSeconds: 1L]).create()
        ec.entity.makeValue("darpan.database.DatabaseSourceQuery").setAll([
                databaseSourceQueryId: "NULL_TENANT_QUERY", databaseSourceConfigId: "NULL_TENANT_CONF",
                sqlSelect: "SELECT 1 AS one"]).create()
        ec.entity.makeValue("darpan.database.DatabaseSourceQuery").setAll([
                databaseSourceQueryId: "KREWE_QUERY_NULL_CONF", databaseSourceConfigId: "NULL_TENANT_CONF",
                companyUserGroupId: "KREWE", sqlSelect: "SELECT 1 AS one"]).create()
    }

    @BeforeEach
    void resetMessages() {
        // Test-only isolation: the production script mirrors extractOmsOrders.groovy and does NOT clear
        // ec.message on failure, so a tenant/validation error left on this shared ExecutionContext would
        // block the next service call (ServiceCallSyncImpl refuses to run a service while
        // ec.message.hasError()). Clearing between @Test methods is the correct place for this — it is
        // test-harness infrastructure, never something the production script should do.
        ec.message.clearErrors()
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @Test
    void registryRowResolvesDatabaseConnector() {
        Map c = SourceSystemConnectorSupport.resolve(ec, "DATABASE")
        assertNotNull(c, "DATABASE connector row should resolve")
        assertEquals("reconciliation.DatabaseExtractionServices.extract#DatabaseRecords", c.extractServiceName)
        assertEquals("windowStart", c.dateFromParameterName)
        assertEquals("windowEnd", c.dateToParameterName)
        assertEquals("DATABASE_QUERY", c.expectedSourceConfigType)
        assertEquals("databaseSourceQueryId", c.configParameterName)
        assertEquals("darpan.database.DatabaseSourceQuery", c.configEntityName)
    }

    @Test
    void validatorRejectionFailsCleanlyWithoutFile() {
        Map out = callExtract("SMOKE_BAD_QUERY")
        assertEquals(false, out.dataAvailable)
        assertFalse((out.errors as List).isEmpty())
        assertNull(out.fileLocation)
    }

    @Test
    void unreachableHostFailsCleanlyWithoutFile() {
        Map out = callExtract("SMOKE_QUERY")
        assertEquals(false, out.dataAvailable)
        assertFalse((out.errors as List).isEmpty())
        assertNull(out.fileLocation)
    }

    @Test
    void wrongTenantIsRejected() {
        Map out = ec.service.sync().name("reconciliation.DatabaseExtractionServices.extract#DatabaseRecords")
                .parameters([databaseSourceQueryId: "SMOKE_QUERY", companyUserGroupId: "OTHER_TENANT",
                             windowStart: Timestamp.valueOf("2026-07-01 00:00:00"),
                             windowEnd: Timestamp.valueOf("2026-07-02 00:00:00")])
                .disableAuthz().call()
        assertEquals(false, out.dataAvailable)
        assertTrue((out.errors as List).join(" ").toLowerCase().contains("tenant"))
    }

    @Test
    void configAndQueryInDifferentTenantsRejected() {
        // Query (TENANT_B) resolves a config owned by TENANT_A. Passing companyUserGroupId=TENANT_B
        // clears the query's own tenant check, so this exercises specifically the config/query
        // same-tenant cross-check — the branch the brief flagged as most safety-critical.
        Map out = ec.service.sync().name("reconciliation.DatabaseExtractionServices.extract#DatabaseRecords")
                .parameters([databaseSourceQueryId: "TENANT_B_QUERY", companyUserGroupId: "TENANT_B",
                             windowStart: Timestamp.valueOf("2026-07-01 00:00:00"),
                             windowEnd: Timestamp.valueOf("2026-07-02 00:00:00")])
                .disableAuthz().call()
        assertEquals(false, out.dataAvailable)
        assertNull(out.fileLocation)
        assertTrue((out.errors as List).join(" ").toLowerCase().contains("different tenants"),
                "expected cross-tenant rejection, got: ${out.errors}")
    }

    @Test
    void interactivePathRejectsQueryOutsideActiveTenant() {
        // No companyUserGroupId => interactive path routes through requireTenantRecordAccess. Active
        // tenant is KREWE (seedCompanyScope); SMOKE_QUERY is owned by SMOKE_TENANT, so it must be rejected.
        Map out = ec.service.sync().name("reconciliation.DatabaseExtractionServices.extract#DatabaseRecords")
                .parameters([databaseSourceQueryId: "SMOKE_QUERY",
                             windowStart: Timestamp.valueOf("2026-07-01 00:00:00"),
                             windowEnd: Timestamp.valueOf("2026-07-02 00:00:00")])
                .disableAuthz().call()
        assertEquals(false, out.dataAvailable)
        assertNull(out.fileLocation)
        assertTrue((out.errors as List).join(" ").toLowerCase().contains("active tenant"),
                "expected active-tenant rejection, got: ${out.errors}")
    }

    @Test
    void interactivePathAcceptsQueryInActiveTenant() {
        // No companyUserGroupId => interactive path. KREWE_QUERY is owned by KREWE (the active tenant),
        // so the tenant fence must PASS; the run then fails downstream at the unreachable host. Proves the
        // interactive branch accepts an in-tenant query rather than rejecting everything.
        Map out = ec.service.sync().name("reconciliation.DatabaseExtractionServices.extract#DatabaseRecords")
                .parameters([databaseSourceQueryId: "KREWE_QUERY",
                             windowStart: Timestamp.valueOf("2026-07-01 00:00:00"),
                             windowEnd: Timestamp.valueOf("2026-07-02 00:00:00")])
                .disableAuthz().call()
        assertEquals(false, out.dataAvailable)
        assertNull(out.fileLocation)
        String joined = (out.errors as List).join(" ").toLowerCase()
        assertFalse(joined.contains("active tenant"),
                "tenant fence should have passed for an in-tenant query, got: ${out.errors}")
        assertFalse((out.errors as List).isEmpty(), "expected a downstream extraction error")
    }

    @Test
    void crossCheckFailsClosedWhenConfigHasNoTenantScope() {
        // KREWE_QUERY_NULL_CONF is owned by the active tenant (KREWE) so its own tenant fence passes, but
        // its config (NULL_TENANT_CONF) has a null companyUserGroupId. This is the REACHABLE config-null
        // case: the config/query cross-check must fail CLOSED on the missing tenant scope rather than let
        // the equality slip. Before the fix a null config tenant produced a generic "different tenants"
        // message; the guard now names the real problem. No file is written.
        Map out = ec.service.sync().name("reconciliation.DatabaseExtractionServices.extract#DatabaseRecords")
                .parameters([databaseSourceQueryId: "KREWE_QUERY_NULL_CONF",
                             windowStart: Timestamp.valueOf("2026-07-01 00:00:00"),
                             windowEnd: Timestamp.valueOf("2026-07-02 00:00:00")])
                .disableAuthz().call()
        assertEquals(false, out.dataAvailable)
        assertNull(out.fileLocation)
        assertTrue((out.errors as List).join(" ").toLowerCase().contains("missing tenant scope"),
                "expected fail-closed missing-tenant-scope rejection, got: ${out.errors}")
    }

    @Test
    void interactivePathRejectsBothNullTenantRows() {
        // Both the query (NULL_TENANT_QUERY) and its config (NULL_TENANT_CONF) have a null tenant. On the
        // interactive path (no companyUserGroupId) requireTenantRecordAccess rejects the null-tenant query
        // upstream, so the config cross-check is never reached — a null==null equality could never slip
        // through here. The service must reject and write no file (defense-in-depth: two layers fail
        // closed on a scope-less query, so the error text is generic rather than the cross-check's).
        Map out = ec.service.sync().name("reconciliation.DatabaseExtractionServices.extract#DatabaseRecords")
                .parameters([databaseSourceQueryId: "NULL_TENANT_QUERY",
                             windowStart: Timestamp.valueOf("2026-07-01 00:00:00"),
                             windowEnd: Timestamp.valueOf("2026-07-02 00:00:00")])
                .disableAuthz().call()
        assertEquals(false, out.dataAvailable)
        assertNull(out.fileLocation)
        assertFalse((out.errors as List).isEmpty(), "expected a fail-closed rejection for a scope-less query")
    }

    private Map callExtract(String queryId) {
        return ec.service.sync().name("reconciliation.DatabaseExtractionServices.extract#DatabaseRecords")
                .parameters([databaseSourceQueryId: queryId, companyUserGroupId: "SMOKE_TENANT",
                             windowStart: Timestamp.valueOf("2026-07-01 00:00:00"),
                             windowEnd: Timestamp.valueOf("2026-07-02 00:00:00")])
                .disableAuthz().call()
    }
}
