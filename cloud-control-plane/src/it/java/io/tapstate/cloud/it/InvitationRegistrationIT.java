package io.tapstate.cloud.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Sorts;
import io.tapstate.cloud.identity.InvitationService;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.Map;
import org.bson.types.ObjectId;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("InvitationRegistrationIT — invited account creation and organization admission")
class InvitationRegistrationIT extends BaseIT {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Email registration consumes one invitation and creates membership only in its organization")
    void emailRegistrationConsumesInvitationOnce() throws Exception {
        String orgId = signupWithOrg("Invited Workspace");
        String email = "guest-" + System.nanoTime() + "@tapstate.io";
        InvitationGrant grant = createInvitation(orgId, email);

        HttpResponse<String> mismatch = requestCode(grant.token(), "other-" + email);
        assertEquals(403, mismatch.statusCode());
        assertInvitationStillUsable(orgId, grant.id());

        assertEquals(202, requestCode(grant.token(), email).statusCode());
        String code = latestTestCode(email);
        assertEquals(6, code.length());
        assertNotEquals(code, challengeHmac(email));

        HttpResponse<String> registered = completeRegistration(grant.token(), email, code);
        assertEquals(201, registered.statusCode(), registered.body());
        String newSession = cookieValue(registered, "TAPSTATE_CLOUD_SESSION");
        assertNotNull(newSession);
        assertNotEquals(sessionCookieValue, newSession, "Successful registration must rotate the session id");

        HttpResponse<String> me = get("/v1/api/auth/me", Map.of("Cookie", "TAPSTATE_CLOUD_SESSION=" + newSession));
        assertEquals(200, me.statusCode());
        assertEquals(email, mapper.readTree(me.body()).path("data").path("email").asText());
        JsonNode organizations = mapper.readTree(get("/v1/api/orgs",
            Map.of("Cookie", "TAPSTATE_CLOUD_SESSION=" + newSession)).body()).path("data");
        assertEquals(1, organizations.size());
        assertEquals(orgId, organizations.get(0).path("id").asText());
        assertEquals(1, countUsers(email));
        assertEquals(1, countMembers(email));
        assertInvitationStoredAsDigest(grant.token());

        HttpResponse<String> replay = completeRegistration(grant.token(), email, code);
        assertEquals(409, replay.statusCode());
        assertEquals(1, countUsers(email));
        assertEquals(1, countMembers(email));
    }

    @Test
    @DisplayName("Five incorrect codes exhaust attempts without creating an account or consuming the invitation")
    void incorrectCodesAreBoundedAndDoNotConsumeInvitation() throws Exception {
        String orgId = signupWithOrg("Attempt Workspace");
        String email = "attempt-" + System.nanoTime() + "@tapstate.io";
        InvitationGrant grant = createInvitation(orgId, email);
        assertEquals(202, requestCode(grant.token(), email).statusCode());
        String actual = latestTestCode(email);
        String incorrect = "000000".equals(actual) ? "000001" : "000000";

        for (int attempt = 0; attempt < 5; attempt++) {
            assertEquals(400, completeRegistration(grant.token(), email, incorrect).statusCode());
        }
        assertEquals(429, completeRegistration(grant.token(), email, incorrect).statusCode());
        assertEquals(0, countUsers(email));
        assertEquals(0, countMembers(email));
        assertInvitationStillUsable(orgId, grant.id());
    }

    @Test
    @DisplayName("Registration does not reveal an existing account or consume its invitation")
    void existingEmailRegistrationHasTheSameGenericCodeFailure() throws Exception {
        String existingEmail = "existing-" + System.nanoTime() + "@tapstate.io";
        register(existingEmail, "StrongPass123!");
        String orgId = signupWithOrg("Existing Account Workspace");
        InvitationGrant existingGrant = createInvitation(orgId, existingEmail);
        String newEmail = "new-" + System.nanoTime() + "@tapstate.io";
        InvitationGrant newGrant = createInvitation(orgId, newEmail);

        HttpResponse<String> existingCodeRequest = requestCode(existingGrant.token(), existingEmail);
        HttpResponse<String> newCodeRequest = requestCode(newGrant.token(), newEmail);
        assertEquals(202, existingCodeRequest.statusCode());
        assertEquals(202, newCodeRequest.statusCode());
        assertEquals(mapper.readTree(newCodeRequest.body()).path("code").asText(),
            mapper.readTree(existingCodeRequest.body()).path("code").asText());

        String code = latestTestCode(existingEmail);
        HttpResponse<String> registration = completeRegistration(existingGrant.token(), existingEmail, code);
        assertEquals(400, registration.statusCode());
        assertEquals("auth.email-code-invalid", mapper.readTree(registration.body()).path("code").asText());
        assertEquals(1, countUsers(existingEmail));
        assertEquals(0, countMembers(existingEmail));
        assertInvitationStillUsable(orgId, existingGrant.id());
    }

