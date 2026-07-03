package darpan.database

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseConfigEntitySmokeTests {
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "database-config-entity-smoke")
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @Test
    void createsAndReadsConfigWithEncryptedPassword() {
        ec.entity.makeValue("darpan.database.DatabaseSourceConfig")
                .setAll([databaseSourceConfigId: "TEST_DB_CONF",
                         description: "test", host: "db.example.internal", port: 3306L,
                         databaseName: "orders", username: "darpan_ro", password: "s3cret"])
                .create()
        def row = ec.entity.find("darpan.database.DatabaseSourceConfig")
                .condition("databaseSourceConfigId", "TEST_DB_CONF").disableAuthz().one()
        assertNotNull(row)
        assertEquals("db.example.internal", row.host) // stored as written
        assertEquals("s3cret", row.password)          // decrypted transparently on read
        assertEquals("MYSQL", row.dialect)            // default applied
        assertEquals(500000L, row.maxRows)            // default applied
    }

    @Test
    void createsQueryAttachedToConfig() {
        // Create config first (tests are independent, so prior test state is not available)
        ec.entity.makeValue("darpan.database.DatabaseSourceConfig")
                .setAll([databaseSourceConfigId: "TEST_DB_CONF_2",
                         description: "test", host: "db2.example.internal", port: 3306L,
                         databaseName: "orders", username: "darpan_ro", password: "s3cret"])
                .create()
        // Create query attached to config
        ec.entity.makeValue("darpan.database.DatabaseSourceQuery")
                .setAll([databaseSourceQueryId: "TEST_DB_QUERY", databaseSourceConfigId: "TEST_DB_CONF_2",
                         sqlSelect: "SELECT id, total FROM orders WHERE updated_at BETWEEN :windowStart AND :windowEnd"])
                .create()
        def row = ec.entity.find("darpan.database.DatabaseSourceQuery")
                .condition("databaseSourceQueryId", "TEST_DB_QUERY").disableAuthz().one()
        assertNotNull(row)
        assertEquals("TEST_DB_CONF_2", row.databaseSourceConfigId)
    }
}
