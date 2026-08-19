package darpan.database

/** Server-side JDBC URL/property builder — the single home for all dialect knowledge. Raw URLs are
 * never accepted from users because URL parameters can enable dangerous driver features
 * (allowLoadLocalInfile, autoDeserialize, ...). */
import java.net.InetAddress
import java.net.UnknownHostException

class JdbcUrlSupport {

    /** Cloud instance-metadata address. Reachable from many hosts and never a legitimate DB. */
    private static final String METADATA_ADDRESS = "169.254.169.254"
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

    /** Normalize + validate a bare dialect code. Fails CLOSED: an unknown/un-normalized dialect throws
     * rather than silently falling through to a permissive default. */
    private static String requireDialect(String dialect) {
        String d = dialect?.trim()?.toUpperCase()
        if (!d || !DIALECTS.containsKey(d)) throw new IllegalArgumentException("Unsupported dialect '${dialect}' (supported: ${DIALECTS.keySet().join(', ')})")
        return d
    }

    /**
     * Reject hosts that resolve to non-routable space. Without this, a tenant-supplied host turns
     * the Darpan server into an internal port scanner: connect latency and error text alone reveal
     * what is listening. Fails CLOSED on an unresolvable host — an unknown host is not a safe host.
     */
    static void assertHostAllowed(String host, boolean allowPrivateHosts) {
        if (allowPrivateHosts) return
        InetAddress[] resolved
        try {
            resolved = InetAddress.getAllByName(host)
        } catch (UnknownHostException ignored) {
            throw new IllegalArgumentException("Cannot resolve host '${host}'.")
        }
        for (InetAddress address : resolved) {
            if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isAnyLocalAddress() || address.isMulticastAddress()
                    || METADATA_ADDRESS == address.hostAddress) {
                throw new IllegalArgumentException("Host '${host}' resolves to a non-routable or internal " +
                        "address (${address.hostAddress}) and cannot be used as a database source.")
            }
        }
    }

    /**
     * The deployment's private-host policy, in ONE place. Callers used to each re-read this property,
     * which meant a caller that forgot silently got the wrong default - and the env-gated integration
     * tests, which deliberately target loopback containers, called buildUrl(config) and were rejected.
     */
    static boolean privateHostsAllowed() {
        return "true".equalsIgnoreCase(System.getProperty("database_source_allow_private_hosts") ?: "false")
    }

    static String buildUrl(Map config, boolean allowPrivateHosts = privateHostsAllowed()) {
        Map d = DIALECTS[dialectOf(config)]
        String host = requireMatch(config.host?.toString()?.trim(), HOST_PATTERN, "host")
        String db = requireMatch(config.databaseName?.toString()?.trim(), DB_PATTERN, "databaseName")
        long port = (config.port ?: d.defaultPort) as long
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range: ${port}")
        // DNS resolution is deliberately LAST: every cheap syntactic check runs first, so a malformed
        // databaseName or port fails for its own reason instead of being masked by a lookup failure.
        assertHostAllowed(host, allowPrivateHosts)
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
        switch (requireDialect(dialect)) {
            case "MYSQL": return ["SET SESSION TRANSACTION READ ONLY"]
            case "POSTGRES": return ["SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY"]
            default: return []   // DB2 LUW: known dialect, no session-level statement — [] is a VALID result
        }
    }

    static String testQuery(String dialect) {
        return requireDialect(dialect) == "DB2" ? "SELECT 1 FROM SYSIBM.SYSDUMMY1" : "SELECT 1"
    }

    private static String requireMatch(String value, def pattern, String label) {
        if (!value || !(value ==~ pattern)) throw new IllegalArgumentException("Invalid ${label}: '${value}'")
        return value
    }
}
