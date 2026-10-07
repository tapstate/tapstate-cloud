package io.tapstate.cloud.atlas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Thin wrapper over Atlas Admin API (v2) with HTTP Digest Auth — the only
 * credentials mechanism Atlas accepts for programmatic API keys.
 * <p>
 * Implementation: JDK 21 {@link java.net.http.HttpClient} (no external
 * HTTP library), with a hand-rolled Digest auth implementation (RFC 7616)
 * because Atlas's WWW-Authenticate challenge uses {@code qop=auth} and
 * JDK HttpClient doesn't ship Digest support out of the box.
 * <p>
 * The client is locked to DIRECT routing — it never reads {@code http.proxyHost}
 * JVM properties or macOS system proxy settings, because Atlas IP Access List
 * checks must see this machine's real egress IP (not some proxy pool that NATs
 * through a rotating set of blocked IPs).
 * <p>
 * This class is intentionally low-level — it returns {@link JsonNode} responses
 * so callers can extract fields without a rigid DTO layer.
 */
@Slf4j
public class AtlasApiClient {

    private static final String V2_BASE = "/api/atlas/v2";
    /**
     * Atlas Admin API v2 enforces a versioned Accept header for schema negotiation.
     * 2025-03-12 is the current stable version date — must match the versioned
     * API docs the client was written against.
     */
    private static final String ACCEPT_HEADER = "application/vnd.atlas.2025-03-12+json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Single shared HttpClient — immutable, thread-safe
    private final HttpClient httpClient;

    private final String baseUrl;      // e.g. "https://cloud.mongodb.com"
    private final String publicKey;    // Digest Auth username
    private final String privateKey;   // Digest Auth password

    /**
     * Constructs a client pointed at {@code https://cloud.mongodb.com} (default).
     */
    public AtlasApiClient(String publicKey, String privateKey) {
        this("https://cloud.mongodb.com", publicKey, privateKey);
    }

    public AtlasApiClient(String baseUrl, String publicKey, String privateKey) {
        this.baseUrl = baseUrl;
        this.publicKey = publicKey;
        this.privateKey = privateKey;
        this.httpClient = HttpClient.newBuilder()
            // LOCKED to DIRECT — never let macOS system proxy or JVM proxy properties
            // interfere with the egress IP Atlas sees.
            .proxy(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                @Override public void connectFailed(URI uri, SocketAddress sa, IOException ioe) { /* no-op */ }
            })
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    // ========== Public API surface (unchanged) ==========

    /**
     * Query one Atlas Cluster — Flex-aware. Tries the standard /clusters path
     * first; on CANNOT_USE_FLEX_CLUSTER_IN_CLUSTER_API (400), falls back to
     * the /flexClusters path and searches the list for the matching name.
     */
    public JsonNode queryCluster(String projectId, String clusterName) throws AtlasApiException {
        String standardPath = V2_BASE + "/groups/{projectId}/clusters/{clusterName}"
            .replace("{projectId}", projectId).replace("{clusterName}", clusterName);
        try {
            return executeJson("GET", standardPath, null);
        } catch (AtlasApiException e) {
            if (e.statusCode() == 400 && "CANNOT_USE_FLEX_CLUSTER_IN_CLUSTER_API".equals(e.errorCode())) {
                return queryFlexCluster(projectId, clusterName);
            }
            if (e.statusCode() == 404) {
                JsonNode flex = queryFlexCluster(projectId, clusterName);
                if (flex != null) return flex;
                throw e;
            }
            throw e;
        }
    }

    /** Flex cluster lookup — Atlas has a separate /flexClusters endpoint. */
    private JsonNode queryFlexCluster(String projectId, String clusterName) throws AtlasApiException {
        String flexPath = V2_BASE + "/groups/{projectId}/flexClusters"
            .replace("{projectId}", projectId);
        JsonNode list = executeJson("GET", flexPath, null);
        if (list == null || !list.has("results")) return null;
        for (JsonNode cluster : list.path("results")) {
            if (clusterName.equals(cluster.path("name").asText())) {
                return cluster;
            }
        }
        return null;
    }

