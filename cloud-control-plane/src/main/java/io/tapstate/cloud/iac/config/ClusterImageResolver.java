package io.tapstate.cloud.iac.config;

import io.tapstate.cloud.iac.IacProperties;
import org.springframework.stereotype.Component;

/**
 * Resolves the runtime container image deployed onto a Cluster node:
 * {@code <registry>/<repository>:<version>}.
 */
@Component
public class ClusterImageResolver {

    private final IacProperties iacProperties;

    public ClusterImageResolver(IacProperties iacProperties) {
        this.iacProperties = iacProperties;
    }

    /**
     * Build the fully qualified runtime image reference for the given version.
     *
     * @param version Cluster version used as the image tag
     * @return image reference {@code registry/repository:version}
     */
    public String runtimeImage(String version) {
        IacProperties.RuntimeImage image = iacProperties.getRuntimeImage();
        return image.getRegistry() + "/" + image.getRepository() + ":" + version;
    }
}
