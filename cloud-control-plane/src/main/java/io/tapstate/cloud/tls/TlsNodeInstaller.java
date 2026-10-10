package io.tapstate.cloud.tls;

import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.iac.IacException;
import io.tapstate.cloud.iac.config.ClusterRuntimeConfigs;
import io.tapstate.cloud.iac.ssh.CommandResult;
import io.tapstate.cloud.iac.ssh.SshClient;
import io.tapstate.cloud.iac.ssh.SshSession;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Pushes a {@link TlsCertificate} onto a Cluster node and (re)loads it in Nginx.
 * <p>
 * Used both by first-time provisioning and by the background distribution
 * sweep, so the remote file layout and reload sequence are defined exactly once.
 * The private key lands with {@code 0600} ownership and Nginx is restarted only
 * after {@code nginx -t} accepts the new configuration.
 */
@Component
public class TlsNodeInstaller {

    /** SSH login used by the control plane on Cluster nodes. */
    public static final String SSH_USER = "ubuntu";

    /** SSH port used by the control plane on Cluster nodes. */
    public static final int SSH_PORT = 22;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration INSTALL_TIMEOUT = Duration.ofSeconds(60);

    private static final String TMP_CERT = "/tmp/tapstate-cluster.crt";
    private static final String TMP_KEY = "/tmp/tapstate-cluster.key";
    private static final String TMP_CONF = "/tmp/tapstate-cluster.conf";

    private final SshClient sshClient;
    private final ClusterRuntimeConfigs configs;

    public TlsNodeInstaller(SshClient sshClient, ClusterRuntimeConfigs configs) {
        this.sshClient = sshClient;
        this.configs = configs;
    }

    /**
     * Upload the certificate chain, private key and TLS site configuration over
     * an existing session, then restart Nginx after a successful config test.
     *
     * @throws IacException with code {@code IAC_TLS_CERTIFICATE_INSTALL_FAILED}
     *     when any upload or remote command fails
     */
    public void install(SshSession session, String domain, TlsCertificate certificate) {
        try {
            session.uploadFile(TMP_CERT,
                certificate.getCertificate().getBytes(StandardCharsets.UTF_8));
            session.uploadFile(TMP_KEY,
                certificate.getPrivateKey().getBytes(StandardCharsets.UTF_8));
            session.uploadFile(TMP_CONF,
                configs.nginxConf(domain).getBytes(StandardCharsets.UTF_8));

            String command = "set -e"
                + " && sudo install -d -m 755 " + ClusterRuntimeConfigs.REMOTE_NGINX_SSL_DIR
                + " && sudo install -m 644 " + TMP_CERT + " "
                + ClusterRuntimeConfigs.REMOTE_NGINX_CERT_PATH
                + " && sudo install -m 600 " + TMP_KEY + " "
                + ClusterRuntimeConfigs.REMOTE_NGINX_KEY_PATH
                + " && sudo mv " + TMP_CONF + " " + ClusterRuntimeConfigs.REMOTE_NGINX_CONF_PATH
                + " && sudo nginx -t"
                + " && sudo systemctl restart nginx"
                + " && rm -f " + TMP_CERT + " " + TMP_KEY;
            runRemote(session, command);
        } catch (IOException e) {
            throw new IacException(AppErrorCode.IAC_TLS_CERTIFICATE_INSTALL_FAILED, e,
                "TLS material upload failed: " + safeMessage(e));
        }
    }

    /**
     * Open an authenticated SSH session to a Cluster node. The HTTP CONNECT
     * proxy is read from the JVM network properties, matching node provisioning.
     */
    public SshSession connect(String host, String privateKeyPem) throws IOException {
        return sshClient.connect(host, SSH_PORT, SSH_USER, privateKeyPem,
            (int) CONNECT_TIMEOUT.toMillis(), proxyHost(), proxyPort());
    }

    private void runRemote(SshSession session, String command) {
        try {
            CommandResult result = session.exec(
                "sudo bash -c " + shellQuote(command), INSTALL_TIMEOUT);
            if (!result.succeeded()) {
                throw new IacException(AppErrorCode.IAC_TLS_CERTIFICATE_INSTALL_FAILED,
                    "TLS install command failed (" + result.exitCode() + "): "
                        + abbreviate(result.stderr()));
            }
        } catch (IOException e) {
            throw new IacException(AppErrorCode.IAC_TLS_CERTIFICATE_INSTALL_FAILED, e,
                "TLS install command failed: " + safeMessage(e));
        }
    }

    private static String proxyHost() {
        String host = System.getProperty("https.proxyHost", System.getProperty("http.proxyHost"));
        return host == null || host.isBlank() ? null : host;
    }

    private static int proxyPort() {
        String port = System.getProperty("https.proxyPort",
            System.getProperty("http.proxyPort", "0"));
        try {
            return Integer.parseInt(port.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String safeMessage(Throwable t) {
        return t != null && t.getMessage() != null ? t.getMessage() : t != null ? t.toString() : "unknown";
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed;
    }
}
