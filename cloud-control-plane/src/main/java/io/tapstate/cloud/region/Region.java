package io.tapstate.cloud.region;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * AWS Region definition — which regions are open for user Cluster provisioning.
 * <p>
 * Week 2: only {@code ap-southeast-1} (Singapore) is open (ADR-0124 D1).
 * Production regions are added post-W4.
 */
@Document(collection = "regions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "AWS Region available for Cluster provisioning")
public class Region {

    @Id
    @Schema(description = "Region code", example = "ap-southeast-1")
    private String code;

    @Schema(description = "Human-readable name", example = "Asia Pacific (Singapore)")
    private String name;

    @Schema(description = "Whether this region is open for new Cluster creation")
    @Builder.Default
    private boolean open = true;

    @Schema(description = "Default per-Cluster spec_key for this region", example = "basic")
    @Builder.Default
    private String defaultSpecKey = "basic";

    @Schema(description = "Sort order (lower = listed first)")
    @Builder.Default
    private int sortOrder = 100;
}