    @Test
    @DisplayName("Email verification enforces cooldown and a shared per-source hourly limit")
    void codeRequestsHonorCooldownAndHourlyLimit() throws Exception {
        String orgId = signupWithOrg("Rate Limited Workspace");
        String cooldownEmail = "cooldown-" + System.nanoTime() + "@tapstate.io";
        InvitationGrant cooldownGrant = createInvitation(orgId, cooldownEmail);
        assertEquals(202, requestCode(cooldownGrant.token(), cooldownEmail).statusCode());
        HttpResponse<String> cooldown = requestCode(cooldownGrant.token(), cooldownEmail);
        assertEquals(429, cooldown.statusCode());
        assertEquals("auth.email-code-cooldown", mapper.readTree(cooldown.body()).path("code").asText());

        for (int request = 0; request < 4; request++) {
            String email = "limited-" + request + "-" + System.nanoTime() + "@tapstate.io";
            InvitationGrant grant = createInvitation(orgId, email);
            HttpResponse<String> response = requestCode(grant.token(), email);
            if (request < 3) {
                assertEquals(202, response.statusCode());
            } else {
                assertEquals(429, response.statusCode());
                assertEquals("auth.email-code-rate-limited", mapper.readTree(response.body()).path("code").asText());
            }
        }
    }

    @Test
    @DisplayName("Expired and revoked invitations are rejected with distinct errors")
    void expiredAndRevokedInvitationsCannotRequestCodes() throws Exception {
        String orgId = signupWithOrg("Invalid Invitation Workspace");
        String expiredEmail = "expired-" + System.nanoTime() + "@tapstate.io";
        String revokedEmail = "revoked-" + System.nanoTime() + "@tapstate.io";
        InvitationGrant expired = createInvitation(orgId, expiredEmail);
        InvitationGrant revoked = createInvitation(orgId, revokedEmail);

        try (MongoClient mongo = MongoClients.create(MONGO_URI)) {
            mongo.getDatabase(MONGO_DB).getCollection("organization_invitations").updateOne(
                new Document("_id", new ObjectId(expired.id())),
                new Document("$set", new Document("expiresAt", Date.from(Instant.now().minusSeconds(10)))));
        }
        HttpResponse<String> revoke = delete("/v1/api/orgs/" + orgId + "/invitations/" + revoked.id(), ownerCookie());
        assertEquals(200, revoke.statusCode(), revoke.body());

        HttpResponse<String> expiredResponse = requestCode(expired.token(), expiredEmail);
        HttpResponse<String> revokedResponse = requestCode(revoked.token(), revokedEmail);
        assertEquals(410, expiredResponse.statusCode());
        assertEquals("auth.invitation-expired", mapper.readTree(expiredResponse.body()).path("code").asText());
        assertEquals(410, revokedResponse.statusCode());
        assertEquals("auth.invitation-revoked", mapper.readTree(revokedResponse.body()).path("code").asText());
    }

    @Test
    @DisplayName("OAuth start stores signup intent server-side and validates state on provider cancellation")
    void oauthStartKeepsInvitationOutOfTheBrowserAuthorizationUrl() throws Exception {
        String invitationToken = "invite-secret-" + System.nanoTime();
        HttpResponse<String> start = post("/v1/api/auth/oauth/google/start",
            mapper.writeValueAsString(Map.of("flow", "signup", "invitationToken", invitationToken)), Map.of());
        assertEquals(200, start.statusCode(), start.body());
        String authorizationPath = mapper.readTree(start.body()).path("data").path("authorizationUrl").asText();
        assertEquals("/oauth2/authorization/google", authorizationPath);
        assertFalse(authorizationPath.contains(invitationToken));
        String session = cookieValue(start, "TAPSTATE_CLOUD_SESSION");
        assertNotNull(session);

        HttpResponse<String> authorization = get(authorizationPath,
            Map.of("Cookie", "TAPSTATE_CLOUD_SESSION=" + session));
        assertEquals(302, authorization.statusCode());
        URI providerUri = URI.create(authorization.headers().firstValue("Location").orElseThrow());
        Map<String, String> parameters = queryParameters(providerUri.getRawQuery());
        assertEquals("it-google-client-id", parameters.get("client_id"));
        assertTrue(parameters.containsKey("state"));
        assertTrue(parameters.containsKey("nonce"));
        assertTrue(parameters.containsKey("code_challenge"));
        assertFalse(providerUri.toString().contains(invitationToken));

        HttpResponse<String> callback = get("/login/oauth2/code/google?error=access_denied&state="
                + parameters.get("state"), Map.of("Cookie", "TAPSTATE_CLOUD_SESSION=" + session));
        assertEquals(302, callback.statusCode());
        assertTrue(callback.headers().firstValue("Location").orElseThrow()
            .endsWith("/auth?oauth=cancelled"));
    }

