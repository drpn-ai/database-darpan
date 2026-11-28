package darpan.database

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

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
    /** probe#DatabaseConnection's read check: long enough for a cold server, short enough for a diagnostics popup. */
    static final int PROBE_QUERY_TIMEOUT_SECONDS = 10

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
        return fenceReadOnly(conn, JdbcUrlSupport.dialectOf(config))
    }

    /** The read-only fence on an open connection: setReadOnly(true), setAutoCommit(false), then the dialect's session statements. */
    static Connection fenceReadOnly(Connection conn, String dialect) {
        try {
            conn.setReadOnly(true)
            conn.setAutoCommit(false)
            for (String stmtSql : JdbcUrlSupport.readOnlySessionStatements(dialect)) {
                java.sql.Statement stmt = conn.createStatement()
                try { stmt.execute(stmtSql) } finally { stmt.close() }
            }
            return conn
        } catch (Exception e) {
            // A connection the fence could not close down is never handed out, and never left open.
            try { conn.close() } catch (Exception ignored) { }
            throw e
        }
    }

    /**
     * Connection diagnostics for probe#DatabaseConnection: the rows test#SourceConnection shows (Address, Connection, Read
     * access) and ok. connect(url, properties) opens the connection (DriverManager in production). Never throws.
     */
    static Map probeChecks(Map config, Closure<Connection> connect) {
        List checks = []
        boolean ok = false
        String url = null
        try {
            url = JdbcUrlSupport.buildUrl(config)
            checks << [name: "Address", status: "PASS", detail: url]
        } catch (IllegalArgumentException e) {
            checks << [name: "Address", status: "FAIL", detail: e.message]
        }
        if (url) {
            Connection conn = null
            java.sql.Statement stmt = null
            try {
                conn = connect.call(url, JdbcUrlSupport.connectionProperties(config))
                checks << [name: "Connection", status: "PASS", detail: "Connected."]
                stmt = conn.createStatement()
                stmt.setQueryTimeout(PROBE_QUERY_TIMEOUT_SECONDS)
                // OC202: the dialect's own test query; Db2 refuses a bare SELECT 1
                String testQuery = JdbcUrlSupport.testQuery(JdbcUrlSupport.dialectOf(config))
                boolean readable = stmt.execute(testQuery)
                checks << [name: "Read access", status: readable ? "PASS" : "FAIL", detail: testQuery]
                ok = readable
            } catch (Exception e) {
                checks << [name: "Connection", status: "FAIL", detail: e.message]
            } finally {
                try { stmt?.close() } catch (Exception ignored) {}
                try { conn?.close() } catch (Exception ignored) {}
            }
        }
        return [ok: ok, checks: checks]
    }

    /** Full extraction against the configured database (MySQL, PostgreSQL or Db2). Kept free of Moqui APIs so it is unit-testable. */
    static Map runExtraction(Map config, Map query, Timestamp windowStart, Timestamp windowEnd) {
        List warnings = []
        List<String> validation = SqlSelectValidator.validate(query.sqlSelect as String)
        if (validation) return failure(validation, warnings)

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

    /**
     * DAR-BE-386: the rows whose keyField value is one of keys (compared as text, whatever type the column holds). The
     * query still reads its own window; keys only narrow it, and never enter the SQL. A keyField no row carries is
     * refused, so a pulse never takes a query without that column for "the target holds none of these".
     */
    static List<Map> keepLookupKeys(List<Map> rows, Collection keys, String keyField) {
        if (!keyField) throw new IllegalArgumentException("A read by key needs the key field.")
        Set<String> wanted = (keys ?: []).collect { it?.toString()?.trim() }.findAll { it } as Set<String>
        if (rows && !rows.any { it.containsKey(keyField) }) {
            throw new IllegalArgumentException("The query returns no ${keyField} column to read by key.".toString())
        }
        return rows.findAll { Map r -> wanted.contains(r.get(keyField)?.toString()?.trim()) }
    }

    /**
     * DAR-BE-386: an extraction narrowed to lookupKeys (keepLookupKeys). A read by key always answers with a list, "[]" when
     * none match: an audit re-check and a read by the other side's keys take a missing file for a failed read. A failed
     * extraction is returned as is.
     */
    static Map filterExtraction(Map extraction, Collection keys, String keyField) {
        if (extraction.errors) return extraction
        List<Map> read = extraction.outputText ? (new JsonSlurper().parseText(extraction.outputText as String) as List<Map>) : []
        List<Map> rows = keepLookupKeys(read, keys, keyField)
        Map metadata = (extraction.requestMetadata ?: [:]) + [recordCount: (long) rows.size()]
        return extraction + [dataAvailable: true, outputText: toOutputText(rows), recordCount: (long) rows.size(), requestMetadata: metadata]
    }

    /**
     * Why a query and its connection may not be used together, or null when they may: the connection must exist and
     * both must carry the same non-blank tenant (companyUserGroupId is nullable on both, and null==null is no tenant).
     * Shared by extract#DatabaseRecords and preview#DatabaseQuery.
     */
    static String queryConfigMismatch(def queryRow, def configRow) {
        String queryTenant = queryRow?.get("companyUserGroupId")?.toString()?.trim()
        String configTenant = configRow?.get("companyUserGroupId")?.toString()?.trim()
        if (configRow == null) return "Database connection ${queryRow?.get('databaseSourceConfigId')} not found.".toString()
        if (!queryTenant || !configTenant) return "Database source query or connection is missing tenant scope."
        if (configTenant != queryTenant) return "Database connection and query belong to different tenants."
        return null
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
