package io.tapstate.cloud.tls;

import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.iac.IacException;
import io.tapstate.cloud.iac.config.ClusterRuntimeConfigs;
import io.tapstate.cloud.iac.ssh.CommandResult;
import io.tapstate.cloud.iac.ssh.SshClient;
import io.tapstate.cloud.iac.ssh.SshSession;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TlsNodeInstaller}: certificate material upload layout,
 * strict mode/permissions in the reload command, and coded failure handling.
 */
class TlsNodeInstallerTest {

    private static final String DOMAIN = "6475a2b3c1d4e5f6a7b8c9d1.dev.cloud.tapstate.com";

    private SshClient sshClient;
    private SshSession session;
    private TlsNodeInstaller installer;

    @BeforeEach
    void setUp() {
        sshClient = mock(SshClient.class);
        session = mock(SshSession.class);
        installer = new TlsNodeInstaller(sshClient, new ClusterRuntimeConfigs());
    }

    @Test
    void uploadsMaterialAndReloadsNginxWithRestrictivePermissions() throws Exception {
        when(session.exec(any(), any())).thenReturn(new CommandResult(0, "", ""));
        TlsCertificate certificate = certificate();

        installer.install(session, DOMAIN, certificate);

        verify(session).uploadFile(eq("/tmp/tapstate-cluster.crt"),
            eq(certificate.getCertificate().getBytes(StandardCharsets.UTF_8)));
        verify(session).uploadFile(eq("/tmp/tapstate-cluster.key"),
            eq(certificate.getPrivateKey().getBytes(StandardCharsets.UTF_8)));
        verify(session).uploadFile(eq("/tmp/tapstate-cluster.conf"), any(byte[].class));

        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        verify(session).exec(command.capture(), any());
        String remote = command.getValue();
        assertTrue(remote.contains("install -m 644 /tmp/tapstate-cluster.crt "
            + ClusterRuntimeConfigs.REMOTE_NGINX_CERT_PATH), remote);
        assertTrue(remote.contains("install -m 600 /tmp/tapstate-cluster.key "
            + ClusterRuntimeConfigs.REMOTE_NGINX_KEY_PATH), "key must be 0600:\n" + remote);
        assertTrue(remote.contains("mv /tmp/tapstate-cluster.conf "
            + ClusterRuntimeConfigs.REMOTE_NGINX_CONF_PATH), remote);
        assertTrue(remote.contains("nginx -t"), "config must be tested before restart:\n" + remote);
        assertTrue(remote.contains("systemctl restart nginx"),
            "Nginx must restart to pick up the new certificate:\n" + remote);
    }

    @Test
    void failedNginxConfigTestRaisesCodedInstallFailure() throws Exception {
        when(session.exec(any(), any()))
            .thenReturn(new CommandResult(1, "", "nginx: [emerg] cannot load certificate"));

        IacException error = assertThrows(IacException.class,
            () -> installer.install(session, DOMAIN, certificate()));
        assertEquals(AppErrorCode.IAC_TLS_CERTIFICATE_INSTALL_FAILED, error.errorCode());
    }

    @Test
    void uploadFailureRaisesCodedInstallFailure() throws Exception {
        org.mockito.Mockito.doThrow(new java.io.IOException("sftp failure"))
            .when(session).uploadFile(anyString(), any(byte[].class));

        IacException error = assertThrows(IacException.class,
            () -> installer.install(session, DOMAIN, certificate()));
        assertEquals(AppErrorCode.IAC_TLS_CERTIFICATE_INSTALL_FAILED, error.errorCode());
    }

    private static TlsCertificate certificate() {
        return TlsCertificate.builder()
            .id(new ObjectId())
            .domain("*.dev.cloud.tapstate.com")
            .certificate("-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----\n")
            .privateKey("-----BEGIN PRIVATE KEY-----\nYmFy\n-----END PRIVATE KEY-----\n")
            .expiryDate(Instant.now().plus(30, ChronoUnit.DAYS))
            .build();
    }
}
