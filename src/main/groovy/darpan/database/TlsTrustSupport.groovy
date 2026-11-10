package darpan.database

import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * DAR-BE-082. The CAs a database connection trusts when it requires TLS: the CA stored on the source config, else the
 * JVM's public roots plus the AWS RDS CA bundle (shipped as a resource; absent, public roots only). Written once per
 * distinct set as a PEM file (Postgres sslrootcert, DB2 sslCertLocation) and a PKCS12 truststore (MySQL), named by the
 * set's SHA-256, in an owner-only temp directory made once per JVM. Only public certificates go in, so the truststore
 * password is not a secret.
 */
class TlsTrustSupport {
    static final String RDS_BUNDLE_RESOURCE = "darpan/database/rds-global-bundle.pem"
    static final String P12_PASSWORD = "darpan-db-trust"
    private static final Map<String, Map<String, String>> CACHE = [:]

    static Map<String, String> trustFiles(String caCertPem) {
        List<X509Certificate> certs = caCertPem?.trim() ? parse(caCertPem, true) : publicRoots()
        String pem = certs.collect { toPem(it) }.join("")
        String key = sha256(pem.getBytes("UTF-8"))
        synchronized (CACHE) {
            Map<String, String> cached = CACHE[key]
            // A cached path is handed out only while its files are still what was written (review: never trust a file
            // that changed on disk); otherwise they are written again.
            if (cached != null && intact(cached)) return cached
            Map<String, String> written = write(key, pem, certs)
            CACHE[key] = written
            return written
        }
    }

    /**
     * An owner-only directory with a random name, made once per JVM (review: a fixed name in the shared temp dir could be
     * pre-created or symlinked by another local user, who could then plant a CA the driver trusts).
     */
    private static synchronized Path dir() {
        if (trustDir == null || !Files.isDirectory(trustDir, LinkOption.NOFOLLOW_LINKS)) {
            try {
                trustDir = Files.createTempDirectory("darpan-db-trust-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            } catch (UnsupportedOperationException ignored) {
                trustDir = Files.createTempDirectory("darpan-db-trust-")
            }
        }
        return trustDir
    }
    private static Path trustDir

    private static Map<String, String> write(String key, String pem, List<X509Certificate> certs) {
        Path d = dir()
        KeyStore ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        certs.eachWithIndex { X509Certificate c, int i -> ks.setCertificateEntry("ca-${i}".toString(), c) }
        ByteArrayOutputStream p12 = new ByteArrayOutputStream()
        ks.store(p12, P12_PASSWORD.toCharArray())
        Path pemPath = place(d, "${key}.pem", pem.getBytes("UTF-8"))
        Path p12Path = place(d, "${key}.p12", p12.toByteArray())
        return [pemPath: pemPath.toString(), p12Path: p12Path.toString(), p12Password: P12_PASSWORD,
                pemSha: sha256(pem.getBytes("UTF-8")), p12Sha: sha256(p12.toByteArray())]
    }

    /** Written to a fresh, never-existing file, then moved into place (no symlink is followed, nothing half-written is read). */
    private static Path place(Path d, String name, byte[] bytes) {
        Path tmp = Files.createTempFile(d, "w-", ".tmp")
        Files.write(tmp, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)
        return Files.move(tmp, d.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private static boolean intact(Map<String, String> files) {
        try {
            Path pem = Paths.get(files.pemPath), p12 = Paths.get(files.p12Path)
            if (!Files.isRegularFile(pem, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(p12, LinkOption.NOFOLLOW_LINKS)) return false
            return sha256(Files.readAllBytes(pem)) == files.pemSha && sha256(Files.readAllBytes(p12)) == files.p12Sha
        } catch (IOException ignored) {
            return false
        }
    }

    private static String sha256(byte[] bytes) { return MessageDigest.getInstance("SHA-256").digest(bytes).encodeHex().toString() }

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
