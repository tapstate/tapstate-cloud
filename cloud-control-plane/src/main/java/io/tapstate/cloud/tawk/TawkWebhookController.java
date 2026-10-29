package io.tapstate.cloud.tawk;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/api/webhooks/tawk")
public class TawkWebhookController {

    private static final Pattern EVENT_ID_PATTERN = Pattern.compile("[A-Za-z0-9:_-]{1,128}");
    private static final int MAX_BODY_BYTES = 262_144;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final TawkWebhookService webhookService;
    private final String webhookSecret;
    private final String expectedPropertyId;

    public TawkWebhookController(
            TawkWebhookService webhookService,
            @Value("${tapstate.cloud.tawk.webhook-secret:}") String webhookSecret,
            @Value("${tapstate.cloud.tawk.property-id:}") String expectedPropertyId) {
        this.webhookService = webhookService;
        this.webhookSecret = webhookSecret;
        this.expectedPropertyId = expectedPropertyId;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Hidden
    public ResponseEntity<Void> receive(
            @RequestHeader(value = "X-Tawk-Signature", required = false) String signature,
            @RequestHeader(value = "X-Hook-Event-Id", required = false) String eventId,
            @RequestBody byte[] rawBody) {
        if (webhookSecret == null || webhookSecret.isBlank() || expectedPropertyId == null
                || expectedPropertyId.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (rawBody == null || rawBody.length > MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        if (!TawkWebhookSignature.matches(rawBody, signature, webhookSecret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (eventId == null || !EVENT_ID_PATTERN.matcher(eventId).matches()) {
            return ResponseEntity.badRequest().build();
        }

        TawkWebhookPayload payload;
        try {
            payload = TawkWebhookPayload.parse(rawBody, OBJECT_MAPPER);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
        if (payload == null || !expectedPropertyId.equals(payload.propertyId())) {
            return ResponseEntity.badRequest().build();
        }
        if (!webhookService.accepts(payload.eventType())) {
            return ResponseEntity.noContent().build();
        }

        webhookService.record(eventId, payload);
        return ResponseEntity.accepted().build();
    }
}
