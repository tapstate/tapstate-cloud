package io.tapstate.cloud.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class JiraCustomerProvisionerTest {

    private static final String EMAIL = "cloud.user@example.com";
    private static final String BASE_URL = "https://support.example.atlassian.net";
    private static final String ADMIN_EMAIL = "customer-admin@example.com";
    private static final String ADMIN_TOKEN = "customer-admin-secret";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void createsCustomerAndAddsItsAccountIdToTheTargetServiceDesk() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        HttpClient http = httpClient(requests,
            response(200, "[]"),
            response(201, "{\"accountId\":\"customer-17\",\"emailAddress\":\"cloud.user@example.com\"}"),
            response(204, ""));

        provisioner(http).createOrAddCustomer(EMAIL, "Cloud User");

        assertEquals(3, requests.size());
        assertEquals("GET", requests.get(0).method());
        assertEquals("https://support.example.atlassian.net/rest/api/3/user/search?query=cloud.user%40example.com&startAt=0&maxResults=50",
            requests.get(0).uri().toString());
        assertEquals("POST", requests.get(1).method());
        assertEquals("https://support.example.atlassian.net/rest/servicedeskapi/customer?strictConflictStatusCode=true",
            requests.get(1).uri().toString());
        JsonNode createBody = MAPPER.readTree(requestBody(requests.get(1)));
        assertEquals(EMAIL, createBody.path("email").asText());
        assertEquals("Cloud User", createBody.path("displayName").asText());
        assertEquals("POST", requests.get(2).method());
        assertEquals("https://support.example.atlassian.net/rest/servicedeskapi/servicedesk/71/customer",
            requests.get(2).uri().toString());
        JsonNode addBody = MAPPER.readTree(requestBody(requests.get(2)));
        assertEquals("customer-17", addBody.path("accountIds").get(0).asText());
        assertAdminAuthorization(requests);
    }

    @Test
    void reusesAnExistingJiraUserAndDoesNotCreateAnotherCustomer() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        HttpClient http = httpClient(requests,
            response(200, "[{\"accountId\":\"existing-23\",\"emailAddress\":\"cloud.user@example.com\",\"active\":true}]"),
            response(204, ""));

        provisioner(http).createOrAddCustomer(EMAIL, "Cloud User");

        assertEquals(2, requests.size());
        assertEquals("/rest/api/3/user/search", requests.get(0).uri().getPath());
        assertEquals("/rest/servicedeskapi/servicedesk/71/customer", requests.get(1).uri().getPath());
        assertEquals("existing-23", MAPPER.readTree(requestBody(requests.get(1)))
            .path("accountIds").get(0).asText());
    }

    @Test
    void recoversFromAConcurrentCustomerCreateByResolvingTheExactEmailAgain() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        HttpClient http = httpClient(requests,
            response(200, "[]"),
            response(409, "{}"),
            response(200, "[{\"accountId\":\"created-by-peer\",\"emailAddress\":\"cloud.user@example.com\",\"active\":true}]"),
            response(204, ""));

        provisioner(http).createOrAddCustomer(EMAIL, "Cloud User");

        assertEquals(4, requests.size());
        assertEquals("POST", requests.get(1).method());
        assertEquals("GET", requests.get(2).method());
        assertEquals("created-by-peer", MAPPER.readTree(requestBody(requests.get(3)))
            .path("accountIds").get(0).asText());
    }

    @Test
    void refusesAmbiguousJiraIdentityWithoutAddingAnyone() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        HttpClient http = httpClient(requests,
            response(200, "[{\"accountId\":\"customer-1\",\"emailAddress\":\"cloud.user@example.com\",\"active\":true},"
                + "{\"accountId\":\"customer-2\",\"emailAddress\":\"cloud.user@example.com\",\"active\":true}]"));

        BusinessException failure = assertThrows(BusinessException.class,
            () -> provisioner(http).createOrAddCustomer(EMAIL, "Cloud User"));

        assertEquals(409, failure.getHttpStatus());
        assertEquals(AppErrorCode.TICKET_CUSTOMER_NOT_READY, failure.getErrorCode());
        assertEquals(1, requests.size());
    }

    @Test
    void refusesToLinkAnExistingAccountWhenItsEmailCannotBeVerified() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        HttpClient http = httpClient(requests,
            response(200, "[]"),
            response(409, "{}"),
            response(200, "[{\"accountId\":\"hidden-customer\",\"active\":true}]"));

        BusinessException failure = assertThrows(BusinessException.class,
            () -> provisioner(http).createOrAddCustomer(EMAIL, "Cloud User"));

        assertEquals(409, failure.getHttpStatus());
        assertEquals(AppErrorCode.TICKET_CUSTOMER_NOT_READY, failure.getErrorCode());
        assertEquals(3, requests.size());
    }

    @Test
    void doesNotCallJiraWhenCustomerProvisioningIsDisabled() {
        HttpClient http = mock(HttpClient.class);
        JiraCustomerProvisioner disabled = new JiraCustomerProvisioner(
            false, false, BASE_URL, "71", ADMIN_EMAIL, ADMIN_TOKEN, 10, http);

        BusinessException failure = assertThrows(BusinessException.class,
            () -> disabled.createOrAddCustomer(EMAIL, "Cloud User"));

        assertEquals(503, failure.getHttpStatus());
        assertEquals(AppErrorCode.TICKET_NOT_CONFIGURED, failure.getErrorCode());
    }

    @Test
    void refusesAutomaticProvisioningUnlessTheOperatorConfirmsASupportOnlySite() {
        JiraCustomerProvisioner sharedSite = new JiraCustomerProvisioner(
            true, false, BASE_URL, "71", ADMIN_EMAIL, ADMIN_TOKEN, 10, mock(HttpClient.class));

        BusinessException failure = assertThrows(BusinessException.class,
            () -> sharedSite.createOrAddCustomer(EMAIL, "Cloud User"));

        assertEquals(503, failure.getHttpStatus());
        assertEquals(AppErrorCode.TICKET_NOT_CONFIGURED, failure.getErrorCode());
    }

    @Test
    void customerCreatePermissionFailureStopsBeforeServiceDeskLinking() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        HttpClient http = httpClient(requests, response(200, "[]"), response(403, "{}"));

        BusinessException failure = assertThrows(BusinessException.class,
            () -> provisioner(http).createOrAddCustomer(EMAIL, "Cloud User"));

        assertEquals(502, failure.getHttpStatus());
        assertEquals(AppErrorCode.TICKET_INTEGRATION_FAILED, failure.getErrorCode());
        assertEquals(2, requests.size());
    }

    private static JiraCustomerProvisioner provisioner(HttpClient http) {
        return new JiraCustomerProvisioner(true, true, BASE_URL, "71", ADMIN_EMAIL, ADMIN_TOKEN, 10, http);
    }

    private static HttpClient httpClient(List<HttpRequest> requests, StubResponse... responses) throws Exception {
        HttpClient client = mock(HttpClient.class);
        AtomicInteger index = new AtomicInteger();
        when(client.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
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
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                ByteBuffer copy = item.slice();
                byte[] bytes = new byte[copy.remaining()];
                copy.get(bytes);
                output.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable failure) {
                body.completeExceptionally(failure);
            }

            @Override
            public void onComplete() {
                body.complete(output.toString(StandardCharsets.UTF_8));
            }
        });
        return body.get(1, TimeUnit.SECONDS);
    }

    private static StubResponse response(int status, String body) {
        return new StubResponse(status, body);
    }

    private static void assertAdminAuthorization(List<HttpRequest> requests) {
        String credentials = ADMIN_EMAIL + ":" + ADMIN_TOKEN;
        String expected = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        requests.forEach(request -> assertEquals(expected, request.headers().firstValue("Authorization").orElseThrow()));
    }

    private record StubResponse(int status, String body) {}
}
