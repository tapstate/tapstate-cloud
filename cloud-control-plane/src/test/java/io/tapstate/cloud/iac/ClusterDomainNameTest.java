package io.tapstate.cloud.iac;

import io.tapstate.cloud.cluster.Cluster;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterDomainNameTest {

    private IacProperties properties(String environment) {
        IacProperties properties = new IacProperties();
        properties.setEnvironment(environment);
        return properties;
    }

    @Test
    void devEnvironmentAddsTheDevLabel() {
        ClusterDomainName resolver = new ClusterDomainName(properties("dev"));

        assertThat(resolver.fqdn("my-cluster"))
            .isEqualTo("my-cluster.dev.cloud.tapstate.com");
    }

    @Test
    void awsdevIsTreatedLikeAnyNonProductionEnvironment() {
        ClusterDomainName resolver = new ClusterDomainName(properties("awsdev"));

        assertThat(resolver.fqdn("my-cluster"))
            .isEqualTo("my-cluster.dev.cloud.tapstate.com");
    }

    @Test
    void productionPlacesTheClusterUnderTheApexDomain() {
        ClusterDomainName resolver = new ClusterDomainName(properties("prod"));

        assertThat(resolver.fqdn("my-cluster"))
            .isEqualTo("my-cluster.cloud.tapstate.com");
    }

    @Test
    void productionSelectorIsCaseInsensitive() {
        ClusterDomainName resolver = new ClusterDomainName(properties("PROD"));

        assertThat(resolver.fqdn("my-cluster"))
            .isEqualTo("my-cluster.cloud.tapstate.com");
    }

    @Test
    void fqdnOfClusterUsesItsPersistedDnsLabel() {
        ClusterDomainName resolver = new ClusterDomainName(properties("prod"));
        Cluster cluster = Cluster.builder().dnsLabel("production").build();

        assertThat(resolver.fqdn(cluster))
            .isEqualTo("production.cloud.tapstate.com");
    }

    @Test
    void honorsTheConfiguredRootDomain() {
        IacProperties properties = properties("dev");
        properties.getGodaddy().setDomain("tapstate.io");
        ClusterDomainName resolver = new ClusterDomainName(properties);

        assertThat(resolver.fqdn("lab"))
            .isEqualTo("lab.dev.cloud.tapstate.io");
    }
}
