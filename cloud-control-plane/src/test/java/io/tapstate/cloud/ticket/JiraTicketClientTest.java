package io.tapstate.cloud.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JiraTicketClientTest {

    private static final String CLOUD_ID = "cloud-site-7";
    private static final String PRIVATE_RESPONSE = "secret-or-private-jira-response";

    @Test
    void exchangesServiceAccountCredentialsAndCachesTheScopedAccessToken() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        HttpClient http = httpClient(requests,
            response(200, "{\"access_token\":\"jira-access-token\",\"expires_in\":3600,\"token_type\":\"Bearer\"}"),
            response(200, "{\"values\":[],\"isLastPage\":true}"),
            response(200, "{\"values\":[],\"isLastPage\":true}"));
        JiraAccessTokenProvider accessTokens = new JiraAccessTokenProvider(http, 10, Clock.systemUTC());
        JiraTicketClient client = new JiraTicketClient(CLOUD_ID, "71", "136", "service-client",
            "service-secret", http, 10, accessTokens);

        assertEquals(List.of(), client.findExactCustomers("cloud.user@example.com"));
        assertEquals(List.of(), client.findExactCustomers("cloud.user@example.com"));

        assertEquals(3, requests.size());
        assertEquals("POST", requests.get(0).method());
        assertEquals("https://auth.atlassian.com/oauth/token", requests.get(0).uri().toString());
        assertEquals("application/x-www-form-urlencoded", requests.get(0).headers().firstValue("Content-Type").orElseThrow());
        assertEquals("grant_type=client_credentials&client_id=service-client&client_secret=service-secret",
            requestBody(requests.get(0)));
        assertEquals("GET", requests.get(1).method());
        assertEquals("https://api.atlassian.com/ex/jira/cloud-site-7/rest/servicedeskapi/servicedesk/71/customer?query=cloud.user%40example.com&start=0&limit=50",
            requests.get(1).uri().toString());
        assertEquals("opt-in", requests.get(1).headers().firstValue("X-ExperimentalApi").orElseThrow());
        assertEquals("Bearer jira-access-token", requests.get(1).headers().firstValue("Authorization").orElseThrow());
        assertEquals("Bearer jira-access-token", requests.get(2).headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    void mapsAuthenticationRateLimitAndServerErrorsWithoutExposingJiraResponseBodies() throws Exception {
        assertCreateError(401, AppErrorCode.TICKET_INTEGRATION_FAILED, 502);
        assertCreateError(403, AppErrorCode.TICKET_INTEGRATION_FAILED, 502);
        assertCreateError(429, AppErrorCode.TICKET_INTEGRATION_FAILED, 502);
        assertCreateError(500, AppErrorCode.TICKET_SUBMISSION_UNCERTAIN, 503);
    }

    @Test
    void mapsCreateTimeoutToUncertainAndSendsOnlyOneExternalWrite() throws Exception {
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenThrow(new HttpTimeoutException("request timeout"));
        JiraTicketClient client = clientWith(http);

        BusinessException failure = assertThrows(BusinessException.class,
            () -> client.createRequest("customer-7", "Title", "Description"));

        assertEquals(503, failure.getHttpStatus());
        assertEquals(AppErrorCode.TICKET_SUBMISSION_UNCERTAIN, failure.getErrorCode());
        assertFalse(failure.getMessage().contains(PRIVATE_RESPONSE));
        verify(http).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void mapsRequestTypeFieldRejectionToAStableClientError() throws Exception {
        HttpClient http = httpClient(new ArrayList<>(), response(400, PRIVATE_RESPONSE));
        JiraTicketClient client = clientWith(http);

        BusinessException failure = assertThrows(BusinessException.class,
            () -> client.createRequest("customer-7", "Title", "Description"));

        assertEquals(400, failure.getHttpStatus());
        assertEquals(AppErrorCode.TICKET_REQUEST_REJECTED, failure.getErrorCode());
        assertFalse(failure.getMessage().contains(PRIVATE_RESPONSE));
        verify(http).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void failsClosedWhenTheRegularTicketCredentialsAreMissing() {
        JiraTicketClient client = new JiraTicketClient(CLOUD_ID, "71", "136", "", "",
            mock(HttpClient.class), 10, mock(JiraAccessTokenProvider.class));

        BusinessException failure = assertThrows(BusinessException.class,
            () -> client.createRequest("customer-7", "Title", "Description"));

        assertEquals(503, failure.getHttpStatus());
        assertEquals(AppErrorCode.TICKET_NOT_CONFIGURED, failure.getErrorCode());
    }

    @Test
    void doesNotMisreportTokenExchangeFailureAsAnUncertainJiraSubmission() {
        HttpClient http = mock(HttpClient.class);
        JiraAccessTokenProvider accessTokens = mock(JiraAccessTokenProvider.class);
        when(accessTokens.getAccessToken(anyString(), anyString()))
            .thenThrow(new BusinessException(AppErrorCode.TICKET_INTEGRATION_FAILED, 502));
        JiraTicketClient client = new JiraTicketClient(CLOUD_ID, "71", "136", "service-client", "service-secret",
            http, 10, accessTokens);

        BusinessException failure = assertThrows(BusinessException.class,
            () -> client.createRequest("customer-7", "Title", "Description"));

        assertEquals(AppErrorCode.TICKET_INTEGRATION_FAILED, failure.getErrorCode());
        assertEquals(502, failure.getHttpStatus());
    }

    private static void assertCreateError(int status, AppErrorCode expectedCode, int expectedStatus) throws Exception {
        HttpClient http = httpClient(new ArrayList<>(), response(status, PRIVATE_RESPONSE));
        JiraTicketClient client = clientWith(http);

        BusinessException failure = assertThrows(BusinessException.class,
            () -> client.createRequest("customer-7", "Title", "Description"));

        assertEquals(expectedStatus, failure.getHttpStatus());
        assertEquals(expectedCode, failure.getErrorCode());
        assertFalse(failure.getMessage().contains(PRIVATE_RESPONSE));
        verify(http).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    private static JiraTicketClient clientWith(HttpClient http) {
        JiraAccessTokenProvider accessTokens = mock(JiraAccessTokenProvider.class);
        when(accessTokens.getAccessToken(anyString(), anyString())).thenReturn("jira-access-token");
        return new JiraTicketClient(CLOUD_ID, "71", "136", "service-client", "service-secret",
            http, 10, accessTokens);
    }

    private static HttpClient httpClient(List<HttpRequest> requests, StubResponse... responses) throws Exception {
        HttpClient client = mock(HttpClient.class);
        AtomicInteger index = new AtomicInteger();
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenAnswer(invocation -> {
                requests.add(invocation.getArgument(0));
                int responseIndex = index.getAndIncrement();
                if (responseIndex >= responses.length) {
                    throw new AssertionError("Unexpected HTTP request: " + requests.getLast().uri());
                }
                StubResponse next = responses[responseIndex];
                HttpResponse<String> response = mock(HttpResponse.class);
                when(response.statusCode()).thenReturn(next.status());
                when(response.body()).thenReturn(next.body());
                return response;
            });
        return client;
    }

    private static String requestBody(HttpRequest request) throws Exception {
        CompletableFuture<String> body = new CompletableFuture<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer item) {
                ByteBuffer copy = item.slice();
                byte[] bytes = new byte[copy.remaining()];
                copy.get(bytes);
                output.writeBytes(bytes);
            }
            @Override public void onError(Throwable failure) { body.completeExceptionally(failure); }
            @Override public void onComplete() { body.complete(output.toString(StandardCharsets.UTF_8)); }
        });
        return body.get(1, TimeUnit.SECONDS);
    }

    private static StubResponse response(int status, String body) {
        return new StubResponse(status, body);
    }

    private record StubResponse(int status, String body) {}
}
