package io.tapstate.cloud.tls;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * TLS certificate stored for distribution to Cluster nodes.
 * <p>
 * Documents are inserted by operators (collection {@code tls_certificates});
 * the control plane never issues or renews certificates. The fields are the
 * operator-facing contract:
 * <ul>
 *   <li>{@code domain} — wildcard or exact DNS name this certificate covers,
 *       e.g. {@code *.dev.cloud.tapstate.com}</li>
 *   <li>{@code certificate} — full-chain PEM ({@code fullchain.pem} content)</li>
 *   <li>{@code privateKey} — private key PEM ({@code privkey.pem} content)</li>
 *   <li>{@code expiryDate} — operator-declared expiry used for selection</li>
 * </ul>
 * "Latest" means the greatest {@code _id}: an ObjectId embeds a monotonic
 * insertion timestamp, so no separate createdAt field is required.
 */
@Document(collection = "tls_certificates")
@CompoundIndex(name = "domain_expiry", def = "{'domain': 1, 'expiryDate': -1}")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TlsCertificate {

    @Id
    private ObjectId id;

    /** DNS name covered by the certificate; wildcard form {@code *.suffix} or exact FQDN. */
    private String domain;

    /** Full certificate chain in PEM format, leaf certificate first. */
    private String certificate;

    /** Private key matching the leaf certificate, PEM format (PKCS #1 or PKCS #8). */
    private String privateKey;

    /** Certificate expiry declared by the operator; expired documents are never selected. */
    private Instant expiryDate;
}
