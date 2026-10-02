package io.tapstate.cloud.iac.ssh;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper;
import net.schmizz.sshj.xfer.InMemorySourceFile;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * {@link SshClient} backed by the SSHJ library.
 * <p>
 * Host keys are accepted without pinning: Cluster nodes are ephemeral, their
 * addresses change with every provision, and no pre-shared known-hosts material
 * exists. Authentication stays key-based with the per-Cluster generated key.
 */
@Component
public class SshjSshClient implements SshClient {

    @Override
    public SshSession connect(String host, int port, String user, String privateKeyPem,
                               int connectTimeoutMs, String proxyHost, int proxyPort)
        throws IOException {
        SSHClient ssh = new SSHClient();
        ssh.addHostKeyVerifier(new PromiscuousVerifier());
        ssh.setConnectTimeout(connectTimeoutMs);
        ssh.setTimeout(connectTimeoutMs);
        if (proxyHost != null && !proxyHost.isBlank()) {
            ssh.setSocketFactory(new HttpConnectSocketFactory(proxyHost, proxyPort));
        }
        try {
            ssh.connect(host, port);
            KeyPair keyPair = parseRsaKeyPair(privateKeyPem);
            ssh.authPublickey(user, new KeyPairWrapper(keyPair));
        } catch (IOException e) {
            disconnectQuietly(ssh);
            throw e;
        }
        return new SshjSshSession(ssh);
    }

    /** Decode a PKCS#8 PEM RSA private key and wrap it into a JCE key pair. */
    private static KeyPair parseRsaKeyPair(String pem) throws IOException {
        try {
            String body = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(body);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(der));

            // SSHJ's KeyPairWrapper also inspects the public key; derive it from
            // the CRT private key rather than carrying it separately.
            RSAPrivateCrtKey rsaPrivateKey = (RSAPrivateCrtKey) privateKey;
            PublicKey publicKey = keyFactory.generatePublic(
                new RSAPublicKeySpec(rsaPrivateKey.getModulus(),
                    rsaPrivateKey.getPublicExponent()));
            return new KeyPair(publicKey, privateKey);
        } catch (Exception e) {
            throw new IOException("Failed to parse PKCS#8 private key PEM", e);
        }
    }

    /**
     * SSHJ-backed session; one shell channel is opened per command.
     */
    private static final class SshjSshSession implements SshSession {

        private final SSHClient ssh;

        private SshjSshSession(SSHClient ssh) {
            this.ssh = ssh;
        }

        @Override
        public CommandResult exec(String command, Duration timeout) throws IOException {
            try (Session session = ssh.startSession()) {
                Session.Command cmd = session.exec(command);

                StringBuilder stdout = new StringBuilder();
                StringBuilder stderr = new StringBuilder();
                Thread outReader = drain(cmd.getInputStream(), stdout);
                Thread errReader = drain(cmd.getErrorStream(), stderr);

                cmd.join(timeout.toMillis(), TimeUnit.MILLISECONDS);
                boolean finished = !cmd.isOpen();
                outReader.join(TimeUnit.SECONDS.toMillis(5));
                errReader.join(TimeUnit.SECONDS.toMillis(5));
                if (!finished) {
                    cmd.close();
                    throw new IOException("Remote command timed out after " + timeout + ": " + command);
                }
                Integer exitStatus = cmd.getExitStatus();
                return new CommandResult(exitStatus != null ? exitStatus : -1,
                    stdout.toString(), stderr.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while awaiting remote command: " + command, e);
            }
        }

        @Override
        public void uploadFile(String remotePath, byte[] content) throws IOException {
            int lastSlash = remotePath.lastIndexOf('/');
            String remoteName = lastSlash >= 0 ? remotePath.substring(lastSlash + 1) : remotePath;
            try (SFTPClient sftp = ssh.newSFTPClient()) {
                if (lastSlash > 0) {
                    sftp.mkdirs(remotePath.substring(0, lastSlash));
                }
                sftp.put(byteSource(remoteName, content), remotePath);
            }
        }

        /** Wrap raw bytes as an SSHJ in-memory source file. */
        private static InMemorySourceFile byteSource(String name, byte[] content) {
            return new InMemorySourceFile() {
                @Override
                public String getName() {
                    return name;
                }

                @Override
                public long getLength() {
                    return content.length;
                }

                @Override
                public InputStream getInputStream() {
                    return new ByteArrayInputStream(content);
                }
            };
        }

        /** Drain an input stream into the target buffer on a daemon thread. */
        private static Thread drain(InputStream in, StringBuilder target) {
            Thread thread = new Thread(() -> {
                try {
                    target.append(IOUtils.readFully(in).toString(StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // Session/channel failure surfaces through join/exit handling.
                }
            });
            thread.setDaemon(true);
            thread.start();
            return thread;
        }

        @Override
        public void close() {
            disconnectQuietly(ssh);
        }
    }

    /** Best-effort disconnect, suppressing transport failures. */
    private static void disconnectQuietly(SSHClient ssh) {
        try {
            if (ssh.isConnected()) {
                ssh.disconnect();
            }
        } catch (IOException ignored) {
            // Nothing actionable: the connection is being torn down anyway.
        }
    }
}
