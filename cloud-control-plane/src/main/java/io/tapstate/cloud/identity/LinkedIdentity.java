package io.tapstate.cloud.identity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "linked_identities")
@CompoundIndexes({
    @CompoundIndex(name = "provider_subject_unique", def = "{'provider': 1, 'subject': 1}", unique = true),
    @CompoundIndex(name = "user_provider_unique", def = "{'userEmail': 1, 'provider': 1}", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LinkedIdentity {

    @Id
    private ObjectId id;

    private String provider;
    private String subject;
    private String userEmail;
    private String providerEmail;
    private Instant linkedAt;
}
