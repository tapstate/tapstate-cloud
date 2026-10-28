package io.tapstate.cloud.tawk;

import io.swagger.v3.oas.annotations.Hidden;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Receives an authenticated notification signal after the Tawk offline form is submitted. */
@RestController
@RequestMapping("/v1/api/feedback/tawk")
public class TawkOfflineSubmissionController {

    private static final Pattern UUID_PATTERN = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final TawkWebhookService webhookService;
    private final String expectedPropertyId;

    public TawkOfflineSubmissionController(
            TawkWebhookService webhookService,
            @Value("${tapstate.cloud.tawk.property-id:}") String expectedPropertyId) {
        this.webhookService = webhookService;
        this.expectedPropertyId = expectedPropertyId;
    }

    @PostMapping("/offline-submissions")
    @Hidden
    public ResponseEntity<Map<String, Boolean>> receive(@RequestBody OfflineSubmissionRequest request) {
        if (expectedPropertyId == null || expectedPropertyId.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (request == null || request.propertyId() == null
                || !expectedPropertyId.equals(request.propertyId())
                || request.eventId() == null || !UUID_PATTERN.matcher(request.eventId()).matches()) {
            return ResponseEntity.badRequest().build();
        }

        UUID eventId = UUID.fromString(request.eventId());
        webhookService.recordOfflineSubmission(eventId.toString(), expectedPropertyId);
        return ResponseEntity.accepted().body(Map.of("queued", true));
    }

    public record OfflineSubmissionRequest(String eventId, String propertyId) {}
}
