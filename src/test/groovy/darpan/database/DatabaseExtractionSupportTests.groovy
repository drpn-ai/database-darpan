package darpan.database

import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseExtractionSupportTests {
    private Connection conn

    @BeforeAll
    void setup() {
        conn = DriverManager.getConnection("jdbc:h2:mem:extract;MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "")
        conn.createStatement().execute("""
            CREATE TABLE orders (id INT PRIMARY KEY, total DECIMAL(10,2), updated_at TIMESTAMP);
            INSERT INTO orders VALUES (1, 10.50, '2026-07-01 10:00:00'), (2, 20.00, '2026-07-02 10:00:00'),
                                      (3, 30.00, '2026-07-10 10:00:00');
        """)
    }

    @AfterAll
    void cleanup() { conn.close() }

    @Test
    void translatesNamedPlaceholdersInOrder() {
        Map prepared = DatabaseExtractionSupport.prepareStatementSql(
                "SELECT id FROM orders WHERE updated_at >= :windowStart AND updated_at < :windowEnd AND updated_at > :windowStart")
        assertEquals("SELECT id FROM orders WHERE updated_at >= ? AND updated_at < ? AND updated_at > ?", prepared.sql)
        assertEquals(["windowStart", "windowEnd", "windowStart"], prepared.bindNames)
    }

    @Test
    void extractsWindowedRowsAsMaps() {
        Map result = DatabaseExtractionSupport.extractRows(conn,
                "SELECT id, total FROM orders WHERE updated_at BETWEEN :windowStart AND :windowEnd",
                [windowStart: Timestamp.valueOf("2026-07-01 00:00:00"), windowEnd: Timestamp.valueOf("2026-07-03 00:00:00")],
                1000L, 30L)
        assertEquals([], result.errors)
        assertEquals(2L, result.recordCount)
        assertEquals(1, result.rows[0].id)             // column labels preserved (lower-cased by H2 mode)
    }

    @Test
    void failsInsteadOfTruncatingWhenMaxRowsExceeded() {
        Map result = DatabaseExtractionSupport.extractRows(conn, "SELECT id FROM orders", [:], 2L, 30L)
        assertFalse(result.errors.isEmpty())
        assertTrue(result.errors[0].contains("maxRows"))
        assertEquals(0L, result.recordCount)           // nothing partial is returned
        assertTrue(result.rows.isEmpty())
    }

    @Test
    void queryWithoutPlaceholdersRunsUnbound() {
        Map result = DatabaseExtractionSupport.extractRows(conn, "SELECT COUNT(*) AS n FROM orders", [:], 10L, 30L)
        assertEquals([], result.errors)
        assertEquals(1L, result.recordCount)
    }

    @Test
    void serializesRowsToJsonDocument() {
        Map result = DatabaseExtractionSupport.extractRows(conn, "SELECT id, total FROM orders WHERE id = 1", [:], 10L, 30L)
        String json = DatabaseExtractionSupport.toOutputText(result.rows)
        def parsed = new JsonSlurper().parseText(json)
        assertEquals(1, parsed.size())
        assertEquals(1, parsed[0].id)
    }
}
