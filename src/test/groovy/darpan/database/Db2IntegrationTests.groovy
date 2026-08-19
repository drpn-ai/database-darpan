package darpan.database

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

import java.sql.DriverManager
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/** Runs only when DARPAN_DB2_TEST_HOST is set. Db2 has no session-level read-only statement, so its
 *  fence is Connection.setReadOnly + the documented SELECT-only grant — extraction proof only here.
 *  Db2 database names are limited to 8 characters, hence RECONTST. */
@EnabledIfEnvironmentVariable(named = "DARPAN_DB2_TEST_HOST", matches = ".+")
class Db2IntegrationTests {

    /**
     * These suites deliberately target a disposable loopback container, which the JdbcUrlSupport host
     * guard rejects by default. Declare that intent here rather than weakening the guard: production
     * still refuses non-routable addresses because MoquiProductionConf.xml never sets this property.
     */
    @BeforeAll static void allowLoopbackForDisposableContainer() {
        System.setProperty("database_source_allow_private_hosts", "true")
    }
    @AfterAll static void restoreHostPolicy() {
        System.clearProperty("database_source_allow_private_hosts")
    }
    private static Map config() {
        [dialect: "DB2", host: System.getenv("DARPAN_DB2_TEST_HOST"),
         port: (System.getenv("DARPAN_DB2_TEST_PORT") ?: "50000") as long,
         databaseName: "RECONTST", username: "db2inst1", password: System.getenv("DARPAN_DB2_TEST_PASSWORD") ?: "test",
         requireTls: "N", connectTimeoutSeconds: 10L, queryTimeoutSeconds: 60L, maxRows: 1000L]
    }

    @Test
    void extractsSeededRowsEndToEnd() {
        def conn = DriverManager.getConnection(JdbcUrlSupport.buildUrl(config()), JdbcUrlSupport.connectionProperties(config()))
        try { conn.createStatement().execute("DROP TABLE orders") } catch (Exception ignored) { }
        conn.createStatement().execute("CREATE TABLE orders (id INT NOT NULL PRIMARY KEY, total DECIMAL(10,2), updated_at TIMESTAMP)")
        conn.createStatement().execute("INSERT INTO orders VALUES (1, 10.5, '2026-07-01 10:00:00'), (2, 20.0, '2026-07-02 10:00:00')")
        conn.close()

        Map result = DatabaseExtractionSupport.runExtraction(config(),
                [sqlSelect: "SELECT id, total FROM orders WHERE updated_at BETWEEN :windowStart AND :windowEnd"],
                Timestamp.valueOf("2026-07-01 00:00:00"), Timestamp.valueOf("2026-07-03 00:00:00"))
        assertEquals([], result.errors)
        assertTrue(result.dataAvailable as boolean)
        assertEquals(2L, result.recordCount)
        assertNotNull(result.requestMetadata.sqlSha256)
    }
}
