package darpan.database

import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

/**
 * Exposure posture, pinned. preview goes remote for the UI's SQL card; extract and validate must
 * not, and the tenant fences they rely on are pinned here so a later refactor cannot quietly
 * remove them.
 */
class DatabaseServiceExposureTests {

    private static String declarationOf(String xml, String marker) {
        int i = xml.indexOf(marker)
        assertTrue(i >= 0, "service not declared: ${marker}")
        return xml.substring(i, xml.indexOf('>', i))
    }

    @Test void previewIsRemote() {
        String x = new File('service/facade/DatabaseFacadeServices.xml').text
        assertTrue(declarationOf(x, 'verb="preview" noun="DatabaseQuery"').contains('allow-remote="true"'),
                'the SQL card calls preview to prove record shape before save')
    }

    @Test void validateStaysInternal() {
        String x = new File('service/facade/DatabaseFacadeServices.xml').text
        assertFalse(declarationOf(x, 'verb="validate" noun="DatabaseConnection"').contains('allow-remote="true"'),
                'connection testing routes through test#SourceConnection, which owns the gate')
    }

    @Test void extractStaysInternal() {
        String x = new File('service/reconciliation/DatabaseExtractionServices.xml').text
        assertTrue(declarationOf(x, 'verb="extract" noun="DatabaseRecords"').contains('allow-remote="false"'),
                'extract takes companyUserGroupId as a parameter; exposing it hands over the tenant fence')
    }

    /** The query/connection tenant check is one helper (DatabaseExtractionSupportTests pins its behavior); both doors use it. */
    @Test void extractAndPreviewApplyTheSameCrossCheck() {
        ['src/main/groovy/darpan/database/reconciliation/automation/extractDatabaseRecords.groovy',
         'src/main/groovy/darpan/database/facade/previewDatabaseQuery.groovy'].each { String path ->
            assertTrue(new File(path).text.contains('DatabaseExtractionSupport.queryConfigMismatch(queryRow, configRow)'),
                    "${path}: preview must not be a weaker door to the same data than extract")
        }
    }

    /** OC200: the two services that connect for a caller take run access. */
    @Test void previewAndValidateGateOnRunAccess() {
        ['previewDatabaseQuery', 'validateDatabaseConnection'].each { String name ->
            assertTrue(new File("src/main/groovy/darpan/database/facade/${name}.groovy").text.contains('canRunActiveTenantReconciliation'), name)
        }
    }
}
