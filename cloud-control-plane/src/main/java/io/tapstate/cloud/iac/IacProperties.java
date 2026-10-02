package io.tapstate.cloud.iac;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration of the infrastructure-as-code layer.
 * <p>
 * Bound to the {@code tapstate.cloud.iac} prefix. Every nested group carries
 * non-sensitive defaults; secrets default to blank and are expected to be injected
 * from the environment.
 */
@ConfigurationProperties(prefix = "tapstate.cloud.iac")
public class IacProperties {

    /** Active compute provider id ({@link CloudProvider#id()}). */
    private String provider = "aws";

    /**
     * Provisioning mode: {@code real} drives actual cloud APIs; {@code mock}
     * simulates outcomes for fast tests.
     */
    private String mode = "real";

    /** Public base URL of this control plane, handed to Cluster runtimes. */
    private String publicBaseUrl = "https://cloud.tapstate.com";

    /**
     * Fixed egress CIDR permitted to reach node port 22. Blank means the public IP
     * of the control plane host is discovered at provision time.
     */
    private String sshSourceCidr = "";

    /** DNS environment suffix: {@code dev} produces {@code <id>.dev.cloud.<domain>}. */
    private String environment = "dev";

    /** AWS-specific settings. */
    private Aws aws = new Aws();

    /** GoDaddy DNS settings. */
    private Godaddy godaddy = new Godaddy();

    /** Private-key protection settings. */
    private Crypto crypto = new Crypto();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public String getSshSourceCidr() {
        return sshSourceCidr;
    }

    public void setSshSourceCidr(String sshSourceCidr) {
        this.sshSourceCidr = sshSourceCidr;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public Aws getAws() {
        return aws;
    }

    public void setAws(Aws aws) {
        this.aws = aws;
    }

    public Godaddy getGodaddy() {
        return godaddy;
    }

    public void setGodaddy(Godaddy godaddy) {
        this.godaddy = godaddy;
    }

    public Crypto getCrypto() {
        return crypto;
    }

    public void setCrypto(Crypto crypto) {
        this.crypto = crypto;
    }

    /** AWS compute settings. */
    public static class Aws {

        /** EC2 instance type for the default spec. */
        private String instanceType = "t3.medium";

        /** Root disk size in GB. */
        private int diskSizeGb = 40;

        public String getInstanceType() {
            return instanceType;
        }

        public void setInstanceType(String instanceType) {
            this.instanceType = instanceType;
        }

        public int getDiskSizeGb() {
            return diskSizeGb;
        }

        public void setDiskSizeGb(int diskSizeGb) {
            this.diskSizeGb = diskSizeGb;
        }
    }

    /** GoDaddy DNS API settings. */
    public static class Godaddy {

        /** API base URL (production by default; OTE available for tests). */
        private String baseUrl = "https://api.godaddy.com";

        /** Root domain whose records are managed. */
        private String domain = "tapstate.com";

        /** GoDaddy API key ({@code sso-key} credential part one). */
        private String apiKey = "";

        /** GoDaddy API secret ({@code sso-key} credential part two). */
        private String apiSecret = "";

        /** Record TTL in seconds. */
        private long ttl = 600;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getDomain() {
            return domain;
        }

        public void setDomain(String domain) {
            this.domain = domain;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getApiSecret() {
            return apiSecret;
        }

        public void setApiSecret(String apiSecret) {
            this.apiSecret = apiSecret;
        }

        public long getTtl() {
            return ttl;
        }

        public void setTtl(long ttl) {
            this.ttl = ttl;
        }
    }

    /** Settings protecting generated SSH private keys at rest. */
    public static class Crypto {

        /** Base64-encoded 32-byte AES master key; blank blocks provisioning. */
        private String masterKey = "";

        public String getMasterKey() {
            return masterKey;
        }

        public void setMasterKey(String masterKey) {
            this.masterKey = masterKey;
        }
    }
}
