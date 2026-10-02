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
     * @param proxyHost        HTTP CONNECT proxy host, or {@code null} for a direct connection
     * @param proxyPort        HTTP CONNECT proxy port, ignored when proxyHost is null
     * @return an open, authenticated session
     * @throws java.io.IOException when connecting or authenticating fails
     */
    SshSession connect(String host, int port, String user, String privateKeyPem,
                       int connectTimeoutMs, String proxyHost, int proxyPort)
        throws java.io.IOException;
}
