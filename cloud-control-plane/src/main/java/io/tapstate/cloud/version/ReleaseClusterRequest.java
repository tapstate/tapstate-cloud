package io.tapstate.cloud.version;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Operator request body for publishing a new Cluster version.
 * <p>
 * {@code versionNumber}, {@code releaseDate} and {@code state} are never
 * accepted from the client: they are assigned server-side.
 */
@Schema(description = "Publish a new Cluster version")
public record ReleaseClusterRequest(

    @NotBlank
    @Size(max = 100)
    @Schema(description = "Version string; any non-empty trimmed string, e.g. 1.4.0 or 1.4.0-rc.1", example = "1.4.0")
    String version,

    @NotNull
    @Schema(description = "Release channel: ALPHA / BETA / RC / STABLE")
    ClusterReleaseCode releaseCode,

    @Size(max = 5000)
    @Schema(description = "Release notes")
    String releaseNotes,

    @NotNull
    @Valid
    @Schema(description = "Git source information for this release")
    GitInfo git
) {
}
