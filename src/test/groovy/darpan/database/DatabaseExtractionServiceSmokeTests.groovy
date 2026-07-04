package darpan.database

import darpan.reconciliation.automation.SourceSystemConnectorSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
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
        // DatabaseSourceConfig/Query.companyUserGroupId is a real FK (DBSRCCONF_COMPANY/DBSRCQRY_COMPANY)
        // to moqui.security.UserGroup, so SMOKE_TENANT must exist before it can be used as a tenant value
        // below (mirrors HotWaxOmsRestSourceConfigFacadeSmokeTests#seedTenant for the same FK shape).
        ec.entity.makeValue("moqui.security.UserGroup").setAll([
                userGroupId: "SMOKE_TENANT", description: "Smoke Tenant"]).create()
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

    private Map callExtract(String queryId) {
        return ec.service.sync().name("reconciliation.DatabaseExtractionServices.extract#DatabaseRecords")
                .parameters([databaseSourceQueryId: queryId, companyUserGroupId: "SMOKE_TENANT",
                             windowStart: Timestamp.valueOf("2026-07-01 00:00:00"),
                             windowEnd: Timestamp.valueOf("2026-07-02 00:00:00")])
                .disableAuthz().call()
    }
}
