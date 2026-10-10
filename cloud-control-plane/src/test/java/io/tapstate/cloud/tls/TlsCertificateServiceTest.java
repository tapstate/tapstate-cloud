package io.tapstate.cloud.tls;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.pkcs.RSAPrivateKey;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TlsCertificateService}: wildcard/exact lookup order,
 * expiry enforcement and full cryptographic validation of stored documents.
 */
class TlsCertificateServiceTest {

    private static final String HOST = "6475a2b3c1d4e5f6a7b8c9d1.dev.cloud.tapstate.com";
    private static final String WILDCARD = "*.dev.cloud.tapstate.com";

    private final TlsCertificateRepository repository = mock(TlsCertificateRepository.class);
    private final TlsCertificateService service = new TlsCertificateService(repository);

    @Test
    void findsLatestWildcardCertificateForMatchingHost() {
        GeneratedCert generated = GeneratedCert.rsa(WILDCARD, WILDCARD);
        TlsCertificate stored = document(WILDCARD, generated, Instant.now().plus(29, ChronoUnit.DAYS));
        when(repository.findFirstByDomainAndExpiryDateAfterOrderByIdDesc(eq(WILDCARD), any()))
            .thenReturn(Optional.of(stored));

        Optional<TlsCertificate> result = service.findValidForHost(HOST);

        assertTrue(result.isPresent());
        assertEquals(stored.getId(), result.get().getId());
    }

    @Test
    void acceptsPkcs1PrivateKeyPem() {
        GeneratedCert generated = GeneratedCert.rsa(WILDCARD, WILDCARD);
        TlsCertificate stored = document(WILDCARD, generated, Instant.now().plus(29, ChronoUnit.DAYS),
            generated.pkcs1Pem);
        when(repository.findFirstByDomainAndExpiryDateAfterOrderByIdDesc(eq(WILDCARD), any()))
            .thenReturn(Optional.of(stored));

        assertTrue(service.findValidForHost(HOST).isPresent(),
            "PKCS#1 RSA private keys (BEGIN RSA PRIVATE KEY) must be accepted");
    }

    @Test
    void fallsBackToExactDomainDocument() {
        GeneratedCert generated = GeneratedCert.rsa(HOST, HOST);
        TlsCertificate stored = document(HOST, generated, Instant.now().plus(29, ChronoUnit.DAYS));
        when(repository.findFirstByDomainAndExpiryDateAfterOrderByIdDesc(eq(WILDCARD), any()))
            .thenReturn(Optional.empty());
        when(repository.findFirstByDomainAndExpiryDateAfterOrderByIdDesc(eq(HOST), any()))
            .thenReturn(Optional.of(stored));

        assertTrue(service.findValidForHost(HOST).isPresent());
    }

    @Test
    void rejectsDocumentWhoseDeclaredExpiryHasPassed() {
        GeneratedCert generated = GeneratedCert.rsa(WILDCARD, WILDCARD);
        TlsCertificate stored = document(WILDCARD, generated, Instant.now().minus(1, ChronoUnit.HOURS));
        when(repository.findFirstByDomainAndExpiryDateAfterOrderByIdDesc(eq(WILDCARD), any()))
            .thenReturn(Optional.of(stored));

        assertTrue(service.findValidForHost(HOST).isEmpty());
    }

    @Test
    void rejectsCertificateThatIsNotValidYetInItsX509Window() {
        GeneratedCert generated = GeneratedCert.rsa(WILDCARD,
            Instant.now().plus(10, ChronoUnit.DAYS), Instant.now().plus(40, ChronoUnit.DAYS),
            WILDCARD);
        TlsCertificate stored = document(WILDCARD, generated, Instant.now().plus(29, ChronoUnit.DAYS));
        when(repository.findFirstByDomainAndExpiryDateAfterOrderByIdDesc(eq(WILDCARD), any()))
            .thenReturn(Optional.of(stored));

        assertTrue(service.findValidForHost(HOST).isEmpty());
    }

    @Test
    void rejectsPrivateKeyThatDoesNotMatchCertificate() {
        GeneratedCert cert = GeneratedCert.rsa(WILDCARD, WILDCARD);
        GeneratedCert unrelated = GeneratedCert.rsa(WILDCARD, WILDCARD);
        TlsCertificate stored = document(WILDCARD, cert, Instant.now().plus(29, ChronoUnit.DAYS),
            unrelated.pkcs8Pem);
        when(repository.findFirstByDomainAndExpiryDateAfterOrderByIdDesc(eq(WILDCARD), any()))
            .thenReturn(Optional.of(stored));

        assertTrue(service.findValidForHost(HOST).isEmpty());
    }