    @Test
    @DisplayName("Concurrent registration attempts create at most one user and one organization membership")
    void concurrentRegistrationConsumesInvitationOnce() throws Exception {
        String orgId = signupWithOrg("Concurrent Workspace");
        String email = "race-" + System.nanoTime() + "@tapstate.io";
        InvitationGrant grant = createInvitation(orgId, email);
        assertEquals(202, requestCode(grant.token(), email).statusCode());
        String code = latestTestCode(email);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<HttpResponse<String>> first = CompletableFuture.supplyAsync(() -> {
                await(start);
                return completeRegistration(grant.token(), email, code);
            }, executor);
            CompletableFuture<HttpResponse<String>> second = CompletableFuture.supplyAsync(() -> {
                await(start);
                return completeRegistration(grant.token(), email, code);
            }, executor);
            start.countDown();
            int firstStatus = first.get().statusCode();
            int secondStatus = second.get().statusCode();
            assertEquals(1, (firstStatus == 201 ? 1 : 0) + (secondStatus == 201 ? 1 : 0));
            assertEquals(1, countUsers(email));
            assertEquals(1, countMembers(email));
        } finally {
            executor.shutdownNow();
        }
    }

    private InvitationGrant createInvitation(String orgId, String email) throws Exception {
        HttpResponse<String> response = post("/v1/api/orgs/" + orgId + "/invitations",
            mapper.writeValueAsString(Map.of("email", email)), ownerCookie());
        assertEquals(201, response.statusCode(), response.body());
        JsonNode data = mapper.readTree(response.body()).path("data");
        return new InvitationGrant(data.path("invitation").path("id").asText(), data.path("token").asText());
    }

    private HttpResponse<String> requestCode(String token, String email) {
        return post("/v1/api/auth/email/code", "{\"email\":\"" + email
            + "\",\"invitationToken\":\"" + token + "\"}", Map.of());
    }

    private HttpResponse<String> delete(String path, Map<String, String> headers) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create(BASE_URL + path)).DELETE();
            headers.forEach(request::header);
            return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private HttpResponse<String> completeRegistration(String token, String email, String code) {
        return post("/v1/api/auth/email/register", "{\"email\":\"" + email
            + "\",\"invitationToken\":\"" + token + "\",\"code\":\"" + code
            + "\",\"password\":\"StrongPass123!\",\"displayName\":\"Invited User\"}", Map.of());
    }

    private Map<String, String> ownerCookie() {
        return Map.of("Cookie", "TAPSTATE_CLOUD_SESSION=" + sessionCookieValue);
    }

    private void assertInvitationStillUsable(String orgId, String invitationId) throws Exception {
        JsonNode listed = mapper.readTree(get("/v1/api/orgs/" + orgId + "/invitations", ownerCookie()).body()).path("data");
        for (JsonNode invitation : listed) {
            if (invitationId.equals(invitation.path("id").asText())) {
                assertEquals("active", invitation.path("status").asText());
                return;
            }
        }
        fail("Invitation was not listed for its organization owner");
    }

    private String latestTestCode(String email) {
        try (MongoClient mongo = MongoClients.create(MONGO_URI)) {
            Document message = mongo.getDatabase(MONGO_DB).getCollection("test_email_outbox")
                .find(new Document("email", email)).sort(Sorts.descending("sentAt")).first();
            assertNotNull(message, "IT mailer should record an outgoing verification message");
            return message.getString("code");
        }
    }

    private String challengeHmac(String email) {
        try (MongoClient mongo = MongoClients.create(MONGO_URI)) {
            Document challenge = mongo.getDatabase(MONGO_DB).getCollection("email_verification_challenges")
                .find(new Document("email", email)).first();
            assertNotNull(challenge);
            return challenge.getString("codeHmac");
        }
    }

    private Map<String, String> queryParameters(String query) {
        return java.util.Arrays.stream(query.split("&"))
            .map(pair -> pair.split("=", 2))
            .collect(java.util.stream.Collectors.toMap(
                parts -> URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                parts -> URLDecoder.decode(parts.length > 1 ? parts[1] : "", StandardCharsets.UTF_8)));
    }

    private void assertInvitationStoredAsDigest(String token) throws Exception {
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(token.getBytes(StandardCharsets.UTF_8)));
        try (MongoClient mongo = MongoClients.create(MONGO_URI)) {
            var invitations = mongo.getDatabase(MONGO_DB).getCollection("organization_invitations");
            assertEquals(1, invitations.countDocuments(new Document("tokenHash", digest)));
            assertEquals(0, invitations.countDocuments(new Document("tokenHash", token)));
        }
    }

    private String cookieValue(HttpResponse<String> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
            .filter(cookie -> cookie.startsWith(name + "="))
            .map(cookie -> cookie.substring(name.length() + 1, cookie.indexOf(';') > 0
                ? cookie.indexOf(';') : cookie.length()))
            .findFirst().orElse(null);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private record InvitationGrant(String id, String token) {}
}