    /**
     * Check if a db user exists.
     *
     * @return dbUser response, or null if 404 USERNAME_NOT_FOUND
     */
    public JsonNode queryDatabaseUser(String projectId, String username) throws AtlasApiException {
        String path = V2_BASE + "/groups/{projectId}/databaseUsers/admin/{username}"
            .replace("{projectId}", projectId).replace("{username}", username);
        try {
            return executeJson("GET", path, null);
        } catch (AtlasApiException e) {
            if (e.statusCode() == 404) return null;
            throw e;
        }
    }

    /**
     * Create a db user. If the user already exists (409 USER_ALREADY_EXISTS),
     * returns {@link CreateUserResult} with alreadyExists=true — idempotent.
     */
    public CreateUserResult createDatabaseUser(String projectId, String username, String password,
                                               List<Map<String, String>> roles) throws AtlasApiException {
        String path = V2_BASE + "/groups/{projectId}/databaseUsers"
            .replace("{projectId}", projectId);

        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("databaseName", "admin");
        body.put("username", username);
        body.put("password", password);
        body.put("roles", roles);

        try {
            JsonNode resp = executeJson("POST", path, body);
            return new CreateUserResult(true, false, resp);
        } catch (AtlasApiException e) {
            if (e.statusCode() == 409 && "USER_ALREADY_EXISTS".equalsIgnoreCase(e.errorCode())) {
                log.info("[AtlasApiClient] USER_ALREADY_EXISTS username={} — idempotent success", username);
                return new CreateUserResult(false, true, null);
            }
            throw e;
        }
    }

    /** Drop a db user — idempotent (404 treated as success). */
    public void dropDatabaseUser(String projectId, String username) throws AtlasApiException {
        String path = V2_BASE + "/groups/{projectId}/databaseUsers/admin/{username}"
            .replace("{projectId}", projectId).replace("{username}", username);
        try {
            executeVoid("DELETE", path, null);
        } catch (AtlasApiException e) {
            if (e.statusCode() == 404) {
                log.info("[AtlasApiClient] drop db user 404 username={} — idempotent", username);
                return;
            }
            throw e;
        }
    }

    /** Add entries to the Project network access list. */
    public void addIpAccessListEntries(String projectId, List<String> cidrBlocks, String comment) throws AtlasApiException {
        for (String cidr : cidrBlocks) {
            addIpAccessListEntry(projectId, cidr, comment, null);
        }
    }

    /**
     * Add (or refresh) one entry in the Project network access list.
     * Used by CI to dynamically register the ephemeral runner egress IP:
     * Atlas rejects wildcard CIDRs on some organizations, so the entry is the
     * runner's exact address, with {@code deleteAfterDate} as a safety net in
     * case the cleanup step never runs. POST is an upsert — refreshing an
     * existing entry is expected and not an error.
     *
     * @param deleteAfterUtc optional auto-deletion instant, null for no expiry
     */
    public void addIpAccessListEntry(String projectId, String ipAddressOrCidr,
                                     String comment, java.time.Instant deleteAfterUtc) throws AtlasApiException {
        String path = V2_BASE + "/groups/{projectId}/accessList"
            .replace("{projectId}", projectId);

        var entry = new java.util.LinkedHashMap<String, Object>();
        entry.put("ipAddress", ipAddressOrCidr);
        if (comment != null) entry.put("comment", comment);
        if (deleteAfterUtc != null) entry.put("deleteAfterDate", deleteAfterUtc.toString());

        executeVoid("POST", path, List.of(entry));
    }

    /**
     * Remove one entry from the Project network access list. Idempotent: a
     * missing entry (404) is treated as success so cleanup never fails a run.
     */
    public void removeIpAccessListEntry(String projectId, String ipAddressOrCidr) throws AtlasApiException {
        String path = V2_BASE + "/groups/{projectId}/accessList/{entry}"
            .replace("{projectId}", projectId)
            .replace("{entry}", URLEncoder.encode(ipAddressOrCidr, java.nio.charset.StandardCharsets.UTF_8));
        try {
            executeVoid("DELETE", path, null);
        } catch (AtlasApiException e) {
            if (e.statusCode() == 404) {
                log.info("[AtlasApiClient] remove access-list entry 404 entry={} — idempotent", ipAddressOrCidr);
                return;
            }
            throw e;
        }
    }

