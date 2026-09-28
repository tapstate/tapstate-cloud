package io.tapstate.cloud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Global Control Plane — Tapstate Cloud backend application entry point.
 * <p>
 * Spring Session MongoDB is auto-configured via application.yml
 * spring.session.store-type=mongodb.
 * <p>
 * {@code @EnableScheduling} activates heartbeat scanner, subscription expiry,
 * and other background maintenance tasks defined in service beans.
 * {@code @EnableAsync} lets event listeners (subscription→cluster termination
 * cascade) run without blocking the cancel-subscription HTTP response.
 *
 * @see <a href="https://github.com/tapstate/tapstate-cloud">tapstate-cloud repo</a>
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class CloudControlPlaneApplication {

    public static void main(String[] args) {
        SpringApplication.run(CloudControlPlaneApplication.class, args);
    }
}
