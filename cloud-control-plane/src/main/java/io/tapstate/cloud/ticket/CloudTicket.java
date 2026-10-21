package io.tapstate.cloud.ticket;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "cloud_support_tickets")
@CompoundIndex(name = "owner_created_at_idx", def = "{'ownerId': 1, 'createdAt': -1}")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Cloud-owned mapping to a Jira Service Management request")
public class CloudTicket {

    @Id
    private ObjectId id;

    @Indexed
    private ObjectId ownerId;

    @Indexed(unique = true)
    private String jiraKey;

    private String summary;

    @CreatedDate
    private Instant createdAt;
}
