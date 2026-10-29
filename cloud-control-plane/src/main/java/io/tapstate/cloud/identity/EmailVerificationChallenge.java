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

@Document(collection = "email_verification_challenges")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailVerificationChallenge {

    @Id
    private ObjectId id;

    @Indexed(unique = true)
    private String key;

    private ObjectId invitationId;
    private String email;
    private String codeHmac;
    private Instant issuedAt;
    @Indexed(expireAfter = "1h")
    private Instant expiresAt;
    private Instant lastSentAt;
    private Instant consumedAt;
    private int attempts;
    private int resends;
}
