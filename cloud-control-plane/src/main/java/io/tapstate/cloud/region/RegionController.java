package io.tapstate.cloud.region;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.tapstate.cloud.common.api.ApiResponse;
import io.tapstate.cloud.common.api.BaseController;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Region endpoints — list available AWS regions for Cluster provisioning.
 */
@RestController
@RequestMapping("/v1/regions")
@RequiredArgsConstructor
@Tag(name = "Regions", description = "Available AWS regions for Cluster provisioning")
public class RegionController extends BaseController {

    private final RegionService regionService;

    @GetMapping
    @Operation(
        summary = "List open regions",
        description = "Returns all AWS regions currently open for Cluster provisioning. " +
                      "Week 2: only one region (e.g. ap-southeast-1) is open. " +
                      "Region selection is done at Cluster creation time and is IMMUTABLE — " +
                      "changing Region requires cancel + resubscribe (Week 4+)."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "List of open regions")
    })
    public ResponseEntity<ApiResponse<List<RegionResponse>>> listOpenRegions() {
        List<Region> regions = regionService.listOpenRegions();
        return ok(regions.stream().map(RegionResponse::from).toList());
    }

    @GetMapping("/{code}")
    @Operation(
        summary = "Get region by code",
        description = "Returns details for a specific AWS region by its standard code (e.g. 'ap-southeast-1')."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Region details"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Region not found")
    })
    public ResponseEntity<ApiResponse<RegionResponse>> getRegion(
            @Parameter(description = "AWS region code", example = "ap-southeast-1") @PathVariable String code) {
        Region region = regionService.getByCode(code);
        return ok(RegionResponse.from(region));
    }

    public record RegionResponse(
        @Schema(description = "AWS region code (e.g. ap-southeast-1)") String code,
        @Schema(description = "Human-readable region name (e.g. Asia Pacific - Singapore)") String name,
        @Schema(description = "Whether this region is open for new Cluster provisioning") boolean open,
        @Schema(description = "Default provisioning spec_key for this region (e.g. 'basic')") String defaultSpecKey
    ) {
        public static RegionResponse from(Region region) {
            return new RegionResponse(
                region.getCode(),
                region.getName(),
                region.isOpen(),
                region.getDefaultSpecKey()
            );
        }
    }
}