    @Test
    void rejectsCertificateWhoseIdentityDoesNotCoverHost() {
        GeneratedCert generated = GeneratedCert.rsa("*.cloud.tapstate.com", "*.cloud.tapstate.com");
        TlsCertificate stored = document(WILDCARD, generated, Instant.now().plus(29, ChronoUnit.DAYS));
        when(repository.findFirstByDomainAndExpiryDateAfterOrderByIdDesc(eq(WILDCARD), any()))
            .thenReturn(Optional.of(stored));

        assertTrue(service.findValidForHost(HOST).isEmpty());
    }

    @Test
    void rejectsGarbledPem() {
        TlsCertificate stored = TlsCertificate.builder()
            .id(new ObjectId())
            .domain(WILDCARD)
            .certificate("not a certificate")
            .privateKey("not a key")
            .expiryDate(Instant.now().plus(29, ChronoUnit.DAYS))
            .build();
        when(repository.findFirstByDomainAndExpiryDateAfterOrderByIdDesc(eq(WILDCARD), any()))
            .thenReturn(Optional.of(stored));

        assertTrue(service.findValidForHost(HOST).isEmpty());
    }

    @Test
    void acceptsEcCertificateWhenBouncyCastleIsRegisteredJceProvider() throws Exception {
        // With BC installed as a JCE provider (the production runtime layout),
        // JcaPEMKeyConverter returns a key named "ECDSA", not the JDK name "EC".
        boolean wasRegistered = java.security.Security.getProvider("BC") != null;
        java.security.Security.insertProviderAt(
            new org.bouncycastle.jce.provider.BouncyCastleProvider(), 1);
        try {
            GeneratedCert generated = GeneratedCert.ec(WILDCARD, WILDCARD);
            TlsCertificate stored = document(WILDCARD, generated, Instant.now().plus(29, ChronoUnit.DAYS));
            when(repository.findFirstByDomainAndExpiryDateAfterOrderByIdDesc(eq(WILDCARD), any()))
                .thenReturn(Optional.of(stored));

            // Lock the premise: under BC the PEM-converted key is named "ECDSA",
            // which the old switch rejected before reaching any signature check.
            try (org.bouncycastle.openssl.PEMParser parser = new org.bouncycastle.openssl.PEMParser(
                    new java.io.StringReader(generated.pkcs8Pem))) {
                org.bouncycastle.asn1.pkcs.PrivateKeyInfo info =
                    (org.bouncycastle.asn1.pkcs.PrivateKeyInfo) parser.readObject();
                java.security.PrivateKey converted =
                    new org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter().getPrivateKey(info);
                assertEquals("ECDSA", converted.getAlgorithm());
            }

            assertTrue(service.findValidForHost(HOST).isPresent(),
                "EC certificates must be accepted when the parsed key is named ECDSA by BouncyCastle");
        } finally {
            if (!wasRegistered) {
                java.security.Security.removeProvider("BC");
            }
        }
    }

    @Test
    void challengeSignatureAlgorithmNormalisesProviderKeyNames() {
        assertEquals("SHA256withECDSA", TlsCertificateService.challengeSignatureAlgorithm("EC"));
        assertEquals("SHA256withECDSA", TlsCertificateService.challengeSignatureAlgorithm("ECDSA"));
        assertEquals("SHA256withECDSA", TlsCertificateService.challengeSignatureAlgorithm(" ecdsa "));
        assertEquals("Ed25519", TlsCertificateService.challengeSignatureAlgorithm("Ed25519"));
        assertEquals("Ed25519", TlsCertificateService.challengeSignatureAlgorithm("EdDSA"));
        assertEquals("SHA256withRSA", TlsCertificateService.challengeSignatureAlgorithm("RSA"));
        assertEquals("SHA256withDSA", TlsCertificateService.challengeSignatureAlgorithm("DSA"));
        assertEquals(null, TlsCertificateService.challengeSignatureAlgorithm("ECDH"));
        assertEquals(null, TlsCertificateService.challengeSignatureAlgorithm(null));
    }

    @Test
    void candidateDomainsAreWildcardSuffixThenExactHost() {
        assertEquals(java.util.List.of(WILDCARD, HOST),
            TlsCertificateService.candidateDomains(HOST));
        assertEquals(java.util.List.of("singlelabel"),
            TlsCertificateService.candidateDomains("singlelabel"));
    }

