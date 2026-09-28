package io.tapstate.cloud.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / SpringDoc configuration for Global Control Plane API.
 * <p>
 * The contract truth source is {@code openapi.yaml} (ADR-0123 D1), but SpringDoc
 * dynamically generates OpenAPI spec from Swagger annotations at runtime.
 * The static {@code openapi.yaml} is the baseline contract for cross-plan fixtures
 * (C1/C2/C3/C4).
 *
 * @see <a href="https://tapstate.io/docs/cloud">Tapstate Cloud Architecture</a>
 */
@Configuration
@OpenAPIDefinition(
    info = @Info(
        title = "Tapstate Cloud — Global Control Plane API",
        version = "v1",
        description = """
            Global Control Plane backend API for Tapstate Cloud.

            ## Authentication

            Three authentication contexts (ADR-0122 D2):
            - **Session Cookie** — Cloud Console users (type ①)
            - **Static Token** — SDK status sender (type ②, `/api/sdk/*`)
            - **Resolve JWT** — issued by this service for Cluster Console access

            ## Regions

            Week 2: only `ap-southeast-1` (Singapore) available.
            Production regions to be added post-W4.

            ## Clusters

            Clusters are auto-provisioned via CloudFormation + EC2 + Docker.
            Lifecycle state machine has 6 states in Week 2.
            """,
        contact = @Contact(
            name = "Tapstate Team",
            email = "cloud@tapstate.io",
            url = "https://tapstate.io"
        )
    ),
    servers = {
        @Server(url = "https://cloud.tapstate.io", description = "Production"),
        @Server(url = "http://localhost:8080", description = "Local development")
    },
    security = {
        @SecurityRequirement(name = "session-cookie")
    }
)
@SecurityScheme(
    name = "session-cookie",
    type = SecuritySchemeType.APIKEY,
    in = SecuritySchemeIn.COOKIE,
    paramName = "TAPSTATE_CLOUD_SESSION",
    description = "Cloud Console session cookie (HttpOnly, Secure, SameSite=Lax)"
)
@SecurityScheme(
    name = "sdk-static-token",
    type = SecuritySchemeType.APIKEY,
    in = SecuritySchemeIn.HEADER,
    paramName = "Authorization",
    description = "SDK static token for runtime → Global Control Plane status reporting. " +
                  "Format: Bearer {per-cluster-static-token}"
)
public class OpenApiConfig {
    // Configuration via annotations; SpringDoc auto-generates /v3/api-docs at runtime
}
