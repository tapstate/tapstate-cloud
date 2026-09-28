package io.tapstate.cloud.org;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Organization member — tracks which users belong to which Organizations.
 * <p>
 * A user can be a member of multiple Organizations.
 */
@Document(collection = "org_members")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Organization membership record")
public class Member {

    @Id
    private String id;

    private String orgId;

    private String userEmail;

    @Builder.Default
    private String role = "member"; // owner / member

    @CreatedDate
    private Instant joinedAt;
}
