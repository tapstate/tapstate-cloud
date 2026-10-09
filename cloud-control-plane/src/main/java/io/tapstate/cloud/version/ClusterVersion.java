package io.tapstate.cloud.version;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One published Cluster release in the global {@code cluster_versions}
 * collection.
 * <p>
 * The table is system-global configuration: it belongs to no organization or
 * customer and is maintained by the operator team. "Newest" is defined solely
 * by {@link #versionNumber} (an atomically increasing counter), never by the
 * {@code version} string or ObjectId timestamp.
 */
@Document(collection = "cluster_versions")
@CompoundIndex(name = "state_versionNumber", def = "{'state': 1, 'versionNumber': -1}")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Published Cluster version — global operator-managed configuration")
public class ClusterVersion {

    @Id
    @Schema(description = "Cluster version document id")
    private ObjectId id;

    @Indexed(unique = true)
    @Schema(description = "Version string, non-empty after trim (e.g. 1.4.0 / 1.4.0-rc.1)", example = "1.4.0")
    private String version;

    @Indexed(unique = true)
    @Schema(description = "Monotonic internal sequence number; greater means newer")
    private long versionNumber;

    @Schema(description = "Release channel: ALPHA / BETA / RC / STABLE")
    private ClusterReleaseCode releaseCode;

    @Schema(description = "Release timestamp (UTC), set to current time on insert")
    private Instant releaseDate;

    @Schema(description = "Release notes")
    private String releaseNotes;

    @Schema(description = "Listing state: ACTIVE (listed) / OFFLINE (delisted)")
    private ClusterVersionState state;

    @Schema(description = "Git source information for this release")
    private GitInfo git;
}
