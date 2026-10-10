package io.tapstate.cloud.version;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Git provenance of the build backing a published Cluster version.
 * <p>
 * {@code branch} and {@code commitId} are required; {@code extra} carries any
 * additional source metadata the operator wants recorded (tag, remote URL,
 * build URL ...).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Git source information for a Cluster version")
public class GitInfo implements Serializable {

    @NotBlank
    @Schema(description = "Git branch name", example = "main")
    private String branch;

    @NotBlank
    @Schema(description = "Git commit id", example = "0.6.0")
    private String commitId;

    @Schema(description = "Additional optional git source metadata")
    @Builder.Default
    private Map<String, String> extra = new HashMap<>();
}
