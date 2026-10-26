package darpan.database

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * list# / delete# DatabaseSourceConfig(s) and DatabaseSourceQuer(y|ies), through the real services.
 *
 * <p>Most calls run in production posture — the smoke harness's anonymous-authorized-all frame
 * removed, authz ON, the caller a real DARPAN_USER member, the security seed (with the
 * DARPAN_ACTIVE_COMPANY_SCOPE filter) loaded. The tenant boundary is pinned four ways for each
 * service: the active tenant's own rows (positive control), another tenant's rows, unstamped
 * rows, and a caller with no active tenant (the sentinel). The list services are also called with
 * authz disabled, the posture every other facade test in the repo uses, to pin that their tenant
 * scoping does not depend on the entity filter being in force.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseSourceListDeleteServiceTests {

    private static final String KREWE = "KREWE"
    private static final String GORJANA = "GORJANA"
    private static final String EDITOR_ID = "TEST_CUSTOMER_USER"
    private static final String EDITOR_USERNAME = "test.customer"
    private static final String NO_TENANT_ID = "DBLD_NO_TENANT_USER"
    private static final String NO_TENANT_USERNAME = "dbld.notenant"
    private static final Timestamp FROM_DATE = Timestamp.valueOf("2026-04-21 00:00:00")

    private static final List<String> CONFIG_KEYS = ["databaseSourceConfigId", "description", "dialect", "host", "port",
                                                     "databaseName", "username", "requireTls", "isActive"].asImmutable()
    private static final List<String> QUERY_KEYS = ["databaseSourceQueryId", "databaseSourceConfigId", "description",
                                                    "sqlSelect", "isActive"].asImmutable()

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "database-source-list-delete")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)   // KREWE editor, logged in

        insert("moqui.security.UserGroup", [userGroupId: GORJANA, description: "Gorjana",
                                            groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        insert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: EDITOR_ID, fromDate: FROM_DATE])
        insert("moqui.security.UserAccount", [userId: NO_TENANT_ID, username: NO_TENANT_USERNAME,
                                              userFullName: "No tenant", currentPassword: "", disabled: "N"])
        insert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: NO_TENANT_ID, fromDate: FROM_DATE])
        // UserFacade reuses a login for the same username, cached groups included: switch user so
        // the DARPAN_USER membership above is seen.
        actAs(NO_TENANT_USERNAME, null)
        actAs(EDITOR_USERNAME, KREWE)
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void resetFixtures() {
        actAs(EDITOR_USERNAME, KREWE)
        ec.entity.find("darpan.database.DatabaseSourceQuery").condition("databaseSourceQueryId", "like", "DBLD_%")
                .disableAuthz().deleteAll()
        ec.entity.find("darpan.database.DatabaseSourceConfig").condition("databaseSourceConfigId", "like", "DBLD_%")
                .disableAuthz().deleteAll()
        config("DBLD_CONF_K1", KREWE)
        config("DBLD_CONF_K2", KREWE)
        config("DBLD_CONF_G", GORJANA)
        config("DBLD_CONF_N", null)
        query("DBLD_Q_K1", "DBLD_CONF_K1", KREWE)
        query("DBLD_Q_K2", "DBLD_CONF_K2", KREWE)
        query("DBLD_Q_G", "DBLD_CONF_G", GORJANA)
        query("DBLD_Q_N", "DBLD_CONF_N", null)
        ec.message.clearErrors()
    }

    // ---------------------------------------------------------------- list#DatabaseSourceConfigs

    @Test
    void listConfigsReturnsOnlyTheActiveTenantsRowsWithTheAllowlistedFields() {
        Map out = production { invoke("facade.DatabaseFacadeServices.list#DatabaseSourceConfigs", [:]) }
        List configs = (out.configs as List).findAll { it.databaseSourceConfigId.toString().startsWith("DBLD_") }
        assertEquals(["DBLD_CONF_K1", "DBLD_CONF_K2"], configs*.databaseSourceConfigId.sort())
        configs.each { Map c -> assertEquals(CONFIG_KEYS.toSet(), c.keySet(), "allowlist only; never the password") }
        Map k1 = configs.find { it.databaseSourceConfigId == "DBLD_CONF_K1" } as Map
        assertEquals("db-DBLD_CONF_K1.invalid", k1.host)
        assertEquals("orders", k1.databaseName)
        assertEquals("reader", k1.username)
    }

    @Test
    void listConfigsIsTenantScopedEvenWithAuthzDisabled() {
        Map out = invoke("facade.DatabaseFacadeServices.list#DatabaseSourceConfigs", [:], true)
        assertEquals(["DBLD_CONF_K1", "DBLD_CONF_K2"], dbldIds(out.configs as List, "databaseSourceConfigId"))
    }

    @Test
    void listConfigsGivesACallerWithNoActiveTenantNothing() {
        actAs(NO_TENANT_USERNAME, null)
        Map out = production { invoke("facade.DatabaseFacadeServices.list#DatabaseSourceConfigs", [:]) }
        assertEquals([], out.configs)
    }

    // ---------------------------------------------------------------- list#DatabaseSourceQueries

    @Test
    void listQueriesReturnsOnlyTheActiveTenantsRows() {
        Map out = production { invoke("facade.DatabaseFacadeServices.list#DatabaseSourceQueries", [:]) }
        List queries = (out.queries as List).findAll { it.databaseSourceQueryId.toString().startsWith("DBLD_") }
        assertEquals(["DBLD_Q_K1", "DBLD_Q_K2"], queries*.databaseSourceQueryId.sort())
        queries.each { Map q -> assertEquals(QUERY_KEYS.toSet(), q.keySet()) }
        assertEquals("SELECT 1 AS one", (queries.find { it.databaseSourceQueryId == "DBLD_Q_K1" } as Map).sqlSelect)
    }

    @Test
    void listQueriesNarrowsToOneConfigAndNeverToAForeignOne() {
        Map own = production { invoke("facade.DatabaseFacadeServices.list#DatabaseSourceQueries", [databaseSourceConfigId: "DBLD_CONF_K2"]) }
        assertEquals(["DBLD_Q_K2"], dbldIds(own.queries as List, "databaseSourceQueryId"))
        Map foreign = production { invoke("facade.DatabaseFacadeServices.list#DatabaseSourceQueries", [databaseSourceConfigId: "DBLD_CONF_G"]) }
        assertEquals([], dbldIds(foreign.queries as List, "databaseSourceQueryId"))
    }

    @Test
    void listQueriesIsTenantScopedEvenWithAuthzDisabled() {
        Map out = invoke("facade.DatabaseFacadeServices.list#DatabaseSourceQueries", [:], true)
        assertEquals(["DBLD_Q_K1", "DBLD_Q_K2"], dbldIds(out.queries as List, "databaseSourceQueryId"))
    }

    @Test
    void listQueriesGivesACallerWithNoActiveTenantNothing() {
        actAs(NO_TENANT_USERNAME, null)
        Map out = production { invoke("facade.DatabaseFacadeServices.list#DatabaseSourceQueries", [:]) }
        assertEquals([], out.queries)
    }

    // ---------------------------------------------------------------- delete#DatabaseSourceQuery

    @Test
    void deleteQueryRemovesTheActiveTenantsOwnQuery() {
        Map out = production { invoke("facade.DatabaseFacadeServices.delete#DatabaseSourceQuery", [databaseSourceQueryId: "DBLD_Q_K1"]) }
        assertEquals(true, out.ok, "${ec.message.errors}")
        assertNull(queryRow("DBLD_Q_K1"))
        assertNotNull(queryRow("DBLD_Q_K2"), "only the named query goes")
    }

    @Test
    void deleteQueryRefusesAnotherTenantsQueryAndAnUnstampedOne() {
        ["DBLD_Q_G", "DBLD_Q_N"].each { String id ->
            Map out = production { invoke("facade.DatabaseFacadeServices.delete#DatabaseSourceQuery", [databaseSourceQueryId: id]) }
            assertEquals(false, out.ok, "${id} must not be deletable from KREWE")
            assertEquals(["Database source query ${id} not found.".toString()], ec.message.errors)
            assertNotNull(queryRow(id), "${id} must survive")
            ec.message.clearErrors()
        }
    }

    @Test
    void deleteQueryReportsAMissingQueryAsNotFound() {
        Map out = production { invoke("facade.DatabaseFacadeServices.delete#DatabaseSourceQuery", [databaseSourceQueryId: "DBLD_NOPE"]) }
        assertEquals(false, out.ok)
        assertEquals(["Database source query DBLD_NOPE not found."], ec.message.errors)
    }

    @Test
    void deleteQueryRefusesACallerWithNoActiveTenant() {
        actAs(NO_TENANT_USERNAME, null)
        Map out = production { invoke("facade.DatabaseFacadeServices.delete#DatabaseSourceQuery", [databaseSourceQueryId: "DBLD_Q_K1"]) }
        assertEquals(false, out.ok)
        assertEquals(["You do not have permission to change database sources in this tenant."], ec.message.errors)
        assertNotNull(queryRow("DBLD_Q_K1"))
    }

    // ---------------------------------------------------------------- delete#DatabaseSourceConfig

    @Test
    void deleteConfigRemovesAnUnreferencedConfigOfTheActiveTenant() {
        ec.entity.find("darpan.database.DatabaseSourceQuery").condition("databaseSourceQueryId", "DBLD_Q_K1").disableAuthz().deleteAll()
        Map out = production { invoke("facade.DatabaseFacadeServices.delete#DatabaseSourceConfig", [databaseSourceConfigId: "DBLD_CONF_K1"]) }
        assertEquals(true, out.ok, "${ec.message.errors}")
        assertNull(configRow("DBLD_CONF_K1"))
    }

    @Test
    void deleteConfigRefusesWhileAQueryStillUsesIt() {
        Map out = production { invoke("facade.DatabaseFacadeServices.delete#DatabaseSourceConfig", [databaseSourceConfigId: "DBLD_CONF_K1"]) }
        assertEquals(false, out.ok)
        assertEquals(["Cannot delete: 1 saved query still use this connection."], ec.message.errors)
        assertNotNull(configRow("DBLD_CONF_K1"))
    }

    @Test
    void deleteConfigCountsReferencingQueriesOfEveryTenantAndUnstampedOnes() {
        // The guard exists to stop an orphaned query; a query stamped elsewhere (or not at all) is still
        // orphaned, so it must count even though the active tenant cannot see it.
        query("DBLD_Q_K2_N", "DBLD_CONF_K2", null)
        query("DBLD_Q_K2_G", "DBLD_CONF_K2", GORJANA)
        Map out = production { invoke("facade.DatabaseFacadeServices.delete#DatabaseSourceConfig", [databaseSourceConfigId: "DBLD_CONF_K2"]) }
        assertEquals(false, out.ok)
        assertEquals(["Cannot delete: 3 saved queries still use this connection."], ec.message.errors)
    }

    @Test
    void deleteConfigRefusesAnotherTenantsConfigAndAnUnstampedOne() {
        ["DBLD_CONF_G", "DBLD_CONF_N"].each { String id ->
            Map out = production { invoke("facade.DatabaseFacadeServices.delete#DatabaseSourceConfig", [databaseSourceConfigId: id]) }
            assertEquals(false, out.ok)
            assertEquals(["Database source config ${id} not found.".toString()], ec.message.errors)
            assertNotNull(configRow(id))
            ec.message.clearErrors()
        }
    }

    @Test
    void deleteConfigRefusesACallerWithNoActiveTenant() {
        actAs(NO_TENANT_USERNAME, null)
        Map out = production { invoke("facade.DatabaseFacadeServices.delete#DatabaseSourceConfig", [databaseSourceConfigId: "DBLD_CONF_K1"]) }
        assertEquals(false, out.ok)
        assertEquals(["You do not have permission to change database sources in this tenant."], ec.message.errors)
        assertNotNull(configRow("DBLD_CONF_K1"))
    }

    // ---------------------------------------------------------------- helpers

    private Map invoke(String name, Map params, boolean disableAuthz = false) {
        def svc = ec.service.sync().name(name).parameters(params)
        if (disableAuthz) svc.disableAuthz()
        return (svc.call() ?: [:]) as Map
    }

    private static List<String> dbldIds(List rows, String field) {
        return (rows*.get(field)).findAll { it.toString().startsWith("DBLD_") }.sort() as List<String>
    }

    /** Production posture: no harness grant on the stack, authz ON (see MigrationAdminServiceSmokeTests). */
    private <T> T production(Closure<T> body) {
        def harnessFrame = ec.artifactExecution.peek()
        assertEquals("smokeTests", harnessFrame?.name, "test precondition: unexpected artifact stack")
        ec.artifactExecution.pop(harnessFrame)
        boolean wasDisabled = ec.artifactExecution.authzDisabled
        ec.artifactExecution.enableAuthz()
        try {
            assertFalse(ec.artifactExecution.authzDisabled, "test precondition: authz must be ON")
            assertTrue(ec.artifactExecution.stack.isEmpty(), "test precondition: no inherited grant on the stack")
            return body.call()
        } finally {
            if (wasDisabled) ec.artifactExecution.disableAuthz()
            ec.artifactExecution.push("smokeTests", ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_ALL, false)
            ec.artifactExecution.setAnonymousAuthorizedAll()
        }
    }

    private void actAs(String username, String tenant) {
        assertTrue(ec.user.internalLoginUser(username), "could not log in ${username}")
        if (tenant) ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, tenant)
        TenantAccessSupport.syncUserContext(ec)
        ec.message.clearErrors()
    }

    private void config(String id, String tenant) {
        insert("darpan.database.DatabaseSourceConfig", [databaseSourceConfigId: id, companyUserGroupId: tenant,
                description: "conf ${id}".toString(), host: "db-${id}.invalid".toString(), databaseName: "orders",
                username: "reader", password: "secret-${id}".toString(), port: 3306L])
    }

    private void query(String id, String configId, String tenant) {
        insert("darpan.database.DatabaseSourceQuery", [databaseSourceQueryId: id, databaseSourceConfigId: configId,
                companyUserGroupId: tenant, description: "query ${id}".toString(), sqlSelect: "SELECT 1 AS one"])
    }

    private def configRow(String id) {
        return ec.entity.find("darpan.database.DatabaseSourceConfig").condition("databaseSourceConfigId", id)
                .disableAuthz().useCache(false).one()
    }

    private def queryRow(String id) {
        return ec.entity.find("darpan.database.DatabaseSourceQuery").condition("databaseSourceQueryId", id)
                .disableAuthz().useCache(false).one()
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }
}