    /**
     * @return true when the Project network access list currently contains
     *         the given entry. Propagation of newly added entries takes a few
     *         seconds, so callers poll this before opening driver connections.
     */
    public boolean accessListContains(String projectId, String ipAddressOrCidr) throws AtlasApiException {
        String path = V2_BASE + "/groups/{projectId}/accessList"
            .replace("{projectId}", projectId);
        JsonNode body = executeJson("GET", path + "?itemsPerPage=500", null);
        if (body == null || !body.has("results")) return false;
        for (JsonNode entry : body.get("results")) {
            if (ipAddressOrCidr.equals(entry.path("ipAddress").asText(null))
                || ipAddressOrCidr.equals(entry.path("cidrBlock").asText(null))) {
                return true;
            }
        }
        return false;
    }

    // ========== Low-level HTTP execution (JDK HttpClient + hand-rolled Digest) ==========

    private JsonNode executeJson(String method, String path, Object jsonBody) throws AtlasApiException {
        HttpResult result = execute(method, path, jsonBody);
        if (result.status >= 200 && result.status < 300 && result.body != null && !result.body.isEmpty()) {
            try {
                return MAPPER.readTree(result.body);
            } catch (IOException e) {
                throw new AtlasApiException(result.status, "Failed to parse Atlas JSON: " + e.getMessage(), path);
            }
        }
        if (result.status >= 200 && result.status < 300) return null;
        throw new AtlasApiException(result.status, extractErrorCode(result.body), path);
    }

    private void executeVoid(String method, String path, Object jsonBody) throws AtlasApiException {
        HttpResult result = execute(method, path, jsonBody);
        if (result.status >= 200 && result.status < 300) return;
        throw new AtlasApiException(result.status, extractErrorCode(result.body), path);
    }

    /**
     * Single entry point for all HTTP calls — handles the Digest Auth 2-step
     * dance: anonymous request → 401 Digest challenge → signed retry.
     */
    private HttpResult execute(String method, String path, Object jsonBody) throws AtlasApiException {
        try {
            String fullUrl = baseUrl + path;
            String jsonStr = jsonBody != null ? MAPPER.writeValueAsString(jsonBody) : null;

            // Step 1: anonymous request to get WWW-Authenticate Digest challenge
            HttpRequest anonReq = buildRequest(method, fullUrl, jsonStr, null);
            HttpResponse<String> anonResp = httpClient.send(anonReq, BodyHandlers.ofString());
            String wwwAuth = anonResp.headers().firstValue("WWW-Authenticate").orElse(null);

            if (wwwAuth == null || !wwwAuth.startsWith("Digest")) {
                // Not a Digest-protected endpoint — return as-is
                return new HttpResult(anonResp.statusCode(), anonResp.body());
            }
            if (anonResp.statusCode() != 401) {
                // Unexpected — shouldn't happen but surface it
                return new HttpResult(anonResp.statusCode(), anonResp.body());
            }

            // Step 2: compute Digest response and retry
            String digestHeader = computeDigest(method, path, wwwAuth);
            HttpRequest authReq = buildRequest(method, fullUrl, jsonStr, digestHeader);
            HttpResponse<String> authResp = httpClient.send(authReq, BodyHandlers.ofString());

            log.debug("[AtlasApiClient] {} {} → {}", method, path, authResp.statusCode());
            return new HttpResult(authResp.statusCode(), authResp.body());

        } catch (AtlasApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AtlasApiException(0, "Network interrupted: " + e.getMessage(), path, e);
        } catch (Exception e) {
            throw new AtlasApiException(0, "Network error calling Atlas Admin API: " + e.getMessage(), path, e);
        }
    }

    private HttpRequest buildRequest(String method, String url, String jsonBody, String authHeader) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", ACCEPT_HEADER);

