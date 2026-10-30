package io.tapstate.cloud.org;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import org.bson.types.ObjectId;

/**
 * Organization — isolation boundary for Clusters (ADR-0122 D3).
 * <p>
 * All Cluster records, deployments, and audit logs belong to exactly one Organization.
 * Cross-Organization access is rejected at both API and Cluster Console levels.
 */
@Document(collection = "organizations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@CompoundIndex(name = "organization_creation_key_unique", def = "{'creationKey': 1}", unique = true,
    partialFilter = "{'creationKey': {'$type': 'string'}}")
@Schema(description = "Organization — isolation boundary for Clusters and resources")
public class Organization {

    @Id
    @Schema(description = "Organization ID")
    private ObjectId id;

    @Schema(description = "Organization name", example = "Acme Corp")
    private String name;

    @Schema(description = "Organization slug (URL-friendly)", example = "acme-corp")
    private String slug;

    @Schema(description = "Owner user email")
    private String ownerEmail;

    @Schema(hidden = true)
    private String creationKey;

    @Builder.Default
    @Schema(description = "Organization status", allowableValues = {"active", "suspended"})
    private String status = "active";

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
