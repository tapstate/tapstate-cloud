package io.tapstate.cloud.version;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** API view of one published Cluster version. */
@Schema(description = "Published Cluster version")
public record ClusterVersionResponse(

    @Schema(description = "Cluster version document id") String id,
    @Schema(description = "Version string", example = "1.4.0") String version,
    @Schema(description = "Monotonic internal sequence number; greater means newer") long versionNumber,
    @Schema(description = "Release channel: ALPHA / BETA / RC / STABLE") ClusterReleaseCode releaseCode,
    @Schema(description = "Release timestamp (UTC)") Instant releaseDate,
    @Schema(description = "Release notes") String releaseNotes,
    @Schema(description = "Listing state: ACTIVE / OFFLINE") ClusterVersionState state,
    @Schema(description = "Git source information for this release") GitInfo git
) {

    public static ClusterVersionResponse from(ClusterVersion v) {
        return new ClusterVersionResponse(
            v.getId() != null ? v.getId().toHexString() : null,
            v.getVersion(),
            v.getVersionNumber(),
            v.getReleaseCode(),
            v.getReleaseDate(),
            v.getReleaseNotes(),
            v.getState(),
            v.getGit());
    }
}
