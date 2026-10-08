package io.tapstate.cloud.it.support;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bson.Document;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

/**
 * Generates and seeds the wildcard TLS certificate that real Cluster ITs serve
 * from their nodes, replacing the former Let's Encrypt issuance so the ITs no
 * longer depend on a public ACME quota.
 * <p>
 * The same certificate is reused across all tests of one class: the forked
 * application trusts it through a JVM truststore derived from the JDK default
 * {@code cacerts}, while each {@code @BeforeEach} re-inserts it into the wiped
 * control-plane store.
 */
public final class WildcardTlsFixture {

    private static final String COLLECTION = "tls_certificates";
    private static final String FALLBACK_DB = "tapstate_cloud_it";
    /** Well-known password of every JDK default cacerts keystore. */
    private static final char[] CACERTS_PASSWORD = "changeit".toCharArray();

    private WildcardTlsFixture() {
    }

    /** The generated certificate material plus helpers for trust. */
    public static final class SeededCertificate {
        private final String domain;
        private final String certificatePem;
        private final String privateKeyPem;
        private final X509Certificate certificate;
        private final Instant expiryDate;

        private SeededCertificate(String domain, String certificatePem, String privateKeyPem,
                                  X509Certificate certificate, Instant expiryDate) {
            this.domain = domain;
            this.certificatePem = certificatePem;
            this.privateKeyPem = privateKeyPem;
            this.certificate = certificate;
            this.expiryDate = expiryDate;
        }

        /** Insert this certificate into the {@code tls_certificates} collection. */
        public void seed(String mongoUri) {
            try {
                ConnectionString connectionString = new ConnectionString(mongoUri);
                String databaseName = connectionString.getDatabase();
                if (databaseName == null || databaseName.isBlank()) {
                    databaseName = FALLBACK_DB;
                }
                MongoDatabase database = MongoClients.create(connectionString).getDatabase(databaseName);
                database.getCollection(COLLECTION).insertOne(new Document()
                    .append("domain", domain)
                    .append("certificate", certificatePem)
                    .append("privateKey", privateKeyPem)
                    .append("expiryDate", Date.from(expiryDate)));
            } catch (Exception e) {
                throw new IllegalStateException("Failed to seed wildcard TLS certificate", e);
            }
        }

        /** SSLContext whose trust anchor is the generated certificate. */
        public SSLContext sslContext() {
            try {
                KeyStore trustStore = newEmptyKeyStore();
                trustStore.setCertificateEntry("tapstate-it-wildcard", certificate);
                return sslContextFor(trustStore);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to build IT SSL context", e);
            }
        }

        /**
         * Write a truststore containing every public CA from the JDK default
         * {@code cacerts} plus the generated certificate. Suitable for the
         * {@code javax.net.ssl.trustStore} system property of the forked app,
         * so its server-side health check accepts the self-signed node
         * certificate while still trusting AWS/Atlas/DNS endpoints.
         */
        public void writeJvmTrustStore(File target) {
            try {
                File cacerts = new File(System.getProperty("java.home"),
                    "lib/security/cacerts");
                KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
                try (FileInputStream in = new FileInputStream(cacerts)) {
                    trustStore.load(in, CACERTS_PASSWORD);
                }
                trustStore.setCertificateEntry("tapstate-it-wildcard", certificate);
                Files.createDirectories(target.getParentFile().toPath());
                try (OutputStream out = Files.newOutputStream(target.toPath())) {
                    trustStore.store(out, CACERTS_PASSWORD);
                }
            } catch (Exception e) {
                throw new IllegalStateException("Failed to write IT JVM truststore", e);
            }
        }

        private static KeyStore newEmptyKeyStore() throws Exception {
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            return keyStore;
        }

        private static SSLContext sslContextFor(KeyStore trustStore) throws Exception {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, tmf.getTrustManagers(), null);
            return context;
        }
    }

    /**
     * Generate a self-signed RSA certificate for {@code wildcardDomain}
     * (e.g. {@code *.dev.cloud.tapstate.com}). The result can be seeded into
     * the database and installed as a trust anchor any number of times.
     */
    public static SeededCertificate generate(String wildcardDomain) {
        try {
            Instant notBefore = Instant.now().minus(1, ChronoUnit.DAYS);
            Instant expiryDate = Instant.now().plus(30, ChronoUnit.DAYS);

            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();

            X500Name subject = new X500Name("CN=" + wildcardDomain);
            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, BigInteger.valueOf(System.nanoTime()),
                Date.from(notBefore), Date.from(expiryDate), subject, keyPair.getPublic());
            builder.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(new GeneralName(GeneralName.dNSName, wildcardDomain)));
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .build(keyPair.getPrivate());
            byte[] certDer = builder.build(signer).getEncoded();

            String certificatePem = pem("CERTIFICATE", certDer);
            String privateKeyPem = pem("PRIVATE KEY", keyPair.getPrivate().getEncoded());
            X509Certificate certificate = (X509Certificate)
                java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(
                        certificatePem.getBytes(StandardCharsets.UTF_8)));

            return new SeededCertificate(wildcardDomain, certificatePem, privateKeyPem,
                certificate, expiryDate);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate wildcard TLS certificate", e);
        }
    }

    private static String pem(String type, byte[] der) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
            .encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + base64
            + "\n-----END " + type + "-----\n";
    }
}
