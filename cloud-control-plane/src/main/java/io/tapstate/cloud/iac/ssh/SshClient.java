package io.tapstate.cloud.iac.ssh;

/**
 * Factory for authenticated {@link SshSession}s against Cluster nodes.
 */
public interface SshClient {

    /**
     * Open an SSH session using public-key authentication.
     *
     * @param host             node host or IP
     * @param port             SSH port
     * @param user             login user
     * @param privateKeyPem    PKCS#8 PEM-encoded private key
     * @param connectTimeoutMs per-connection timeout in milliseconds
     * @return an open, authenticated session
     * @throws java.io.IOException when connecting or authenticating fails
     */
    SshSession connect(String host, int port, String user, String privateKeyPem,
                       int connectTimeoutMs) throws java.io.IOException;
}