        if (jsonBody != null) {
            b.header("Content-Type", "application/json");
            b.method(method, BodyPublishers.ofString(jsonBody));
        } else {
            b.method(method, BodyPublishers.noBody());
        }
        if (authHeader != null) {
            b.header("Authorization", authHeader);
        }
        return b.build();
    }

    // ========== HTTP Digest Auth (RFC 7616) ==========

    /**
     * Build the Digest Authorization header from a 401 challenge.
     * Supports the common Atlas variant: {@code qop=auth} + MD5.
     */
    private String computeDigest(String method, String path, String wwwAuthenticate) throws AtlasApiException {
        Map<String, String> challenge = parseWwwAuthenticate(wwwAuthenticate);
        String realm = require(challenge, "realm", wwwAuthenticate);
        String nonce = require(challenge, "nonce", wwwAuthenticate);
        String qop = challenge.get("qop"); // may be null

        String cnonce = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String nc = "00000001";

        // HA1 = MD5(username:realm:password)
        String ha1 = md5Hex(publicKey + ":" + realm + ":" + privateKey);
        // HA2 = MD5(method:uri)
        String ha2 = md5Hex(method + ":" + path);

        String response;
        if (qop != null && qop.contains("auth")) {
            response = md5Hex(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2);
        } else {
            response = md5Hex(ha1 + ":" + nonce + ":" + ha2);
        }

        StringBuilder sb = new StringBuilder("Digest ");
        sb.append("username=\"").append(publicKey).append("\",");
        sb.append("realm=\"").append(realm).append("\",");
        sb.append("nonce=\"").append(nonce).append("\",");
        sb.append("uri=\"").append(path).append("\",");
        if (qop != null && qop.contains("auth")) {
            sb.append("qop=auth,");
            sb.append("nc=").append(nc).append(",");
            sb.append("cnonce=\"").append(cnonce).append("\",");
        }
        sb.append("response=\"").append(response).append("\"");
        return sb.toString();
    }

    /**
     * Parse WWW-Authenticate: Digest realm="X", nonce="Y", qop="auth" ...
     * into a map of key → value (quotes stripped).
     */
    private Map<String, String> parseWwwAuthenticate(String header) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        // Strip "Digest " prefix (with possible challenge type before it)
        String body;
        if (header.toLowerCase().startsWith("digest")) {
            body = header.substring(header.indexOf(' ') + 1);
        } else {
            body = header;
        }
        // Split on commas, respecting quoted values
        Iterator<String> parts = splitByTopLevelComma(body).iterator();
        while (parts.hasNext()) {
            String pair = parts.next().trim();
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            result.put(key, value);
        }
        return result;
    }

    private List<String> splitByTopLevelComma(String input) {
        List<String> result = new java.util.ArrayList<>();
        boolean inQuote = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"') inQuote = !inQuote;
            if (c == ',' && !inQuote) {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) result.add(current.toString());
        return result;
    }

    private String require(Map<String, String> m, String key, String header) throws AtlasApiException {
        String v = m.get(key);
        if (v == null || v.isEmpty()) {
            throw new AtlasApiException(401, "Missing '" + key + "' in WWW-Authenticate: " + header, null);
        }
        return v;
    }

    private static String md5Hex(String input) throws AtlasApiException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AtlasApiException(0, "MD5 not available", null, e);
        }
    }

    // ========== Error code extraction ==========

    private String extractErrorCode(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            JsonNode node = MAPPER.readTree(body);
            JsonNode params = node.get("parameters");
            if (params != null && params.isArray()) {
                log.warn("[AtlasApiClient] error parameters={}", params);
            }
            JsonNode code = node.get("errorCode");
            if (code != null) return code.asText();
            JsonNode error = node.get("Error");
            if (error != null && error.has("errorCode")) {
                return error.get("errorCode").asText();
            }
        } catch (IOException ignored) { /* not JSON */ }
        return null;
    }

    // ========== Result wrappers ==========

    public record CreateUserResult(boolean createdNew, boolean alreadyExists, JsonNode response) {}
    private record HttpResult(int status, String body) {}
}
