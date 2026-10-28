package io.tapstate.cloud.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class JiraAccessTokenProviderTest {

    @Test
    void refreshesTheAccessTokenBeforeItExpires() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-27T00:00:00Z"));
        HttpClient http = httpClient(
            response(200, "{\"access_token\":\"first\",\"expires_in\":3600,\"token_type\":\"Bearer\"}"),
            response(200, "{\"access_token\":\"second\",\"expires_in\":3600,\"token_type\":\"Bearer\"}"));
        JiraAccessTokenProvider provider = new JiraAccessTokenProvider(http, 10, clock);

        assertEquals("first", provider.getAccessToken("client-id", "client-secret"));
        clock.advanceSeconds(3541);
        assertEquals("second", provider.getAccessToken("client-id", "client-secret"));
    }

    @Test
    void rejectsANonBearerTokenResponse() throws Exception {
        HttpClient http = httpClient(response(200,
            "{\"access_token\":\"not-bearer\",\"expires_in\":3600,\"token_type\":\"Basic\"}"));
        JiraAccessTokenProvider provider = new JiraAccessTokenProvider(http, 10, Clock.systemUTC());

        BusinessException failure = assertThrows(BusinessException.class,
            () -> provider.getAccessToken("client-id", "client-secret"));

        assertEquals(AppErrorCode.TICKET_INTEGRATION_FAILED, failure.getErrorCode());
    }

    @Test
    void mapsAnEmptyTokenResponseToASafeIntegrationError() throws Exception {
        HttpClient http = httpClient(response(200, ""));
        JiraAccessTokenProvider provider = new JiraAccessTokenProvider(http, 10, Clock.systemUTC());

        BusinessException failure = assertThrows(BusinessException.class,
            () -> provider.getAccessToken("client-id", "client-secret"));

        assertEquals(AppErrorCode.TICKET_INTEGRATION_FAILED, failure.getErrorCode());
        assertFalse(failure.getMessage().contains("client-secret"));
    }

    @Test
    void simultaneousCallersShareOneTokenExchange() throws Exception {
        HttpClient http = httpClient(response(200,
            "{\"access_token\":\"shared\",\"expires_in\":3600,\"token_type\":\"Bearer\"}"));
        JiraAccessTokenProvider provider = new JiraAccessTokenProvider(http, 10, Clock.systemUTC());
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return provider.getAccessToken("client-id", "client-secret");
                }));
            }
            start.countDown();
            for (var future : futures) assertEquals("shared", future.get(5, TimeUnit.SECONDS));
        }
        verify(http, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void doesNotCacheARejectedExchangeOrExposeItsResponse() throws Exception {
        HttpClient http = httpClient(
            response(401, "private-response-and-client-secret"),
            response(200, "{\"access_token\":\"recovered\",\"expires_in\":3600,\"token_type\":\"Bearer\"}"));
        JiraAccessTokenProvider provider = new JiraAccessTokenProvider(http, 10, Clock.systemUTC());

        BusinessException failure = assertThrows(BusinessException.class,
            () -> provider.getAccessToken("client-id", "client-secret"));
        assertEquals(AppErrorCode.TICKET_INTEGRATION_FAILED, failure.getErrorCode());
        assertFalse(failure.getMessage().contains("client-secret"));
        assertEquals("recovered", provider.getAccessToken("client-id", "client-secret"));
    }

    private static HttpClient httpClient(StubResponse... responses) throws Exception {
        HttpClient client = mock(HttpClient.class);
        AtomicInteger index = new AtomicInteger();
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenAnswer(invocation -> {
                int responseIndex = index.getAndIncrement();
                if (responseIndex >= responses.length) throw new AssertionError("Unexpected token request");
                StubResponse next = responses[responseIndex];
                HttpResponse<String> response = mock(HttpResponse.class);
                when(response.statusCode()).thenReturn(next.status());
                when(response.body()).thenReturn(next.body());
                return response;
            });
        return client;
    }

    private static StubResponse response(int status, String body) {
        return new StubResponse(status, body);
    }

    private record StubResponse(int status, String body) {}

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        private void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
