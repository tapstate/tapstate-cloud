package io.tapstate.cloud.notification;

import io.tapstate.cloud.event.AlertRecord;
import io.tapstate.cloud.event.EventSeverity;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EmailTemplateRendererTest {

    private final EmailTemplateRenderer renderer = new EmailTemplateRenderer();

    @Test
    @DisplayName("every email carries the shared shell: inline logo, brand line, website and support contact")
    void sharedShellIsPresent() {
        RenderedEmail code = renderer.renderVerificationCode("123456");
        RenderedEmail alert = renderer.renderAlert(alertRecord("acme-prod", "<script>alert(1)</script>"));

        for (RenderedEmail email : new RenderedEmail[]{code, alert}) {
            assertThat(email.inlineLogo()).isTrue();
            assertThat(email.htmlBody())
                .contains("cid:tapstate-logo")
                .contains("TAPSTATE CLOUD")
                .contains("https://tapstate.com")
                .contains("support@tapstate.com");
            assertThat(email.textBody()).contains("support@tapstate.com");
        }
    }

    @Test
    @DisplayName("the verification email highlights the code in both text and HTML bodies")
    void verificationCodeIsProminent() {
        RenderedEmail email = renderer.renderVerificationCode("123456");

        assertThat(email.subject()).isEqualTo("Your Tapstate verification code");
        assertThat(email.textBody()).contains("123456");
        assertThat(email.htmlBody())
            .contains("123456")
            .contains("letter-spacing:0.28em");
    }

    @Test
    @DisplayName("an alert email shows severity, the affected task and the event reason")
    void alertShowsSeverityTaskAndReason() {
        RenderedEmail email = renderer.renderAlert(alertRecord("acme-prod", "Task failed"));

        assertThat(email.subject()).isEqualTo("[CRITICAL] Task failed");
        assertThat(email.textBody())
            .contains("Severity: CRITICAL")
            .contains("Affected task: task-42")
            .contains("Cluster: acme-prod")
            .contains("Reason: source is unreachable");
        assertThat(email.htmlBody())
            .contains("#dc2626")
            .contains("CRITICAL")
            .contains("task-42")
            .contains("source is unreachable")
            .contains("acme-prod");
    }

    @Test
    @DisplayName("event fields are HTML-escaped before they reach the body")
    void eventFieldsAreEscaped() {
        RenderedEmail email = renderer.renderAlert(alertRecord("acme-prod", "<script>alert(1)</script>"));

        assertThat(email.htmlBody()).doesNotContain("<script>alert(1)</script>")
            .contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    @ParameterizedTest
    @CsvSource({"pipeline, acceptance-pipeline", "source, inventory", "cluster-node, node-a",
        "cluster-storage, cache", "custom-resource, extension-resource"})
    void clusterAlertEmailsIdentifyTheActualResource(String kind, String id) {
        var alert = alertRecord("acme-prod", "Cluster alert");
        alert.setEventType("cluster.alert.test.fault");
        alert.setDetails(Map.of("resourceKind", kind, "resourceId", id, "clusterId", "acme-prod"));

        var email = renderer.renderAlert(alert);

        assertThat(email.textBody()).contains("Affected resource: " + kind + " / " + id)
            .doesNotContain("Affected task: -");
        assertThat(email.htmlBody()).contains("Affected resource", kind + " / " + id);
    }

    @Test
    void resourceIdentityIsEscapedAndExistingTaskDetailsKeepTheirMeaning() {
        var alert = alertRecord("acme-prod", "Cluster alert");
        alert.setDetails(Map.of("resourceKind", "source", "resourceId", "<script>resource</script>"));
        var email = renderer.renderAlert(alert);
        assertThat(email.htmlBody()).contains("source / &lt;script&gt;resource&lt;/script&gt;")
            .doesNotContain("<script>resource</script>");
        alert.setDetails(Map.of("taskId", "task-42", "resourceKind", "source", "resourceId", "inventory"));
        assertThat(renderer.renderAlert(alert).textBody()).contains("Affected task: task-42")
            .doesNotContain("Affected resource:");
    }

    private AlertRecord alertRecord(String clusterName, String subject) {
        return AlertRecord.builder()
            .id(new ObjectId())
            .policyName("critical-task-alerts")
            .eventType("cluster.task.failed")
            .severity(EventSeverity.CRITICAL)
            .subject(subject)
            .actor(null)
            .details(Map.of(
                "clusterName", clusterName,
                "taskId", "task-42",
                "reason", "source is unreachable"))
            .createdAt(Instant.parse("2026-01-02T03:04:05Z"))
            .build();
    }
}
