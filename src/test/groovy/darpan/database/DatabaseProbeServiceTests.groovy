package darpan.database

import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

/**
 * The registry's healthCheckServiceName slot dispatches probe# services and nothing else
 * (SourceSystemConnectorSupport.PROBE_SERVICE_NAME). validate#DatabaseConnection therefore cannot
 * fill the slot, and a rename here would silently unregister diagnostics rather than fail loudly.
 */
class DatabaseProbeServiceTests {

    private static String facadeXml() { new File('service/facade/DatabaseFacadeServices.xml').text }

    private static String declarationOf(String xml, String marker) {
        int i = xml.indexOf(marker)
        assertTrue(i >= 0, "service not declared: ${marker}")
        return xml.substring(i, xml.indexOf('>', i))
    }

    @Test void probeServiceNameMatchesTheRegistryProbeShape() {
        String name = "facade.DatabaseFacadeServices.probe#DatabaseConnection"
        assertTrue(name ==~ /^(reconciliation|facade)\.[A-Za-z0-9]+(Extraction|Facade)Services\.probe#[A-Za-z0-9]+$/)
    }

    @Test void probeServiceIsDeclaredAndNotRemote() {
        String decl = declarationOf(facadeXml(), 'verb="probe" noun="DatabaseConnection"')
        assertTrue(decl.contains('allow-remote="false"'),
                "probe must stay internal - test#SourceConnection owns the permission gate")
    }

    /** Strip line comments so this asserts on code, not on prose that mentions the call. */
    private static String codeOf(File f) {
        return f.readLines().findAll { !it.trim().startsWith('//') }.join('\n')
    }

    @Test void probeScriptExistsAndNeverWritesServiceErrors() {
        File g = new File('src/main/groovy/darpan/database/facade/probeDatabaseConnection.groovy')
        assertTrue(g.exists(), "probe script missing")
        assertFalse(codeOf(g).contains('ec.message.addError'),
                "a rejected credential is a check row, not a service error")
    }

    @Test void probeAppliesTheTenantFence() {
        String g = new File('src/main/groovy/darpan/database/facade/probeDatabaseConnection.groovy').text
        assertTrue(g.contains('requireTenantRecordAccess'),
                "probe reads a config by id and must not cross tenants")
    }
}
