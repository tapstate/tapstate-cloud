package io.tapstate.cloud.tawk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class TawkOfflineSubmissionControllerTest {

    private static final String PROPERTY_ID = "property-1";
    private static final String EVENT_ID = "d63a5409-7796-48cb-9c94-f0420dcb0c31";

    @Mock
    private TawkWebhookService webhookService;

    @Test
    void queuesOnlyAValidSubmissionForTheConfiguredProperty() {
        TawkOfflineSubmissionController controller = new TawkOfflineSubmissionController(
            webhookService, PROPERTY_ID);

        ResponseEntity<?> response = controller.receive(
            new TawkOfflineSubmissionController.OfflineSubmissionRequest(EVENT_ID, PROPERTY_ID));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(webhookService).recordOfflineSubmission(EVENT_ID, PROPERTY_ID);
    }

    @Test
    void rejectsMismatchedPropertyAndMalformedEventId() {
        TawkOfflineSubmissionController controller = new TawkOfflineSubmissionController(
            webhookService, PROPERTY_ID);

        ResponseEntity<?> mismatched = controller.receive(
            new TawkOfflineSubmissionController.OfflineSubmissionRequest(EVENT_ID, "property-2"));
        ResponseEntity<?> malformed = controller.receive(
            new TawkOfflineSubmissionController.OfflineSubmissionRequest("not-a-uuid", PROPERTY_ID));

        assertEquals(HttpStatus.BAD_REQUEST, mismatched.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, malformed.getStatusCode());
        verify(webhookService, never()).recordOfflineSubmission(EVENT_ID, PROPERTY_ID);
    }

    @Test
    void failsClosedWhenNoPropertyIsConfigured() {
        TawkOfflineSubmissionController controller = new TawkOfflineSubmissionController(
            webhookService, " ");

        ResponseEntity<?> response = controller.receive(
            new TawkOfflineSubmissionController.OfflineSubmissionRequest(EVENT_ID, PROPERTY_ID));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        verify(webhookService, never()).recordOfflineSubmission(EVENT_ID, PROPERTY_ID);
    }
}
