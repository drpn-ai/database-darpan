package darpan.database

import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

/**
 * The facade artifact group is a pattern group (facade\..*), so any remote facade service is
 * reachable by every tenant role including DARPAN_TENANT_USER. Write gates therefore have to live
 * in the service Groovy, which is what these tests pin.
 */
class DatabaseConfigCrudTests {

    private static String facadeXml() { new File('service/facade/DatabaseFacadeServices.xml').text }
    private static File script(String n) { new File("src/main/groovy/darpan/database/facade/${n}.groovy") }
    /** One service's block; list# and delete# are XML actions in the facade file (DAR-BE-074). */
    private static String serviceBlock(String verb, String noun) {
        String x = facadeXml()
        int start = x.indexOf("verb=\"${verb}\" noun=\"${noun}\"")
        assertTrue(start >= 0, "missing service ${verb}#${noun}")
        return x.substring(start, x.indexOf('</service>', start))
    }

    @Test void crudServicesAreDeclared() {
        String x = facadeXml()
        assertTrue(x.contains('verb="save" noun="DatabaseSourceConfig"'), 'save not declared')
        assertTrue(x.contains('verb="list" noun="DatabaseSourceConfigs"'), 'list not declared')
        assertTrue(x.contains('verb="delete" noun="DatabaseSourceConfig"'), 'delete not declared')
    }

    @Test void listServiceNeverDeclaresPasswordOutput() {
        String x = facadeXml()
        int start = x.indexOf('verb="list" noun="DatabaseSourceConfigs"')
        int end = x.indexOf('</service>', start)
        assertFalse(x.substring(start, end).contains('password'),
                'list must not return the stored credential')
    }

    @Test void listBuildsAnExplicitFieldAllowlistNotGetMap() {
        String g = serviceBlock('list', 'DatabaseSourceConfigs')
        assertFalse(g.contains('.getMap()'),
                'an allowlist stays correct when new columns are added; getMap() would leak them')
        assertFalse(g.contains('password'), 'password must never leave the server')
    }

    @Test void writeServicesGateOnTenantEditPermission() {
        assertTrue(script('saveDatabaseSourceConfig').text.contains('canEditActiveTenantData'),
                'save must be tenant-admin gated')
        assertTrue(serviceBlock('delete', 'DatabaseSourceConfig').contains('canEditActiveTenantData'),
                'delete must be tenant-admin gated')
    }

    @Test void saveValidatesTheHostBeforePersisting() {
        assertTrue(script('saveDatabaseSourceConfig').text.contains('JdbcUrlSupport'),
                'a blocked address must be rejected at save time, not as a later extraction failure')
    }

    @Test void deleteRefusesWhileQueriesStillReferenceTheConfig() {
        assertTrue(serviceBlock('delete', 'DatabaseSourceConfig').contains('DatabaseSourceQuery'),
                'deleting a config out from under its queries would orphan them')
    }
}
