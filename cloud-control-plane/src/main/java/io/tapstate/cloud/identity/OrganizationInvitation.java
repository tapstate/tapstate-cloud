package io.tapstate.cloud.identity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "organization_invitations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizationInvitation {

    @Id
    private ObjectId id;

    @Indexed(unique = true)
    private String tokenHash;

    private ObjectId orgId;
    private String purpose;
    private String createdByEmail;
    private String invitedEmail;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant revokedAt;
    private Instant consumedAt;
    private String consumedByEmail;
}
