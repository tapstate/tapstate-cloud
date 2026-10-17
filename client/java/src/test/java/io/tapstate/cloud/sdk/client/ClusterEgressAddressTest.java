package io.tapstate.cloud.sdk.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ClusterEgressAddressTest {
    @Test
    void eachReadUsesTheRuntimeIdentityAndCurrentServerMetadata() throws Exception {
        AtomicReference<String> ip = new AtomicReference<>("203.0.113.42");
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/api/clusters/cluster-one/egress-address", exchange -> {
            calls.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = ("{\"code\":\"ok\",\"data\":{\"organizationId\":\"org-one\","
                + "\"clusterId\":\"cluster-one\",\"ec2PublicIp\":\"" + ip.get()
                + "\",\"egressAddressStatus\":\"STABLE\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            CloudControlPlaneSdk sdk = sdk(server);
            assertEquals("203.0.113.42", sdk.currentEgressAddress().ec2PublicIp());
            ip.set("203.0.113.43");
            assertEquals(new ClusterEgressAddress("org-one", "cluster-one", "203.0.113.43", "STABLE"),
                sdk.currentEgressAddress());
            assertEquals("Bearer private-token", authorization.get());
            assertEquals(2, calls.get());
        } finally { server.stop(0); }
    }

    @Test
    void invalidAndCrossClusterResponsesFailWithoutEchoingTheirBody() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicInteger status = new AtomicInteger(200);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length == 0 ? -1 : bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            CloudControlPlaneSdk sdk = sdk(server);
            for (String invalid : new String[] {"", "null", "private-token-not-json",
                "{\"code\":\"ok\",\"data\":{\"organizationId\":\"org-one\",\"clusterId\":\"other\"}}",
                "{\"code\":\"ok\",\"data\":{\"organizationId\":\"org-one\",\"clusterId\":\"cluster-one\",\"ec2PublicIp\":12}}"}) {
                body.set(invalid);
                var failure = assertThrows(CloudControlPlaneException.class, sdk::currentEgressAddress);
                assertEquals("sdk.context-unavailable", failure.getErrorCode());
                assertFalse(failure.getMessage().contains("private-token"));
            }
            status.set(401);
            body.set("private-token-rejected");
            assertEquals(401, assertThrows(CloudControlPlaneException.class, sdk::currentEgressAddress).getHttpStatus());
        } finally { server.stop(0); }
    }

    private CloudControlPlaneSdk sdk(HttpServer server) {
        return CloudControlPlaneSdk.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
            .clusterId("cluster-one").sdkStaticToken("private-token").build();
    }
}
