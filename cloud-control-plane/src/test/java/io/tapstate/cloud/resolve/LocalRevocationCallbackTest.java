package io.tapstate.cloud.resolve;

import com.sun.net.httpserver.HttpServer;
import io.tapstate.cloud.config.JwksRotationService;
import io.tapstate.cloud.identity.AuthController;
import io.tapstate.cloud.identity.EmailVerificationService;
import io.tapstate.cloud.identity.InvitationService;
import io.tapstate.cloud.identity.TrialCodePolicy;
import io.tapstate.cloud.identity.UserRepository;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

import java.net.InetSocketAddress;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.tapstate.cloud.resolve.LocalClusterEndpointsTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalRevocationCallbackTest {
    @Test
    void sendsRealSignedCallbackToConfiguredInternalOriginInsteadOfBrowserOrigin() throws Exception {
        var requestQuery = new AtomicReference<String>();
        var requestMethod = new AtomicReference<String>();
        var requestHost = new AtomicReference<String>();
        var backend = NetworkInterface.networkInterfaces().flatMap(NetworkInterface::inetAddresses)
            .filter(address -> address instanceof Inet4Address && !address.isLoopbackAddress()
                && !address.isAnyLocalAddress() && !address.isLinkLocalAddress())
            .findFirst().orElseThrow(() -> new IllegalStateException("A non-loopback IPv4 test interface is required"));
        HttpServer server = HttpServer.create(new InetSocketAddress(backend, 0), 0);
        server.createContext("/auth/invalidate-session", exchange -> {
            requestMethod.set(exchange.getRequestMethod());
            requestHost.set(exchange.getRequestHeaders().getFirst("Host"));
            requestQuery.set(exchange.getRequestURI().getRawQuery());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            String clusterOrigin = "http://localhost:" + server.getAddress().getPort();
            String backendAuthority = backend.getHostAddress() + ":" + server.getAddress().getPort();
            var deployment = properties("http://" + backendAuthority);
            deployment.getClusters().get(CLUSTER_ID).setPublicOrigin(clusterOrigin);
            var resolver = local(deployment);
            var keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
            var keys = mock(JwksRotationService.class);
            when(keys.buildActiveSigningKeyPair()).thenReturn(keyPair);
            var records = mock(JtiRecordRepository.class);
            var record = JtiRecord.builder().jti("local-jti").clusterId(new ObjectId(CLUSTER_ID))
                .clusterBaseUri(clusterOrigin).build();
            when(records.findByUserEmailAndRevokedAtIsNull("user@example.com")).thenReturn(List.of(record));
            var controller = new AuthController(mock(UserRepository.class),
                mock(EmailVerificationService.class), mock(InvitationService.class),
                mock(TrialCodePolicy.class),
                mock(AuthenticationManager.class), records, new CallbackSignService(keys), resolver,
                mock(ClientRegistrationRepository.class), mock(io.tapstate.cloud.config.LegalConfig.class));
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user@example.com", null, List.of()));
            assertEquals(200, controller.logout(new MockHttpServletRequest()).getStatusCode().value());
            assertEquals("POST", requestMethod.get());
            assertEquals(backendAuthority, requestHost.get());
            assertNotNull(requestQuery.get());
            Map<String, String> values = java.util.Arrays.stream(requestQuery.get().split("&"))
                .map(part -> part.split("=", 2))
                .collect(Collectors.toMap(part -> part[0], part -> URLDecoder.decode(part[1], StandardCharsets.UTF_8)));
            assertEquals("local-jti", values.get("jti"));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(keyPair.getPublic());
            signature.update(("POST|" + values.get("ts") + "|" + values.get("nonce") + "|local-jti")
                .getBytes(StandardCharsets.UTF_8));
            assertTrue(signature.verify(Base64.getUrlDecoder().decode(values.get("sign"))));
            assertNotNull(record.getRevokedAt());
            verify(records).save(record);
        } finally {
            SecurityContextHolder.clearContext();
            server.stop(0);
        }
    }
}
