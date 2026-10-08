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
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
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
 */
public final class WildcardTlsFixture {

    private static final String COLLECTION = "tls_certificates";
    private static final String FALLBACK_DB = "tapstate_cloud_it";

    private WildcardTlsFixture() {
    }

    /** The seeded certificate material plus an SSLContext trusting it. */
    public static final class SeededCertificate {
        private final String certificatePem;
        private final String privateKeyPem;
        private final X509Certificate certificate;
        private final Instant expiryDate;

        private SeededCertificate(String certificatePem, String privateKeyPem,
                                  X509Certificate certificate, Instant expiryDate) {
            this.certificatePem = certificatePem;
            this.privateKeyPem = privateKeyPem;
            this.certificate = certificate;
            this.expiryDate = expiryDate;
        }

        /** SSLContext whose trust anchor is the generated certificate. */
        public SSLContext sslContext() {
            try {
                KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
                trustStore.load(null, null);
                trustStore.setCertificateEntry("tapstate-it-wildcard", certificate);
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(trustStore);
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, tmf.getTrustManagers(), null);
                return context;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to build IT SSL context", e);
            }
        }
    }

    /**
     * Generate a self-signed RSA certificate for {@code wildcardDomain}
     * (e.g. {@code *.dev.cloud.tapstate.com}) and insert it into the
     * {@code tls_certificates} collection of the control-plane store.
     */
    public static SeededCertificate seed(String mongoUri, String wildcardDomain) {
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

            ConnectionString connectionString = new ConnectionString(mongoUri);
            String databaseName = connectionString.getDatabase();
            if (databaseName == null || databaseName.isBlank()) {
                databaseName = FALLBACK_DB;
            }
            MongoDatabase database = MongoClients.create(connectionString).getDatabase(databaseName);
            database.getCollection(COLLECTION).insertOne(new Document()
                .append("domain", wildcardDomain)
                .append("certificate", certificatePem)
                .append("privateKey", privateKeyPem)
                .append("expiryDate", Date.from(expiryDate)));

            return new SeededCertificate(certificatePem, privateKeyPem, certificate, expiryDate);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to seed wildcard TLS certificate", e);
        }
    }

    private static String pem(String type, byte[] der) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
            .encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + base64
            + "\n-----END " + type + "-----\n";
    }
}
