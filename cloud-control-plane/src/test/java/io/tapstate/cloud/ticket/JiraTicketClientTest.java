package io.tapstate.cloud.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JiraTicketClientTest {

    private static final String BASE_URL = "https://support.example.atlassian.net";
    private static final String PRIVATE_RESPONSE = "secret-or-private-jira-response";

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
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(400);
        when(response.body()).thenReturn(PRIVATE_RESPONSE);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
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
        JiraTicketClient client = new JiraTicketClient(BASE_URL, "71", "136", "", "", 3, 10);

        BusinessException failure = assertThrows(BusinessException.class,
            () -> client.createRequest("customer-7", "Title", "Description"));

        assertEquals(503, failure.getHttpStatus());
        assertEquals(AppErrorCode.TICKET_NOT_CONFIGURED, failure.getErrorCode());
    }

    private static void assertCreateError(int status, AppErrorCode expectedCode, int expectedStatus) throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(PRIVATE_RESPONSE);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        JiraTicketClient client = clientWith(http);

        BusinessException failure = assertThrows(BusinessException.class,
            () -> client.createRequest("customer-7", "Title", "Description"));

        assertEquals(expectedStatus, failure.getHttpStatus());
        assertEquals(expectedCode, failure.getErrorCode());
        assertFalse(failure.getMessage().contains(PRIVATE_RESPONSE));
        verify(http).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    private static JiraTicketClient clientWith(HttpClient http) {
        JiraTicketClient client = new JiraTicketClient(BASE_URL, "71", "136", "service@example.com",
            "service-secret", 3, 10);
        ReflectionTestUtils.setField(client, "httpClient", http);
        return client;
    }
}
