package darpan.database

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

import java.sql.DriverManager
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/** Runs only when DARPAN_PG_TEST_HOST is set. Start a disposable Postgres first:
 *  docker run --rm -d --name darpan-pg-test -e POSTGRES_PASSWORD=test -e POSTGRES_DB=recon_test -p 15432:5432 postgres:16
 */
@EnabledIfEnvironmentVariable(named = "DARPAN_PG_TEST_HOST", matches = ".+")
class PostgresIntegrationTests {
    private static Map config() {
        [dialect: "POSTGRES", host: System.getenv("DARPAN_PG_TEST_HOST"),
         port: (System.getenv("DARPAN_PG_TEST_PORT") ?: "15432") as long,
         databaseName: "recon_test", username: "postgres", password: System.getenv("DARPAN_PG_TEST_PASSWORD") ?: "test",
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
    }

    @Test
    void readOnlySessionBlocksWrites() {
        // Self-contained: create the table on a separate read-write connection FIRST so the INSERT below
        // can ONLY throw because of the read-only fence — never because the table is missing. Do NOT
        // depend on extractsSeededRowsEndToEnd (or any method order) to have created it, otherwise a
        // "relation does not exist" error would satisfy assertThrows and silently mask a fence regression.
        // (readOnlyMode=always only engages once setReadOnly(true) is called, so this connection is R/W.)
        def setup = DriverManager.getConnection(JdbcUrlSupport.buildUrl(config()), JdbcUrlSupport.connectionProperties(config()))
        setup.createStatement().execute("DROP TABLE IF EXISTS orders")
        setup.createStatement().execute("CREATE TABLE orders (id INT PRIMARY KEY, total DECIMAL(10,2), updated_at TIMESTAMP)")
        setup.close()

        // NOTE: connectionProperties sets readOnlyMode=always, but the pg driver only enforces it when
        // the connection is marked read-only — this test proves the combined shape actually blocks writes.
        def conn = DriverManager.getConnection(JdbcUrlSupport.buildUrl(config()), JdbcUrlSupport.connectionProperties(config()))
        conn.setReadOnly(true)
        conn.setAutoCommit(false)
        JdbcUrlSupport.readOnlySessionStatements("POSTGRES").each { conn.createStatement().execute(it) }
        assertThrows(Exception) { conn.createStatement().executeUpdate("INSERT INTO orders VALUES (99, 1, NOW())") }
        conn.close()
    }

    @Test
    void openReadOnlyConnectionAppliesFullFenceAndBlocksWrites() {
        // DatabaseExtractionSupport.openReadOnlyConnection is the single shared helper both runExtraction
        // AND previewDatabaseQuery.groovy now use to open their connection (setReadOnly + setAutoCommit(false)
        // + dialect readOnlySessionStatements). Proving the SHARED method enforces the fence proves both
        // read paths are equally hardened, rather than re-testing a hand-rolled duplicate of the shape.
        def setup = DriverManager.getConnection(JdbcUrlSupport.buildUrl(config()), JdbcUrlSupport.connectionProperties(config()))
        setup.createStatement().execute("DROP TABLE IF EXISTS orders")
        setup.createStatement().execute("CREATE TABLE orders (id INT PRIMARY KEY, total DECIMAL(10,2), updated_at TIMESTAMP)")
        setup.close()

        def conn = DatabaseExtractionSupport.openReadOnlyConnection(config())
        assertThrows(Exception) { conn.createStatement().executeUpdate("INSERT INTO orders VALUES (99, 1, NOW())") }
        conn.close()
    }
}
