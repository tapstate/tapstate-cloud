package io.tapstate.cloud.notification;

import io.tapstate.cloud.event.AlertRecord;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Renders every outgoing email from one shared layout: brand logo, consistent
 * typography, accent colour and a contact footer. Only the content block differs
 * by notification type.
 */
@Component
public class EmailTemplateRenderer {

    private static final String LOGO_CID = "tapstate-logo";
    private static final String SUPPORT_EMAIL = "support@tapstate.com";
    private static final String WEBSITE = "https://tapstate.com";
    private static final DateTimeFormatter TIME_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    public RenderedEmail renderVerificationCode(String code) {
        String title = "Verify your email";
        String text = "Your Tapstate verification code is " + code + ".\n\n"
            + "It expires in 10 minutes. If you did not request this code, you can ignore this email.\n\n"
            + "Need help? Contact " + SUPPORT_EMAIL + ".\n"
            + "Tapstate Cloud — " + WEBSITE;
        String content = """
            <h1 style="margin:0 0 8px;font-size:22px;line-height:1.35;font-weight:600;color:#101820;">%s</h1>
            <p style="margin:0;font-size:15px;line-height:1.6;color:#334155;">Enter this code to continue to Tapstate Cloud.</p>
            <div style="margin:24px 0;padding:16px 20px;border-radius:10px;background:#f1f5f9;text-align:center;font-family:Arial,Helvetica,sans-serif;font-size:32px;line-height:1.25;font-weight:700;letter-spacing:0.28em;color:#101820;">%s</div>
            <p style="margin:0;font-size:14px;line-height:1.6;color:#334155;">This code expires in 10 minutes. Do not share it with anyone.</p>
            <p style="margin:20px 0 0;font-size:13px;line-height:1.6;color:#64748b;">If you did not request this code, you can ignore this email.</p>
            """.formatted(title, escape(code));
        return new RenderedEmail("Your Tapstate verification code", text,
            layout(title, "Your verification code expires in 10 minutes.", "#2563eb", content), true);
    }

    public RenderedEmail renderAlert(AlertRecord alert) {
        String severity = alert.getSeverity() == null ? "INFO" : alert.getSeverity().name();
        String subject = alert.getSubject() == null ? alert.getEventType() : alert.getSubject();
        String task = detail(alert, "taskId", "taskName", "pipelineId", "jobId");
        String resource = detail(alert, "resourceId");
        String resourceKind = detail(alert, "resourceKind");
        String affectedLabel = task == null && resource != null ? "Affected resource" : "Affected task";
        String affected = task != null || resource == null ? task
            : resourceKind == null ? resource : resourceKind + " / " + resource;
        String accent = switch (severity) {
            case "CRITICAL" -> "#dc2626";
            case "WARNING" -> "#d97706";
            default -> "#2563eb";
        };
        String text = """
            %s

            Policy: %s
            Event type: %s
            Severity: %s
            %s: %s
            Cluster: %s
            Reason: %s
            Actor: %s
            Time: %s
            Alert ID: %s

            Need help? Contact %s.
            Tapstate Cloud — %s
            """.formatted(
            subject,
            value(alert.getPolicyName()),
            value(alert.getEventType()),
            severity,
            affectedLabel,
            value(affected),
            value(detail(alert, "clusterId", "clusterName")),
            value(detail(alert, "reason", "message", "error")),
            value(alert.getActor()),
            alert.getCreatedAt() == null ? "-" : TIME_FORMAT.format(alert.getCreatedAt()),
            alert.getId() == null ? "-" : alert.getId().toHexString(),
            SUPPORT_EMAIL, WEBSITE);

        String content = """
            <div style="display:inline-block;margin:0 0 12px;padding:4px 10px;border-radius:999px;background:%s;color:#ffffff;font-size:12px;font-weight:700;letter-spacing:0.08em;">%s</div>
            <h1 style="margin:0 0 8px;font-size:22px;line-height:1.35;font-weight:600;color:#101820;">%s</h1>
            <p style="margin:0 0 20px;font-size:15px;line-height:1.6;color:#334155;">A Tapstate Cloud alert matched your policy.</p>
            <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="font-size:14px;line-height:1.55;color:#334155;">
              %s
            </table>
            <p style="margin:22px 0 0;font-size:13px;line-height:1.6;color:#64748b;">Need help? Contact <a href="mailto:%s" style="color:#2563eb;text-decoration:none;">%s</a>.</p>
            """.formatted(
            accent, escape(severity), escape(subject),
            row("Policy", alert.getPolicyName())
                + row("Event type", alert.getEventType())
                + row("Severity", severity)
                + row(affectedLabel, affected)
                + row("Cluster", detail(alert, "clusterId", "clusterName"))
                + row("Reason", detail(alert, "reason", "message", "error"))
                + row("Actor", alert.getActor())
                + row("Time", alert.getCreatedAt() == null ? "-" : TIME_FORMAT.format(alert.getCreatedAt()))
                + row("Alert ID", alert.getId() == null ? "-" : alert.getId().toHexString()),
            SUPPORT_EMAIL, SUPPORT_EMAIL);

        return new RenderedEmail(
            "[" + severity + "] " + subject,
            text,
            layout("Tapstate Cloud alert", "Severity " + severity + ": " + subject, accent, content),
            true);
    }

    private String layout(String title, String preheader, String accent, String content) {
        return """
            <!doctype html>
            <html lang="en">
              <body style="margin:0;padding:32px 16px;background:#f4f7f8;font-family:Arial,Helvetica,sans-serif;color:#101820;">
                <div style="display:none;max-height:0;overflow:hidden;opacity:0;">%s</div>
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="max-width:600px;margin:0 auto;background:#ffffff;border-radius:14px;border-top:4px solid %s;">
                  <tr><td style="padding:28px 32px 8px;">
                    <img src="cid:%s" width="140" alt="Tapstate" style="display:block;width:140px;height:auto;border:0;" />
                    <div style="margin:12px 0 0;font-size:12px;font-weight:700;letter-spacing:0.14em;color:#64748b;">TAPSTATE CLOUD</div>
                  </td></tr>
                  <tr><td style="padding:8px 32px 24px;">%s</td></tr>
                  <tr><td style="padding:18px 32px 28px;border-top:1px solid #e2e8f0;">
                    <p style="margin:0;font-size:12px;line-height:1.7;color:#64748b;">Tapstate Cloud · <a href="%s" style="color:#2563eb;text-decoration:none;">%s</a></p>
                    <p style="margin:6px 0 0;font-size:12px;line-height:1.7;color:#64748b;">Contact: <a href="mailto:%s" style="color:#2563eb;text-decoration:none;">%s</a></p>
                  </td></tr>
                </table>
              </body>
            </html>
            """.formatted(escape(preheader), accent, LOGO_CID, content, WEBSITE, WEBSITE,
            SUPPORT_EMAIL, SUPPORT_EMAIL);
    }

    private static String row(String label, String value) {
        String rendered = value == null || value.isBlank() ? "-" : value;
        return "<tr><td style=\"padding:6px 12px 6px 0;color:#64748b;white-space:nowrap;vertical-align:top;\">"
            + escape(label) + "</td><td style=\"padding:6px 0;color:#101820;\">"
            + escape(rendered) + "</td></tr>";
    }

    private static String detail(AlertRecord alert, String... keys) {
        Map<String, Object> details = alert.getDetails();
        if (details == null) {
            return null;
        }
        for (String key : keys) {
            Object value = details.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }
}
