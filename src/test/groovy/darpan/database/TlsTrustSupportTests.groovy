package darpan.database

import org.junit.jupiter.api.Test

import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

import static org.junit.jupiter.api.Assertions.*

/** DAR-BE-082: a database connection that requires TLS checks who the server is, not only that the link is encrypted. */
class TlsTrustSupportTests {
    static final String TEST_CA = '''-----BEGIN CERTIFICATE-----
MIIDEzCCAfugAwIBAgIUZpgvkc3UqtpSbSOuRkNQJjnvEWYwDQYJKoZIhvcNAQEL
BQAwGTEXMBUGA1UEAwwORGFycGFuIFRlc3QgQ0EwHhcNMjYxMTEwMTExMDE5WhcN
MzYxMTA3MTExMDE5WjAZMRcwFQYDVQQDDA5EYXJwYW4gVGVzdCBDQTCCASIwDQYJ
KoZIhvcNAQEBBQADggEPADCCAQoCggEBAK2/ABKiUPWO1ZUF9VkhNBDNdqgQUOe2
5688FZ32F6kmllxOuTPXO9T9VcFJg3k/aO4AkRbItOy1+/eloJpk3lvi/C5H3KhJ
QhRnbJ8fYXVMXcizqFrrcrbyGMq8vYrcLu9ZiR+QBDlVu8LAbIzU8ea1ohFB8G/6
1s/XYIpQHRvNF+FlpGpDKdcZuDvTBFTNqQxeQlXLJzokQ2UNzG7mFA9p2z/anlbs
p+bP/48vDMe+qaYFEql7kwuvpioH0M/773IWHbIKlyyxER113xblOxQ2GMru80Sc
exH1Nd1Bw1+oX/c8Oud53d0UxY+Jq601t91zdisI983ZlrdkxHoUs70CAwEAAaNT
MFEwHQYDVR0OBBYEFCmvTry0fatDk0m1ZOrvvqwy7YOJMB8GA1UdIwQYMBaAFCmv
Try0fatDk0m1ZOrvvqwy7YOJMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQEL
BQADggEBAIeURFB0G9Ssh8HENQ+eyY7/Q3LT7WblTG+QkcyfuDuzb4+EmnlGrOYK
6SW7I4Q6gZ8cMBDHb0HS8dbim2pVvhHPQgYJXP9HQiEI73A2guXyE7pQ2+8+ZLjh
J37xkjcPNc/AgBZW6wJDjuGKuWb5p78mgwXv/Bybye52cHDD9i21hY7SxNyk6oid
AC/Jl0liHle6WJwSlvsWTsfCVUfACmAQyTietbRfrBtcQuqQTn1DFuUlWpRrAdS1
A2k6yH1VOZmWeTRRVkIvtaY9sOCR/ZqaiSoWpDUjhoCyMJEMeDyOsbj70aEMTSD/
9d5TchIgOsWJbM5jayXvYx51L4mtuo8=
-----END CERTIFICATE-----'''

    static Map base(String dialect) { [dialect: dialect, host: "db.example.com", databaseName: "orders", username: "u", password: "p"] }

    static List<X509Certificate> certsIn(String path) {
        new File(path).withInputStream { CertificateFactory.getInstance("X.509").generateCertificates(it) as List<X509Certificate> }
    }

    @Test
    void aStoredCaIsTheOnlyCaTrusted() {
        Map files = TlsTrustSupport.trustFiles(TEST_CA)
        assertEquals(["CN=Darpan Test CA"], certsIn(files.pemPath as String)*.subjectX500Principal*.name)
        KeyStore ks = KeyStore.getInstance("PKCS12")
        new File(files.p12Path as String).withInputStream { ks.load(it, (files.p12Password as String).toCharArray()) }
        assertEquals(1, ks.size())
    }

    @Test
    void withNoStoredCaThePublicRootsAreTrusted() {
        int n = certsIn(TlsTrustSupport.trustFiles(null).pemPath as String).size()
        assertTrue(n > 50, "the JVM public roots (and the AWS RDS bundle when shipped): " + n)
    }

    @Test
    void eachDriverChecksTheServersIdentity() {
        Properties my = JdbcUrlSupport.connectionProperties(base("MYSQL") + [caCertPem: TEST_CA])
        assertEquals("VERIFY_IDENTITY", my.getProperty("sslMode"))
        assertTrue(my.getProperty("trustCertificateKeyStoreUrl").startsWith("file:"))
        assertEquals("PKCS12", my.getProperty("trustCertificateKeyStoreType"))
        Properties pg = JdbcUrlSupport.connectionProperties(base("POSTGRES"))
        assertEquals("verify-full", pg.getProperty("sslmode"))
        assertTrue(new File(pg.getProperty("sslrootcert")).exists())
        Properties db2 = JdbcUrlSupport.connectionProperties(base("DB2"))
        assertEquals("true", db2.getProperty("sslConnection"))
        assertTrue(new File(db2.getProperty("sslCertLocation")).exists())
        assertEquals("BASIC", db2.getProperty("sslHostnameValidation"))
    }

    @Test
    void tlsOffStaysAsItWas() {
        assertNull(JdbcUrlSupport.connectionProperties(base("MYSQL") + [requireTls: "N"]).getProperty("trustCertificateKeyStoreUrl"))
        assertNull(JdbcUrlSupport.connectionProperties(base("POSTGRES") + [requireTls: "N"]).getProperty("sslrootcert"))
    }

    /** Review of DAR-BE-082: the trust files live in an owner-only directory and a tampered file is rewritten, not trusted. */
    @Test
    void theTrustFilesAreOwnerOnlyAndATamperedFileIsRewritten() {
        Map files = TlsTrustSupport.trustFiles(TEST_CA)
        java.nio.file.Path dir = new File(files.pemPath as String).toPath().parent
        assertFalse(dir.fileName.toString() == "darpan-db-trust", "not a fixed, guessable name in the shared temp dir")
        assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(dir)))
        new File(files.pemPath as String).text = "-----BEGIN CERTIFICATE-----\nplanted\n-----END CERTIFICATE-----\n"
        Map again = TlsTrustSupport.trustFiles(TEST_CA)
        assertEquals(["CN=Darpan Test CA"], certsIn(again.pemPath as String)*.subjectX500Principal*.name)
    }

    @Test
    void aBadStoredCaIsRefusedInWords() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException) { TlsTrustSupport.trustFiles("not a certificate") }
        assertTrue(e.message.startsWith("The CA certificate for this database could not be read"), e.message)
    }
}
