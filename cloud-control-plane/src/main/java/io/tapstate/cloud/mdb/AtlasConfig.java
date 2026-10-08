package io.tapstate.cloud.mdb;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Atlas Programmatic API Key — stored in MongoDB so AtlasMdbService can look up
 * the currently-activated key on every Atlas Admin API call without config restarts.
 * <p>
 * Uniqueness invariant: at most one {@code status = "Activated"} document at a time.
 * Enforced via a partialFilterExpression unique index (see {@link AtlasConfigRepository}).
 * <p>
 * The PO-provided original structure (atlasConfig.json) is the seed data shape —
 * fields are named to match that structure for a straight import.
 */
@Document(collection = "atlas_configs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Atlas Programmatic API Key — credentials source for all Atlas Admin API calls")
public class AtlasConfig {

    @Id
    private ObjectId id;

    /** Atlas Programmatic API Public Key (Digest Auth username). */
    @Schema(description = "Atlas API Public Key — Digest Auth username", hidden = true)
    private String publicKey;

    /** Atlas Programmatic API Private Key (Digest Auth password). NEVER serialized to API response. */
    @Schema(description = "Atlas API Private Key — SENSITIVE, server-side only", hidden = true)
    private String privateKey;

    /** Atlas Admin API host — defaults to {@code cloud.mongodb.com}. */
    @Builder.Default
    @Schema(description = "Atlas Admin API domain", example = "cloud.mongodb.com")
    private String domain = "cloud.mongodb.com";

    /** Atlas Organization ID — needed for Describe API calls that fetch roles. */
    @Schema(description = "Atlas Organization ID", example = "64082d7893e9d409965ecf32")
    private String orgId;

    /**
     * Lifecycle status — only one Activated key allowed globally
     * (enforced by partialFilterExpression unique index).
     */
    @Indexed
    @Schema(description = "Activated = the key currently used for Atlas Admin API calls", example = "Activated")
    private String status;

    /**
     * Atlas Admin API DescribeKey response — auto-populated the first time the
     * key is used. Contains {@code _id}, {@code publicKey}, {@code roles[]} etc.
     * Nullable on seed.
     */
    @Schema(description = "Auto-populated Describe API Key response from Atlas Admin API")
    private org.bson.Document describeAPIKey;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
