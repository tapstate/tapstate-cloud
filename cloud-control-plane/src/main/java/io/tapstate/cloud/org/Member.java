package io.tapstate.cloud.org;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.CompoundIndex;

import java.time.Instant;
import org.bson.types.ObjectId;

/**
 * Organization member — tracks which users belong to which Organizations.
 * <p>
 * A user can be a member of multiple Organizations.
 */
@Document(collection = "org_members")
@CompoundIndex(name = "org_user_email_unique", def = "{'orgId': 1, 'userEmail': 1}", unique = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Organization membership record")
public class Member {

    @Id
    private ObjectId id;

    private ObjectId orgId;

    private String userEmail;

    @Builder.Default
    private String role = "member"; // owner / member

    @CreatedDate
    private Instant joinedAt;
}
