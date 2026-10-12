package io.tapstate.cloud.version;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Request body for listing/delisting a published Cluster version. */
@Schema(description = "Change a Cluster version listing state")
public record UpdateVersionStateRequest(

    @NotNull
    @Schema(description = "New state: ACTIVE (listed) or OFFLINE (delisted)")
    ClusterVersionState state
) {
}
