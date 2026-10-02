package io.tapstate.cloud.iac;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the IaC layer configuration properties.
 * <p>
 * Bean implementations ({@code ComputeInfrastructure}, {@code DnsInfrastructure},
 * SSH and orchestration services) are picked up through component scanning; this
 * configuration only activates the typed property binding.
 */
@Configuration
@EnableConfigurationProperties(IacProperties.class)
public class IacConfig {
}
