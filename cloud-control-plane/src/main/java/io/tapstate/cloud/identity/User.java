package io.tapstate.cloud.identity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import org.bson.types.ObjectId;

/**
 * Global Control Plane user — email/password authentication (ADR-0122 D1).
 * <p>
 * Isolation: User records are global across Organizations; Organization membership
 * is tracked separately in {@code org.Member} (ADR-0122 D3).
 * <p>
 * Password: BCrypt hash, never stored as plaintext.
 */
@Document(collection = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Global Control Plane user account")
public class User {

    @Id
    @Schema(description = "User ID")
    private ObjectId id;

    @Indexed(unique = true)
    @Schema(description = "Email address (unique login identifier)", example = "alice@tapstate.io")
    private String email;

    @Schema(description = "BCrypt password hash (NEVER plaintext)")
    private String passwordHash;

    @Schema(description = "Display name", example = "Alice Chen")
    private String displayName;

    @Schema(description = "Whether email has been verified via magic link")
    private boolean emailVerified;

    @Schema(description = "Account status", allowableValues = {"active", "disabled", "pending_verification"})
    @Builder.Default
    private String status = "pending_verification";

    @CreatedDate
    @Schema(description = "Account creation timestamp (UTC)")
    private Instant createdAt;

    @LastModifiedDate
    @Schema(description = "Last update timestamp (UTC)")
    private Instant updatedAt;

    @Schema(description = "Last successful login timestamp (UTC)")
    private Instant lastLoginAt;
}
