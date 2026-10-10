package io.tapstate.cloud.tls;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Selects and validates {@link TlsCertificate} documents for Cluster hosts.
 * <p>
 * Lookup is by DNS suffix: a host {@code a.dev.cloud.example.com} is covered by
 * a certificate stored under {@code *.dev.cloud.example.com} (or, rarely, under
 * its exact FQDN). The newest non-expired document wins. Documents that fail
 * cryptographic validation are skipped with a warning rather than pushed to
 * nodes, where {@code nginx -t} would reject them.
 */
@Service
@Slf4j
public class TlsCertificateService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final TlsCertificateRepository repository;

    public TlsCertificateService(TlsCertificateRepository repository) {
        this.repository = repository;
    }

    /**
     * Find the latest usable certificate covering {@code host}, if any.
     *
     * @param host fully qualified Cluster DNS name, e.g. {@code 64ab…dev.cloud.example.com}
     */
    public Optional<TlsCertificate> findValidForHost(String host) {
        Instant now = Instant.now();
        for (String candidate : candidateDomains(host)) {
            Optional<TlsCertificate> stored =
                repository.findFirstByDomainAndExpiryDateAfterOrderByIdDesc(candidate, now);
            if (stored.isEmpty()) {
                continue;
            }
            TlsCertificate certificate = stored.get();
            String reason = validate(certificate, host, now);
            if (reason == null) {
                return Optional.of(certificate);
            }
            log.warn("TLS certificate {} stored under '{}' is unusable for host {}: {}",
                certificate.getId(), candidate, host, reason);
        }
        return Optional.empty();
    }

    /**
     * Validate a stored document: present fields, parseable X.509 chain, within
     * validity window, DNS identity covering {@code host}, parseable private key
     * whose key matches the leaf certificate.
     *
     * @return {@code null} when usable, otherwise a human-readable reason
     */
    public String validate(TlsCertificate certificate, String host, Instant now) {
        if (certificate == null) {
            return "document is missing";
        }
        if (isBlank(certificate.getDomain()) || isBlank(certificate.getCertificate())
            || isBlank(certificate.getPrivateKey()) || certificate.getExpiryDate() == null) {
            return "required fields are missing";
        }
        if (!certificate.getExpiryDate().isAfter(now)) {
            return "declared expiryDate " + certificate.getExpiryDate() + " has passed";
        }

        X509Certificate leaf;
        try {
            Collection<?> parsed = CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(
                    certificate.getCertificate().getBytes(StandardCharsets.UTF_8)));
            if (parsed.isEmpty()) {
                return "certificate PEM contains no X.509 certificate";
            }
            leaf = (X509Certificate) parsed.iterator().next();
            leaf.checkValidity(java.util.Date.from(now));
        } catch (Exception e) {
            return "certificate PEM cannot be parsed or is not valid now: " + e.getMessage();
        }

        if (!identityCoversHost(leaf, host)) {
            return "certificate DNS identity does not cover " + host;
        }

        PrivateKey privateKey;
        try {
            privateKey = parsePrivateKey(certificate.getPrivateKey());
        } catch (Exception e) {
            return "private key PEM cannot be parsed: " + e.getMessage();
        }

        if (!keyMatchesCertificate(privateKey, leaf)) {
            return "private key does not match the leaf certificate";
        }
        return null;
    }

    /** DNS names to look up, most specific first: the wildcard suffix then the exact host. */
    static List<String> candidateDomains(String host) {
        List<String> candidates = new ArrayList<>(2);
        int firstDot = host.indexOf('.');
        if (firstDot > 0 && firstDot < host.length() - 1) {
            candidates.add("*" + host.substring(firstDot));
        }
        candidates.add(host);
        return candidates;
    }

    /**
     * Whether the certificate's subjectAltName (DNS) or, when SANs are absent,
     * its common name covers {@code host}. Wildcard identities match a single
     * non-dotted left-most label.
     */
    private boolean identityCoversHost(X509Certificate certificate, String host) {
        List<String> names = new ArrayList<>();
        try {
            Collection<List<?>> sans = certificate.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> san : sans) {
                    if (san.size() >= 2 && Integer.valueOf(2).equals(san.get(0)) && san.get(1) != null) {
                        names.add(san.get(1).toString());
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through to the subject CN.
        }
        if (names.isEmpty() && certificate.getSubjectX500Principal() != null) {
            String dn = certificate.getSubjectX500Principal().getName("RFC1779");
            for (String part : dn.split(",")) {
                String trimmed = part.trim();
                if (trimmed.toUpperCase().startsWith("CN=")) {
                    names.add(trimmed.substring(3));
                }
            }
        }
        String normalizedHost = host.trim().toLowerCase();
        for (String name : names) {
            if (nameMatches(name.trim().toLowerCase(), normalizedHost)) {
                return true;
            }
        }
        return false;
    }

    /** Exact match, or one-label wildcard match ({@code *.example.com} covers {@code a.example.com}). */
    static boolean nameMatches(String identity, String host) {
        if (identity.equals(host)) {
            return true;
        }
        if (!identity.startsWith("*.")) {
            return false;
        }
        String suffix = identity.substring(1); // ".example.com"
        if (!host.endsWith(suffix)) {
            return false;
        }
        String leftLabel = host.substring(0, host.length() - suffix.length());
        return !leftLabel.isBlank() && !leftLabel.contains(".");
    }

    /** Parse a PKCS #1 or PKCS #8 private key PEM (RSA / EC / Ed25519 / DSA). */
    private static PrivateKey parsePrivateKey(String pem) throws Exception {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object object = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            if (object instanceof PEMKeyPair keyPair) {
                return converter.getKeyPair(keyPair).getPrivate();
            }
            if (object instanceof PrivateKeyInfo keyInfo) {
                return converter.getPrivateKey(keyInfo);
            }
            throw new IllegalArgumentException("PEM does not contain a private key");
        }
    }

    /**
     * Prove key ownership by signing a random challenge with the private key and
     * verifying it with the certificate public key — algorithm independent and
     * safe for RSA, EC and Ed25519 keys.
     */
    private boolean keyMatchesCertificate(PrivateKey privateKey, X509Certificate certificate) {
        String algorithm = challengeSignatureAlgorithm(privateKey.getAlgorithm());
        if (algorithm == null) {
            log.warn("Unsupported TLS private key algorithm: {}", privateKey.getAlgorithm());
            return false;
        }
        try {
            byte[] challenge = new byte[32];
            SECURE_RANDOM.nextBytes(challenge);
            Signature signer = Signature.getInstance(algorithm);
            signer.initSign(privateKey);
            signer.update(challenge);
            byte[] signatureBytes = signer.sign();

            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(certificate.getPublicKey());
            verifier.update(challenge);
            return verifier.verify(signatureBytes);
        } catch (Exception e) {
            log.warn("TLS private key/certificate match check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Map a provider-specific key algorithm name to the JCA Signature algorithm
     * used for the ownership challenge. JCE providers disagree on EC key naming —
     * the JDK names an EC key {@code EC} while BouncyCastle names the same key
     * {@code ECDSA} — so the raw {@code PrivateKey.getAlgorithm()} value cannot
     * be switched on directly.
     */
    static String challengeSignatureAlgorithm(String keyAlgorithm) {
        if (keyAlgorithm == null) {
            return null;
        }
        return switch (keyAlgorithm.trim().toUpperCase()) {
            case "RSA" -> "SHA256withRSA";
            case "EC", "ECDSA" -> "SHA256withECDSA";
            case "ED25519", "EDDSA" -> "Ed25519";
            case "DSA" -> "SHA256withDSA";
            default -> null;
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
