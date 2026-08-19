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
        assertTrue(script('deleteDatabaseSourceQuery').text.contains('canEditActiveTenantData'))
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
}
