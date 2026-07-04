package darpan.database

import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class JdbcUrlSupportTests {
    private static Map base(String dialect = "MYSQL") {
        [dialect: dialect, host: "db.example.internal", databaseName: "orders",
         username: "darpan_ro", password: "pw", requireTls: "Y",
         connectTimeoutSeconds: 10L, queryTimeoutSeconds: 300L]
    }

    @Test void buildsCanonicalUrlsPerDialect() {
        assertEquals("jdbc:mysql://db.example.internal:3306/orders", JdbcUrlSupport.buildUrl(base("MYSQL")))
        assertEquals("jdbc:postgresql://db.example.internal:5432/orders", JdbcUrlSupport.buildUrl(base("POSTGRES")))
        assertEquals("jdbc:db2://db.example.internal:50000/orders", JdbcUrlSupport.buildUrl(base("DB2")))
        assertEquals("jdbc:mysql://db.example.internal:3306/orders", JdbcUrlSupport.buildUrl(base(null)))   // null dialect defaults to MYSQL
    }
    @Test void explicitPortOverridesDialectDefault() {
        assertEquals("jdbc:postgresql://db.example.internal:15432/orders",
                JdbcUrlSupport.buildUrl(base("POSTGRES") + [port: 15432L]))
    }
    @Test void rejectsUnknownDialect() {
        assertThrows(IllegalArgumentException) { JdbcUrlSupport.buildUrl(base("ORACLE")) }
    }
    @Test void mysqlHardenedPropertiesAlwaysPresent() {
        Properties p = JdbcUrlSupport.connectionProperties(base("MYSQL"))
        assertEquals("false", p.getProperty("allowLoadLocalInfile"))
        assertEquals("false", p.getProperty("allowUrlInLocalInfile"))
        assertEquals("false", p.getProperty("allowMultiQueries"))
        assertNull(p.getProperty("autoDeserialize"))
        assertEquals("true", p.getProperty("useCursorFetch"))
        assertEquals("REQUIRED", p.getProperty("sslMode"))
        assertEquals("10000", p.getProperty("connectTimeout"))   // Connector/J: milliseconds
        assertEquals("300000", p.getProperty("socketTimeout"))   // Connector/J: milliseconds
        assertEquals("darpan_ro", p.getProperty("user"))
    }
    @Test void postgresProperties() {
        Properties p = JdbcUrlSupport.connectionProperties(base("POSTGRES"))
        assertEquals("require", p.getProperty("sslmode"))
        assertEquals("10", p.getProperty("connectTimeout"))      // pgjdbc: SECONDS
        assertEquals("300", p.getProperty("socketTimeout"))
        assertEquals("always", p.getProperty("readOnlyMode"))
    }
    @Test void db2Properties() {
        Properties p = JdbcUrlSupport.connectionProperties(base("DB2"))
        assertEquals("true", p.getProperty("sslConnection"))
        assertEquals("10", p.getProperty("loginTimeout"))
        assertEquals("darpan_ro", p.getProperty("user"))
    }
    @Test void tlsOffMapsPerDialect() {
        assertEquals("PREFERRED", JdbcUrlSupport.connectionProperties(base("MYSQL") + [requireTls: "N"]).getProperty("sslMode"))
        assertEquals("prefer", JdbcUrlSupport.connectionProperties(base("POSTGRES") + [requireTls: "N"]).getProperty("sslmode"))
        assertEquals("false", JdbcUrlSupport.connectionProperties(base("DB2") + [requireTls: "N"]).getProperty("sslConnection"))
    }
    @Test void readOnlySessionStatementsPerDialect() {
        assertEquals(["SET SESSION TRANSACTION READ ONLY"], JdbcUrlSupport.readOnlySessionStatements("MYSQL"))
        assertEquals(["SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY"], JdbcUrlSupport.readOnlySessionStatements("POSTGRES"))
        assertEquals([], JdbcUrlSupport.readOnlySessionStatements("DB2"))   // DB2 known → [] is valid, NOT a throw
    }
    @Test void readOnlySessionStatementsFailsClosedOnUnknownDialect() {
        assertThrows(IllegalArgumentException) { JdbcUrlSupport.readOnlySessionStatements("BADVALUE") }
    }
    @Test void testQueryPerDialect() {
        assertEquals("SELECT 1", JdbcUrlSupport.testQuery("MYSQL"))
        assertEquals("SELECT 1", JdbcUrlSupport.testQuery("POSTGRES"))
        assertEquals("SELECT 1 FROM SYSIBM.SYSDUMMY1", JdbcUrlSupport.testQuery("DB2"))
    }
    @Test void testQueryFailsClosedOnUnknownDialect() {
        assertThrows(IllegalArgumentException) { JdbcUrlSupport.testQuery("nope") }
    }
    @Test void rejectsHostWithUrlMetaCharacters() {
        ["db/evil", "db?x=1", "db&y=2", "db host", "db:3306"].each { bad ->
            assertThrows(IllegalArgumentException) { JdbcUrlSupport.buildUrl(base() + [host: bad]) }
        }
    }
    @Test void rejectsDatabaseNameWithMetaCharacters() {
        assertThrows(IllegalArgumentException) { JdbcUrlSupport.buildUrl(base() + [databaseName: "orders?autoDeserialize=true"]) }
    }
    @Test void rejectsOutOfRangePort() {
        assertThrows(IllegalArgumentException) { JdbcUrlSupport.buildUrl(base() + [port: 70000L]) }
    }
}
