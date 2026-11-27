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
    void placeholderInsideStringLiteralIsNotTreatedAsBindMarker() {
        // A :windowStart token that appears INSIDE a string literal is text data, not a real bind
        // marker. SqlSelectValidator strips literals before its own checks, so this passes validation;
        // prepareStatementSql must independently recognize the literal and leave it untouched — only
        // the real occurrence outside the literal becomes a '?'. Otherwise bindNames.size() would exceed
        // the true '?' count (the driver never treats a '?' inside quotes as a parameter marker) and
        // setTimestamp would throw an opaque parameter-index-out-of-range error.
        Map prepared = DatabaseExtractionSupport.prepareStatementSql(
                "SELECT id, ':windowStart' AS literal_col FROM orders WHERE updated_at >= :windowStart")
        assertEquals("SELECT id, ':windowStart' AS literal_col FROM orders WHERE updated_at >= ?", prepared.sql)
        assertEquals(["windowStart"], prepared.bindNames)
    }

    @Test
    void extractRowsBindsCorrectlyWhenPlaceholderTextAppearsInsideAStringLiteral() {
        // End-to-end regression for the same bug: before the fix this threw a parameter-index error
        // because bindNames had 3 entries (the in-literal :windowStart, plus the two real ones) while
        // only 2 real '?' markers exist in the prepared SQL outside the literal.
        Map result = DatabaseExtractionSupport.extractRows(conn,
                "SELECT id, ':windowStart' AS literal_col FROM orders WHERE updated_at >= :windowStart AND updated_at < :windowEnd",
                [windowStart: Timestamp.valueOf("2026-07-01 00:00:00"), windowEnd: Timestamp.valueOf("2026-07-03 00:00:00")],
                1000L, 30L)
        assertEquals([], result.errors)
        assertEquals(2L, result.recordCount)
        assertEquals(":windowStart", result.rows[0].literal_col)
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
    void capIsLimitTrueReturnsExactlyMaxRowsWithoutError() {
        // Preview's silent-LIMIT contract: same 3-row table, same 2-row cap as the fail-not-truncate
        // test above, but capIsLimit=true must return exactly maxRows rows with NO error.
        Map result = DatabaseExtractionSupport.extractRows(conn, "SELECT id FROM orders", [:], 2L, 30L, true)
        assertEquals([], result.errors)
        assertEquals(2L, result.recordCount)
        assertEquals(2, result.rows.size())
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

    // DAR-BE-386 (part 2): a pulse asks the target side which keys it holds; a database endpoint answers from its window.
    @Test
    void keysNarrowTheRowsToThoseAsked() {
        List<Map> rows = [[order_name: "8H-1"], [order_name: "8H-2"], [order_name: "8H-3"]]
        assertEquals([[order_name: "8H-2"]], DatabaseExtractionSupport.keepLookupKeys(rows, ["8H-2", "8H-9"], "order_name"))
    }

    @Test
    void aKeyMatchesWhateverTypeTheColumnHolds() {
        List<Map> rows = [[id: 1], [id: 2L], [id: "3"]]
        assertEquals([[id: 2L], [id: "3"]], DatabaseExtractionSupport.keepLookupKeys(rows, ["2", "3"], "id"))
    }

    @Test
    void aKeyColumnTheQueryDoesNotReturnIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException) {
            DatabaseExtractionSupport.keepLookupKeys([[id: 1]], ["1"], "order_name")
        }
        assertTrue(e.message.contains("order_name"), e.message)
    }

    @Test
    void readingByKeyWritesOnlyThoseRows() {
        Map out = DatabaseExtractionSupport.filterExtraction(
                [dataAvailable: true, outputText: DatabaseExtractionSupport.toOutputText([[id: 1], [id: 2], [id: 3]]), recordCount: 3L, errors: [], warnings: [],
                 requestMetadata: [recordCount: 3L]], ["2"], "id")
        assertEquals(1L, out.recordCount)
        assertEquals([2], new JsonSlurper().parseText(out.outputText as String)*.id)
        assertEquals(1L, out.requestMetadata.recordCount, "the metadata counts what was written, not what the query read")
    }

    /** A read by key that finds none still writes "[]": audit re-checks and other-side reads take no file for a failure. */
    @Test
    void readingByKeyThatFindsNoneWritesAnEmptyList() {
        Map none = DatabaseExtractionSupport.filterExtraction(
                [dataAvailable: true, outputText: DatabaseExtractionSupport.toOutputText([[id: 1]]), recordCount: 1L, errors: [], warnings: [],
                 requestMetadata: [recordCount: 1L]], ["9"], "id")
        assertTrue(none.dataAvailable as boolean)
        assertEquals("[]", none.outputText)
        assertEquals(0L, none.recordCount)
        Map empty = DatabaseExtractionSupport.filterExtraction(
                [dataAvailable: false, outputText: null, recordCount: 0L, errors: [], warnings: [], requestMetadata: [recordCount: 0L]], ["9"], "id")
        assertTrue(empty.dataAvailable as boolean, "the window itself held nothing: still an answer")
        assertEquals("[]", empty.outputText)
        Map failed = DatabaseExtractionSupport.filterExtraction(
                [dataAvailable: false, outputText: null, recordCount: 0L, errors: ["boom"], warnings: []], ["9"], "id")
        assertFalse(failed.dataAvailable as boolean, "a failed read stays failed")
    }
}
