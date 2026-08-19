package darpan.database

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

import java.sql.DriverManager
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/** Runs only when DARPAN_MYSQL_TEST_HOST is set. Start a disposable MySQL first:
 *  docker run --rm -d --name darpan-mysql-test -e MYSQL_ROOT_PASSWORD=test -e MYSQL_DATABASE=recon_test -p 13306:3306 mysql:8
 */
@EnabledIfEnvironmentVariable(named = "DARPAN_MYSQL_TEST_HOST", matches = ".+")
class MySqlIntegrationTests {

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
        [host: System.getenv("DARPAN_MYSQL_TEST_HOST"), port: (System.getenv("DARPAN_MYSQL_TEST_PORT") ?: "13306") as long,
         databaseName: "recon_test", username: "root", password: System.getenv("DARPAN_MYSQL_TEST_PASSWORD") ?: "test",
         requireTls: "N", connectTimeoutSeconds: 5L, queryTimeoutSeconds: 30L, maxRows: 1000L]
    }

    @Test
    void extractsSeededRowsEndToEnd() {
        def conn = DriverManager.getConnection(JdbcUrlSupport.buildUrl(config()), JdbcUrlSupport.connectionProperties(config()))
        conn.createStatement().execute("DROP TABLE IF EXISTS orders")
        conn.createStatement().execute("CREATE TABLE orders (id INT PRIMARY KEY, total DECIMAL(10,2), updated_at TIMESTAMP)")
        conn.createStatement().execute("INSERT INTO orders VALUES (1, 10.5, '2026-07-01 10:00:00'), (2, 20.0, '2026-07-02 10:00:00')")
        conn.close()

        Map result = DatabaseExtractionSupport.runExtraction(config(),
                [sqlSelect: "SELECT id, total FROM orders WHERE updated_at BETWEEN :windowStart AND :windowEnd"],
                Timestamp.valueOf("2026-07-01 00:00:00"), Timestamp.valueOf("2026-07-03 00:00:00"))
        assertEquals([], result.errors)
        assertTrue(result.dataAvailable as boolean)
        assertEquals(2L, result.recordCount)
        assertNotNull(result.outputText)
        assertNotNull(result.requestMetadata.sqlSha256)
    }

    @Test
    void readOnlySessionBlocksWrites() {
        // Self-contained: create the table on a separate read-write connection FIRST so the INSERT below
        // can ONLY throw because of the read-only fence — never because the table is missing. Do NOT
        // depend on extractsSeededRowsEndToEnd (or any method order) to have created it, otherwise a
        // "table doesn't exist" error would satisfy assertThrows and silently mask a fence regression.
        def setup = DriverManager.getConnection(JdbcUrlSupport.buildUrl(config()), JdbcUrlSupport.connectionProperties(config()))
        setup.createStatement().execute("DROP TABLE IF EXISTS orders")
        setup.createStatement().execute("CREATE TABLE orders (id INT PRIMARY KEY, total DECIMAL(10,2), updated_at TIMESTAMP)")
        setup.close()

        // Direct proof: open the hardened connection the same way runExtraction does and verify a write fails.
        def conn = DriverManager.getConnection(JdbcUrlSupport.buildUrl(config()), JdbcUrlSupport.connectionProperties(config()))
        conn.setReadOnly(true)
        conn.createStatement().execute("SET SESSION TRANSACTION READ ONLY")
        conn.setAutoCommit(false)   // READ ONLY is enforced per-transaction; match runExtraction's session shape
        assertThrows(Exception) { conn.createStatement().executeUpdate("INSERT INTO orders VALUES (99, 1, NOW())") }
        conn.close()
    }
}
