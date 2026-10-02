package io.tapstate.cloud.iac.godaddy;

import io.tapstate.cloud.iac.DnsInfrastructure;
import io.tapstate.cloud.iac.IacException;
import io.tapstate.cloud.iac.IacProperties;
import io.tapstate.cloud.common.api.AppErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * GoDaddy implementation of {@link DnsInfrastructure}.
 * <p>
 * The root domain is managed through the GoDaddy production API; relative record
 * names are derived from the Cluster FQDN. Authentication uses the GoDaddy
 * {@code sso-key} header built from an API key and secret supplied through
 * configuration. Missing credentials fail the invocation rather than startup.
 */
@Component
@Slf4j
public class GodaddyDnsInfrastructure implements DnsInfrastructure {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final String PAT_PREFIX = "gd_pat_";

    private final IacProperties properties;
    private final HttpClient http;

    public GodaddyDnsInfrastructure(IacProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .build();
    }

    @Override
    public void upsertARecord(String fqdn, String ipv4) {
        IacProperties.Godaddy config = properties.getGodaddy();
        requireCredentials(config);

        String relativeName = relativeName(fqdn, config.getDomain());
        String path = recordsPath(config.getDomain(), relativeName);
        String body = "[{\"data\":\"" + ipv4 + "\",\"ttl\":" + config.getTtl() + "}]";

        HttpRequest request = baseRequest(config, path)
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
            .build();

        send(request, "PUT", fqdn, true);
        log.info("[GoDaddy] A record upserted {} -> {}", fqdn, ipv4);
    }

    @Override
    public void deleteRecord(String fqdn) {
        IacProperties.Godaddy config = properties.getGodaddy();
        requireCredentials(config);

        String relativeName = relativeName(fqdn, config.getDomain());
        String path = recordsPath(config.getDomain(), relativeName);

        HttpRequest request = baseRequest(config, path)
            .DELETE()
            .build();

        send(request, "DELETE", fqdn, false);
        log.info("[GoDaddy] A record deleted {}", fqdn);
    }

    // ========== HTTP ==========

    /** Execute a request and classify non-success responses. */
    private void send(HttpRequest request, String method, String fqdn, boolean failOnNotFound) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return;
            }
            // An absent record during deletion is the desired end state.
            if (!failOnNotFound && status == 404) {
                return;
            }
            if (status == 401 || status == 403) {
                throw new IacException(AppErrorCode.IAC_DNS_PROVIDER_UNAUTHORIZED,
                    method + " " + fqdn + " rejected with " + status);
            }
            throw new IacException(AppErrorCode.IAC_DNS_PROVIDER_FAILED,
                method + " " + fqdn + " failed with " + status + ": "
                    + abbreviate(response.body()));
        } catch (IacException e) {
            throw e;
        } catch (Exception e) {
            throw new IacException(AppErrorCode.IAC_DNS_PROVIDER_FAILED, e,
                method + " " + fqdn + " transport error: " + e.getMessage());
        }
    }

    private static HttpRequest.Builder baseRequest(IacProperties.Godaddy config, String path) {
        return HttpRequest.newBuilder()
            .uri(URI.create(config.getBaseUrl() + path))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", authorizationHeader(config))
            .header("Accept", "application/json");
    }

    /**
     * Build the Authorization header. Personal Access Tokens (prefix {@code gd_pat_})
     * are sent as Bearer tokens; classic credentials use the legacy
     * {@code sso-key key:secret} scheme.
     */
    private static String authorizationHeader(IacProperties.Godaddy config) {
        if (isPat(config.getApiKey())) {
            return "Bearer " + config.getApiKey();
        }
        return "sso-key " + config.getApiKey() + ":" + config.getApiSecret();
    }

    private static boolean isPat(String apiKey) {
        return apiKey != null && apiKey.startsWith(PAT_PREFIX);
    }

    /** Build the records endpoint path. */
    private static String recordsPath(String rootDomain, String relativeName) {
        return "/v1/domains/" + rootDomain + "/records/A/" + relativeName;
    }

    /** Strip the root domain suffix to obtain the GoDaddy-relative record name. */
    private static String relativeName(String fqdn, String rootDomain) {
        String suffix = "." + rootDomain;
        if (!fqdn.endsWith(suffix)) {
            throw new IacException(AppErrorCode.IAC_DNS_PROVIDER_FAILED,
                "FQDN " + fqdn + " is not under the configured root domain " + rootDomain);
        }
        return fqdn.substring(0, fqdn.length() - suffix.length());
    }

    private static void requireCredentials(IacProperties.Godaddy config) {
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw new IacException(AppErrorCode.IAC_DNS_PROVIDER_UNAUTHORIZED,
                "GoDaddy API credentials are not configured");
        }
        // A Personal Access Token carries the credential in a single value; a
        // classic key requires its paired secret.
        if (!isPat(config.getApiKey())
            && (config.getApiSecret() == null || config.getApiSecret().isBlank())) {
            throw new IacException(AppErrorCode.IAC_DNS_PROVIDER_UNAUTHORIZED,
                "GoDaddy API secret is not configured");
        }
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() > 300 ? trimmed.substring(0, 300) : trimmed;
    }
}
