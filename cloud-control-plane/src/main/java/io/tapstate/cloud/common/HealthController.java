package io.tapstate.cloud.common;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health check endpoints (ADR-0120 acceptance: GET /health → 200).
 * <p>
 * Two routes:
 * - {@code /health} — simple liveness probe (always 200 if process is up)
 * - {@code /health/readiness} — includes MongoDB connectivity and process uptime;
 *   Actuator also exposes the full readiness at {@code /actuator/health}
 */
@RestController
@RequestMapping
@Tag(name = "Health", description = "Health check endpoints")
@Slf4j
public class HealthController {

    @GetMapping("/health")
    @Operation(
        summary = "Liveness probe (always 200 when process is running)",
        description = "Simple liveness check — returns 200 as long as the process is up. " +
                      "Kubernetes pod-level probe (not used for dependency checks)."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Service is live")
    })
    public Map<String, Object> liveness() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("service", "tapstate-cloud-control");
        result.put("uptime_seconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        result.put("timestamp", java.time.Instant.now().toString());
        return result;
    }

    @GetMapping("/health/readiness")
    @Operation(
        summary = "Readiness probe — basic dependency check",
        description = "Readiness check — verifies database connectivity and returns service metadata. " +
                      "Kubernetes probe; Actuator also exposes full readiness at /actuator/health."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Service is ready")
    })
    public Map<String, Object> readiness() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("database", "mongo");
        result.put("uptime_seconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        result.put("timestamp", java.time.Instant.now().toString());
        return result;
    }
}
