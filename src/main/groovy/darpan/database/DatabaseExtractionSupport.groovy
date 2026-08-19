package darpan.database

import groovy.json.JsonOutput

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Timestamp
import java.util.regex.Matcher
import java.util.regex.Pattern

class DatabaseExtractionSupport {
    private static final Pattern NAMED_PLACEHOLDER = Pattern.compile(/:(windowStart|windowEnd)\b/)

    /** Replace :windowStart/:windowEnd with '?' preserving order of appearance. A placeholder token
     * that falls inside a string literal or comment is left untouched — it is text data, not a real
     * bind marker, so replacing it would desync bindNames from the true '?' count and later throw an
     * opaque parameter-index error out of PreparedStatement.setTimestamp. SqlSelectValidator strips
     * literals/comments before its own checks, so such a query still passes validation; this method
     * must independently recognize the same regions (via the shared SqlSelectValidator.literalAndCommentSpans
     * scan) rather than blindly regex-replacing the raw SQL. */
    static Map prepareStatementSql(String sql) {
        List<int[]> literalSpans = SqlSelectValidator.literalAndCommentSpans(sql)
        List<String> bindNames = []
        Matcher m = NAMED_PLACEHOLDER.matcher(sql)
        StringBuffer sb = new StringBuffer()
        while (m.find()) {
            int start = m.start()
            boolean insideLiteral = literalSpans.any { int[] span -> start >= span[0] && start < span[1] }
            if (insideLiteral) continue   // leave literal/comment text exactly as-is; not a real bind marker
            bindNames << m.group(1)
            m.appendReplacement(sb, "?")
        }
        m.appendTail(sb)
        return [sql: sb.toString(), bindNames: bindNames]
    }

    /**
     * @param capIsLimit false (default): exceeding maxRows FAILS the extraction with zero rows —
     *        reconciliation correctness demands fail-not-truncate, since a partial extract
     *        manufactures false discrepancies for every record past the cap. Every reconciliation
     *        extraction call site relies on this and must keep omitting this parameter.
     *        true: the cap is a silent LIMIT — stop reading at maxRows and return exactly those rows
     *        with no error. Only the query-preview facade (10-row cap) may pass true.
     */
    static Map extractRows(Connection conn, String sql, Map<String, Timestamp> binds, long maxRows, long queryTimeoutSeconds, boolean capIsLimit = false) {
        Map prepared = prepareStatementSql(sql)
        List<Map> rows = []
        PreparedStatement ps = conn.prepareStatement(prepared.sql as String, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)
        try {
            ps.setQueryTimeout((int) queryTimeoutSeconds)
            ps.setFetchSize(1000)
            List<String> bindNames = prepared.bindNames as List<String>
            for (int i = 0; i < bindNames.size(); i++) ps.setTimestamp(i + 1, binds[bindNames[i]])
            ResultSet rs = ps.executeQuery()
            ResultSetMetaData md = rs.getMetaData()
            int cols = md.getColumnCount()
            while (rs.next()) {
                if (rows.size() >= maxRows) {
                    if (capIsLimit) break   // silent LIMIT: stop reading, return exactly maxRows rows
                    // Reconciliation correctness demands fail-not-truncate: a partial extract
                    // manufactures false discrepancies for every record past the cap.
                    return [rows: [], recordCount: 0L,
                            errors: ["Extraction exceeded maxRows cap (${maxRows}). Narrow the query window or raise maxRows on the connection config." as String]]
                }
                Map row = new LinkedHashMap()
                for (int c = 1; c <= cols; c++) row.put(md.getColumnLabel(c), rs.getObject(c))
                rows << row
            }
        } finally {
            ps.close()
        }
        return [rows: rows, recordCount: (long) rows.size(), errors: []]
    }

    static String toOutputText(List<Map> rows) { return JsonOutput.toJson(rows) }

    /** Opens a JDBC connection with the full read-only fence: setReadOnly(true), setAutoCommit(false)
     * (transactional READ ONLY on MySQL; required for pg fetchSize streaming), then the dialect's
     * readOnlySessionStatements. Shared by runExtraction and the query-preview facade
     * (previewDatabaseQuery.groovy) so both read paths are equally hardened — neither may open a raw
     * DriverManager connection and skip a step of this fence. */
    static Connection openReadOnlyConnection(Map config) {
        Connection conn = DriverManager.getConnection(JdbcUrlSupport.buildUrl(config),
                JdbcUrlSupport.connectionProperties(config))
        conn.setReadOnly(true)
        conn.setAutoCommit(false)
        for (String stmtSql : JdbcUrlSupport.readOnlySessionStatements(JdbcUrlSupport.dialectOf(config))) {
            conn.createStatement().execute(stmtSql)
        }
        return conn
    }

    /** Full extraction against the configured MySQL database. Kept free of Moqui APIs so it is unit-testable. */
    static Map runExtraction(Map config, Map query, Timestamp windowStart, Timestamp windowEnd) {
        List warnings = []
        List<String> validation = SqlSelectValidator.validate(query.sqlSelect as String)
        if (validation) return failure(validation, warnings)
        if (config.caCertPem) warnings << "caCertPem is configured but CA pinning is not yet enforced (encryption-required TLS mode used)."

        String sql = query.sqlSelect as String
        if (!sql.contains(":windowStart") && !sql.contains(":windowEnd")) {
            warnings << "Query uses no :windowStart/:windowEnd placeholders; every run re-extracts the full dataset."
        }
        long startedAt = System.currentTimeMillis()
        Connection conn = null
        try {
            conn = openReadOnlyConnection(config)
            Map result = extractRows(conn, sql,
                    [windowStart: windowStart, windowEnd: windowEnd],
                    (config.maxRows ?: 500000L) as long, (config.queryTimeoutSeconds ?: 300L) as long)
            if (result.errors) return failure(result.errors as List<String>, warnings)
            List<Map> rows = result.rows as List<Map>
            return [dataAvailable: !rows.isEmpty(), outputText: rows.isEmpty() ? null : toOutputText(rows),
                    recordCount: result.recordCount, warnings: warnings, errors: [],
                    requestMetadata: metadata(config, sql, result.recordCount as long, startedAt)]
        } catch (Exception e) {
            return failure(["Database extraction failed: ${e.class.simpleName}: ${e.message}" as String], warnings)
        } finally {
            if (conn != null) {
                try { conn.commit() } catch (Exception ignored) { }   // close out the read-only transaction
                try { conn.close() } catch (Exception ignored) { }
            }
        }
    }

    private static Map failure(List<String> errors, List warnings) {
        return [dataAvailable: false, outputText: null, recordCount: 0L, warnings: warnings, errors: errors, requestMetadata: [:]]
    }

    private static Map metadata(Map config, String sql, long recordCount, long startedAt) {
        // Sanitized diagnostics only — never credentials, never the full row payload.
        return [host: config.host, databaseName: config.databaseName, recordCount: recordCount,
                durationMs: System.currentTimeMillis() - startedAt,
                sqlSha256: java.security.MessageDigest.getInstance("SHA-256").digest(sql.getBytes("UTF-8")).encodeHex().toString()]
    }
}
