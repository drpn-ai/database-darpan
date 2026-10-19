package darpan.database

import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class DatabaseQueryCrudTests {

    private static String facadeXml() { new File('service/facade/DatabaseFacadeServices.xml').text }
    private static File script(String n) { new File("src/main/groovy/darpan/database/facade/${n}.groovy") }

    @Test void queryServicesAreDeclared() {
        String x = facadeXml()
        assertTrue(x.contains('verb="save" noun="DatabaseSourceQuery"'), 'save not declared')
        assertTrue(x.contains('verb="list" noun="DatabaseSourceQueries"'), 'list not declared')
        assertTrue(x.contains('verb="delete" noun="DatabaseSourceQuery"'), 'delete not declared')
    }

    @Test void saveValidatesTheSelectBeforePersisting() {
        assertTrue(script('saveDatabaseSourceQuery').text.contains('SqlSelectValidator'),
                'an unvalidated statement must never reach the extractor')
    }

    @Test void saveCrossChecksTheParentConfigTenant() {
        // databaseSourceConfigId is a plain FK with no DB-level tenant scoping, so without this a
        // tenant could attach a query to another tenant's connection.
        assertTrue(script('saveDatabaseSourceQuery').text.contains('requireTenantRecordAccess'),
                'the parent config must be proven same-tenant')
    }

    @Test void writeServicesGateOnTenantEditPermission() {
        assertTrue(script('saveDatabaseSourceQuery').text.contains('canEditActiveTenantData'))
        String x = facadeXml()
        int start = x.indexOf('verb="delete" noun="DatabaseSourceQuery"')
        assertTrue(x.substring(start, x.indexOf('</service>', start)).contains('canEditActiveTenantData'))
    }

    @Test void validatorRejectsNonSelectAndUnknownPlaceholders() {
        assertFalse(SqlSelectValidator.validate("DELETE FROM order_header").isEmpty(),
                'non-SELECT must be rejected')
        assertFalse(SqlSelectValidator.validate(
                "SELECT * FROM order_header WHERE entry_date > :bogusParam").isEmpty(),
                'only :windowStart and :windowEnd are supported')
    }

    @Test void validatorAcceptsAWindowedSelect() {
        assertTrue(SqlSelectValidator.validate(
                "SELECT order_id FROM order_header WHERE entry_date >= :windowStart AND entry_date < :windowEnd").isEmpty())
    }

    @Test void sqlSelectParametersAllowAngleBrackets() {
        // Moqui's HTML filter rejects < and > in string parameters. The canonical windowed query
        // (entry_date >= :windowStart AND entry_date < :windowEnd) is therefore unsaveable without
        // allow-html="any" - found by the live CHELAN run, invisible to validator-level unit tests
        // because it fails in the service layer before the script ever executes.
        String x = facadeXml()
        for (String marker : ['verb="save" noun="DatabaseSourceQuery"', 'verb="preview" noun="DatabaseQuery"']) {
            int start = x.indexOf(marker)
            assertTrue(start >= 0, "missing service: ${marker}")
            String block = x.substring(start, x.indexOf('</service>', start))
            int p = block.indexOf('name="sqlSelect"')
            assertTrue(p >= 0, "no sqlSelect parameter in ${marker}")
            assertTrue(block.substring(p, block.indexOf('/>', p)).contains('allow-html="any"'),
                    "sqlSelect must accept angle brackets in ${marker}")
        }
    }
}
