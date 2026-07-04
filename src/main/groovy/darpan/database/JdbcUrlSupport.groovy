package darpan.database

/** Server-side JDBC URL/property builder — the single home for all dialect knowledge. Raw URLs are
 * never accepted from users because URL parameters can enable dangerous driver features
 * (allowLoadLocalInfile, autoDeserialize, ...). */
class JdbcUrlSupport {
    private static final def HOST_PATTERN = ~/^[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?$/
    private static final def DB_PATTERN = ~/^[A-Za-z0-9_$-]+$/
    private static final Map<String, Map> DIALECTS = [
            MYSQL   : [prefix: "jdbc:mysql://", defaultPort: 3306L],
            POSTGRES: [prefix: "jdbc:postgresql://", defaultPort: 5432L],
            DB2     : [prefix: "jdbc:db2://", defaultPort: 50000L],
    ]

    static String dialectOf(Map config) {
        String d = (config.dialect ?: "MYSQL").toString().trim().toUpperCase()
        if (!DIALECTS.containsKey(d)) throw new IllegalArgumentException("Unsupported dialect '${d}' (supported: ${DIALECTS.keySet().join(', ')})")
        return d
    }

    static String buildUrl(Map config) {
        Map d = DIALECTS[dialectOf(config)]
        String host = requireMatch(config.host?.toString()?.trim(), HOST_PATTERN, "host")
        String db = requireMatch(config.databaseName?.toString()?.trim(), DB_PATTERN, "databaseName")
        long port = (config.port ?: d.defaultPort) as long
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range: ${port}")
        return "${d.prefix}${host}:${port}/${db}"
    }

    static Properties connectionProperties(Map config) {
        String dialect = dialectOf(config)
        boolean tls = "Y".equalsIgnoreCase(config.requireTls?.toString() ?: "Y")
        long connectSeconds = (config.connectTimeoutSeconds ?: 10L) as long
        long socketSeconds = (config.queryTimeoutSeconds ?: 300L) as long
        Properties p = new Properties()
        p.setProperty("user", config.username?.toString() ?: "")
        p.setProperty("password", config.password?.toString() ?: "")
        switch (dialect) {
            case "MYSQL":
                p.setProperty("allowLoadLocalInfile", "false")
                p.setProperty("allowUrlInLocalInfile", "false")
                p.setProperty("allowMultiQueries", "false")
                p.setProperty("useCursorFetch", "true")
                p.setProperty("sslMode", tls ? "REQUIRED" : "PREFERRED")
                p.setProperty("connectTimeout", String.valueOf(connectSeconds * 1000L))   // Connector/J: ms
                p.setProperty("socketTimeout", String.valueOf(socketSeconds * 1000L))
                break
            case "POSTGRES":
                p.setProperty("sslmode", tls ? "require" : "prefer")
                p.setProperty("connectTimeout", String.valueOf(connectSeconds))           // pgjdbc: seconds
                p.setProperty("socketTimeout", String.valueOf(socketSeconds))
                p.setProperty("readOnlyMode", "always")   // pgjdbc-side read-only enforcement
                break
            case "DB2":
                p.setProperty("sslConnection", tls ? "true" : "false")
                p.setProperty("loginTimeout", String.valueOf(connectSeconds))             // jcc: seconds
                break
        }
        return p
    }

    /** Session-level read-only statements executed right after connect (first fence; the second is the
     * documented SELECT-only grant). Db2 LUW has no session-level statement — grant + setReadOnly only. */
    static List<String> readOnlySessionStatements(String dialect) {
        switch (dialect) {
            case "MYSQL": return ["SET SESSION TRANSACTION READ ONLY"]
            case "POSTGRES": return ["SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY"]
            default: return []
        }
    }

    static String testQuery(String dialect) {
        return dialect == "DB2" ? "SELECT 1 FROM SYSIBM.SYSDUMMY1" : "SELECT 1"
    }

    private static String requireMatch(String value, def pattern, String label) {
        if (!value || !(value ==~ pattern)) throw new IllegalArgumentException("Invalid ${label}: '${value}'")
        return value
    }
}
