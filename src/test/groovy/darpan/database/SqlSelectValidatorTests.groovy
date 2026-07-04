package darpan.database

import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class SqlSelectValidatorTests {
    private static boolean ok(String sql) { SqlSelectValidator.validate(sql).isEmpty() }

    @Test void acceptsPlainSelect() { assertTrue(ok("SELECT id, total FROM orders")) }
    @Test void acceptsWindowedSelect() {
        assertTrue(ok("SELECT o.id, o.total FROM orders o WHERE o.updated_at BETWEEN :windowStart AND :windowEnd"))
    }
    @Test void acceptsJoinsAndCte() {
        assertTrue(ok("WITH r AS (SELECT order_id, SUM(amount) amt FROM refunds GROUP BY order_id) " +
                "SELECT o.id, r.amt FROM orders o LEFT JOIN r ON r.order_id = o.id"))
    }
    @Test void acceptsKeywordsInsideStringLiterals() {
        assertTrue(ok("SELECT id FROM orders WHERE status = 'DROPPED' AND note != 'do not UPDATE'"))
    }

    @Test void rejectsNonSelectFirstKeyword() { assertFalse(ok("UPDATE orders SET total = 0")) }
    @Test void rejectsSecondStatement() { assertFalse(ok("SELECT 1; DROP TABLE orders")) }
    @Test void rejectsTrailingSemicolon() { assertFalse(ok("SELECT 1;")) }
    @Test void rejectsInto() { assertFalse(ok("SELECT * FROM orders INTO OUTFILE '/tmp/x'")) }
    @Test void rejectsForUpdate() { assertFalse(ok("SELECT id FROM orders FOR UPDATE")) }
    @Test void rejectsLockInShareMode() { assertFalse(ok("SELECT id FROM orders LOCK IN SHARE MODE")) }
    @Test void rejectsDeniedTokenEvenNested() { assertFalse(ok("SELECT (CALL something()) FROM dual")) }
    @Test void rejectsUnknownNamedPlaceholder() { assertFalse(ok("SELECT id FROM orders WHERE tenant = :tenantId")) }
    @Test void rejectsUserVariables() { assertFalse(ok("SELECT @rownum := 1 FROM orders")) }
    @Test void rejectsCommentSmuggledKeyword() { assertFalse(ok("SELECT 1 /* x */ ; DELETE FROM orders -- y")) }
    @Test void rejectsPostgresCopy() { assertFalse(ok('COPY orders TO \'/tmp/x\'')) }
    @Test void rejectsPostgresDoBlock() { assertFalse(ok('DO $x$ BEGIN NULL END $x$')) }
    @Test void rejectsBlankSql() { assertFalse(ok("   ")) }

    // --- Golden witnesses for the comment/quote-order and executable-comment bypasses ---

    @Test void rejectsCommentQuoteOrderBypassIntoOutfile() {
        assertFalse(ok("SELECT * FROM orders -- '\nINTO OUTFILE '/tmp/x.csv' -- '"))
    }

    @Test void rejectsCommentQuoteOrderBypassForUpdate() {
        assertFalse(ok("SELECT id FROM orders WHERE x = 1 -- '\nFOR UPDATE -- '"))
    }

    @Test void rejectsExecutableVersionComment() {
        assertFalse(ok("SELECT 1 /*!50000 ; DROP TABLE orders*/"))
    }

    // --- Unterminated constructs must fail CLOSED, never silently swallow trailing SQL ---

    @Test void rejectsUnterminatedStringLiteral() {
        assertFalse(ok("SELECT * FROM orders WHERE note = 'unterminated ; DROP TABLE orders"))
    }

    @Test void rejectsUnterminatedBlockComment() {
        assertFalse(ok("SELECT * FROM orders /* unterminated ; DROP TABLE orders"))
    }

    // --- Defensive cases: comment-like sequences inside real string literals must stay inert ---

    @Test void acceptsDoubleDashInsideStringLiteral() {
        assertTrue(ok("SELECT id FROM t WHERE note = 'a -- b'"))
    }

    @Test void acceptsBlockCommentOpenerInsideStringLiteral() {
        assertTrue(ok("SELECT id FROM t WHERE note = 'a /* b'"))
    }
}
