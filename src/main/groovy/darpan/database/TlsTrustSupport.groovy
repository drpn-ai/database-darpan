package darpan.database

import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * DAR-BE-082. The CAs a database connection trusts when it requires TLS: the CA stored on the source config, else the
 * JVM's public roots plus the AWS RDS CA bundle (shipped as a resource; absent, public roots only). Written once per
 * distinct set as a PEM file (Postgres sslrootcert, DB2 sslCertLocation) and a PKCS12 truststore (MySQL), named by the
 * set's SHA-256, under java.io.tmpdir. Only public certificates go in, so the truststore password is not a secret.
 */
class TlsTrustSupport {
    static final String RDS_BUNDLE_RESOURCE = "darpan/database/rds-global-bundle.pem"
    static final String P12_PASSWORD = "darpan-db-trust"
    private static final Map<String, Map<String, String>> CACHE = new java.util.concurrent.ConcurrentHashMap<>()

    static Map<String, String> trustFiles(String caCertPem) {
        List<X509Certificate> certs = caCertPem?.trim() ? parse(caCertPem, true) : publicRoots()
        String pem = certs.collect { toPem(it) }.join("")
        String key = MessageDigest.getInstance("SHA-256").digest(pem.getBytes("UTF-8")).encodeHex().toString()
        return CACHE.computeIfAbsent(key) { String k -> write(k, pem, certs) }
    }

    private static Map<String, String> write(String key, String pem, List<X509Certificate> certs) {
        File dir = new File(System.getProperty("java.io.tmpdir"), "darpan-db-trust")
        dir.mkdirs()
        File pemFile = new File(dir, "${key}.pem")
        File p12File = new File(dir, "${key}.p12")
        pemFile.text = pem
        KeyStore ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        certs.eachWithIndex { X509Certificate c, int i -> ks.setCertificateEntry("ca-${i}".toString(), c) }
        p12File.withOutputStream { ks.store(it, P12_PASSWORD.toCharArray()) }
        return [pemPath: pemFile.absolutePath, p12Path: p12File.absolutePath, p12Password: P12_PASSWORD]
    }

    /** The JVM's default trusted roots, plus the AWS RDS bundle when it is shipped. */
    static List<X509Certificate> publicRoots() {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init((KeyStore) null)
        List<X509Certificate> roots = (tmf.trustManagers.find { it instanceof X509TrustManager } as X509TrustManager).acceptedIssuers as List
        InputStream rds = TlsTrustSupport.classLoader.getResourceAsStream(RDS_BUNDLE_RESOURCE)
        if (rds != null) rds.withCloseable { roots = roots + parse(it.getText("UTF-8"), false) }
        return roots
    }

    private static List<X509Certificate> parse(String pem, boolean stored) {
        try {
            List<X509Certificate> certs = CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(pem.trim().getBytes("UTF-8"))) as List<X509Certificate>
            if (!certs) throw new IllegalArgumentException("no certificate")
            return certs
        } catch (Exception e) {
            if (!stored) throw e
            throw new IllegalArgumentException("The CA certificate for this database could not be read; paste the PEM text " +
                    "(-----BEGIN CERTIFICATE----- ...) or clear it to use the public certificate authorities.")
        }
    }

    private static String toPem(X509Certificate c) {
        return "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".bytes).encodeToString(c.encoded) + "\n-----END CERTIFICATE-----\n"
    }
}