    @Test
    void wildcardIdentityMatchesOneLabelOnly() {
        assertTrue(TlsCertificateService.nameMatches("*.example.com", "a.example.com"));
        assertFalse(TlsCertificateService.nameMatches("*.example.com", "a.b.example.com"));
        assertFalse(TlsCertificateService.nameMatches("*.example.com", "example.com"));
        assertTrue(TlsCertificateService.nameMatches("a.example.com", "a.example.com"));
    }

    // ========== Test fixture ==========

    private static TlsCertificate document(String domain, GeneratedCert generated, Instant expiryDate) {
        return document(domain, generated, expiryDate, generated.pkcs8Pem);
    }

    private static TlsCertificate document(String domain, GeneratedCert generated,
                                           Instant expiryDate, String privateKeyPem) {
        return TlsCertificate.builder()
            .id(new ObjectId())
            .domain(domain)
            .certificate(generated.certPem)
            .privateKey(privateKeyPem)
            .expiryDate(expiryDate)
            .build();
    }

    /** A self-signed certificate and its private key, encoded as PEM. */
    private record GeneratedCert(String certPem, String pkcs8Pem, String pkcs1Pem) {

        static GeneratedCert rsa(String cn, String... sanDns) {
            return rsa(cn, Instant.now().minus(1, ChronoUnit.DAYS),
                Instant.now().plus(30, ChronoUnit.DAYS), sanDns);
        }

        static GeneratedCert ec(String cn, String... sanDns) {
            return ec(cn, Instant.now().minus(1, ChronoUnit.DAYS),
                Instant.now().plus(30, ChronoUnit.DAYS), sanDns);
        }

        static GeneratedCert ec(String cn, Instant notBefore, Instant notAfter, String... sanDns) {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("ECDSA", "BC");
                generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
                KeyPair keyPair = generator.generateKeyPair();

                X500Name subject = new X500Name("CN=" + cn);
                X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    subject, BigInteger.valueOf(System.nanoTime()),
                    Date.from(notBefore), Date.from(notAfter), subject, keyPair.getPublic());
                if (sanDns.length > 0) {
                    GeneralName[] names = java.util.Arrays.stream(sanDns)
                        .map(name -> new GeneralName(GeneralName.dNSName, name))
                        .toArray(GeneralName[]::new);
                    builder.addExtension(Extension.subjectAlternativeName, false,
                        new GeneralNames(names));
                }
                ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA")
                    .setProvider("BC").build(keyPair.getPrivate());
                byte[] der = builder.build(signer).getEncoded();

                String certPem = pem("CERTIFICATE", der);
                String pkcs8Pem = pem("PRIVATE KEY", keyPair.getPrivate().getEncoded());
                return new GeneratedCert(certPem, pkcs8Pem, pkcs8Pem);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        static GeneratedCert rsa(String cn, Instant notBefore, Instant notAfter, String... sanDns) {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                KeyPair keyPair = generator.generateKeyPair();

                X500Name subject = new X500Name("CN=" + cn);
                X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    subject, BigInteger.valueOf(System.nanoTime()),
                    Date.from(notBefore), Date.from(notAfter), subject, keyPair.getPublic());
                if (sanDns.length > 0) {
                    GeneralName[] names = java.util.Arrays.stream(sanDns)
                        .map(name -> new GeneralName(GeneralName.dNSName, name))
                        .toArray(GeneralName[]::new);
                    builder.addExtension(Extension.subjectAlternativeName, false,
                        new GeneralNames(names));
                }
                ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                    .build(keyPair.getPrivate());
                byte[] der = builder.build(signer).getEncoded();

                String certPem = pem("CERTIFICATE", der);
                String pkcs8Pem = pem("PRIVATE KEY", keyPair.getPrivate().getEncoded());

                PrivateKeyInfo privateKeyInfo = PrivateKeyInfo.getInstance(keyPair.getPrivate().getEncoded());
                byte[] pkcs1Der = RSAPrivateKey.getInstance(privateKeyInfo.parsePrivateKey()).getEncoded();
                String pkcs1Pem = pem("RSA PRIVATE KEY", pkcs1Der);

                return new GeneratedCert(certPem, pkcs8Pem, pkcs1Pem);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private static String pem(String type, byte[] der) {
            String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(der);
            return "-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n";
        }
    }
}
