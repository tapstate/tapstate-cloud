package io.tapstate.cloud.it;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.mongodb.spring.session.MongoIndexedSessionRepository;
import org.mongodb.spring.session.MongoSession;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared-session guarantees behind the multi-replica console:
 * <ul>
 *   <li>A login is persisted as a session document in the control-plane MongoDB,
 *       carries the user as principal index, keeps its 7-day idle expiry, and the
 *       same session cookie authenticates repeated /me calls.</li>
 *   <li>A session saved by one repository instance (one replica's JVM) is
 *       readable by a separate repository instance (another replica's JVM) with
 *       the Spring Security context intact. Tomcat's per-JVM sessions made this
 *       impossible and produced intermittent 401s in production.</li>
 * </ul>
 */
@DisplayName("SharedSessionIT — MongoDB-backed sessions shared across replicas")
class SharedSessionIT extends BaseIT {

    private static final String PASSWORD = "StrongPass123!";

    /** HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY. */
    private static final String SECURITY_CONTEXT_KEY = "SPRING_SECURITY_CONTEXT";
    /** Mirrors AuthController.SESSION_USER_EMAIL. */
    private static final String SESSION_EMAIL_KEY = "TAPSTATE_USER_EMAIL";

    private static MongoClient mongoClient;
    private static MongoTemplate mongoOps;

    @BeforeAll
    static void connectToControlPlaneStore() {
        ConnectionString cs = new ConnectionString(MONGO_URI);
        mongoClient = MongoClients.create(cs);
        mongoOps = new MongoTemplate(new SimpleMongoClientDatabaseFactory(mongoClient, MONGO_DB));
    }

    @AfterAll
    static void closeControlPlaneStore() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    @Test
    @DisplayName("S1 — Login persists a shared session document; same cookie authenticates repeated /me")
    void login_persistsSharedSession_andCookieAlwaysAuthenticates() throws Exception {
        String email = "it-shared-" + System.nanoTime() + "@tapstate.io";
        signupAndLogin(email, PASSWORD);
        assertNotNull(sessionCookieValue, "Session cookie should be captured after login");

        // The cookie value is the Base64-encoded session id, so the document is
        // looked up by principal — the email is unique per test run.
        Document stored = mongoOps.findOne(
            new Query(Criteria.where("principal").is(email)),
            Document.class, "sessions");
        assertNotNull(stored, "Logged-in session must be persisted in the shared sessions collection");
        assertNotNull(stored.getString("_id"), "Persisted session must carry its id");

        Date expireAt = stored.get("expireAt", Date.class);
        assertNotNull(expireAt, "Session document must carry an expireAt TTL field");
        Instant expiry = expireAt.toInstant();
        Instant now = Instant.now();
        assertTrue(expiry.isAfter(now.plus(Duration.ofDays(6))),
            "Idle expiry must follow the configured 7-day lifetime, was " + expiry);
        assertTrue(expiry.isBefore(now.plus(Duration.ofDays(8))),
            "Idle expiry must not exceed the configured 7-day lifetime, was " + expiry);

        // Single process locally, but every request resolves the session from
        // MongoDB through the same filter chain the replicas use.
        for (int i = 0; i < 20; i++) {
            HttpResponse<String> resp = get("/v1/api/auth/me",
                Map.of("Cookie", "TAPSTATE_CLOUD_SESSION=" + sessionCookieValue));
            assertEquals(200, resp.statusCode(),
                "Request " + i + " with the shared session cookie must authenticate");
            assertTrue(resp.body().contains(email),
                "Request " + i + " should return the logged-in user");
        }

        logout();
        assertNull(mongoOps.findOne(new Query(Criteria.where("principal").is(email)),
                Document.class, "sessions"),
            "Logout must delete the shared session document");
    }

    @Test
    @DisplayName("S2 — Session written by replica A is readable by independent replica B with security context")
    void sessionSavedByOneReplica_isReadableByAnotherReplica() {
        MongoIndexedSessionRepository replicaA = newReplicaRepository();
        MongoIndexedSessionRepository replicaB = newReplicaRepository();
        String email = "it-crossreplica-" + System.nanoTime() + "@tapstate.io";

        MongoSession session = replicaA.createSession();
        String sessionId = session.getId();
        try {
            Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            session.setAttribute(SECURITY_CONTEXT_KEY, context);
            session.setAttribute(SESSION_EMAIL_KEY, email);
            replicaA.save(session);

            // A brand-new repository instance simulates a second pod: it has no
            // in-memory knowledge of the session and can only resolve it from MongoDB.
            MongoSession loaded = replicaB.findById(sessionId);
            assertNotNull(loaded, "Replica B must resolve replica A's session from MongoDB");
            assertEquals(Duration.ofDays(7), loaded.getMaxInactiveInterval(),
                "Configured idle expiry must travel with the persisted session");

            SecurityContext restored = (SecurityContext) loaded.getAttribute(SECURITY_CONTEXT_KEY);
            assertNotNull(restored, "Spring Security context must survive cross-replica deserialization");
            assertNotNull(restored.getAuthentication(), "Restored context must carry an authentication");
            assertEquals(email, restored.getAuthentication().getName(),
                "Restored authentication must name the logged-in user");
            assertEquals(email, loaded.getAttribute(SESSION_EMAIL_KEY),
                "Session email attribute must survive cross-replica deserialization");
        } finally {
            replicaA.deleteById(sessionId);
        }
    }

    /**
     * Builds an isolated repository over the shared store, as each replica does
     * in its own JVM. afterPropertiesSet creates the TTL/indexes idempotently.
     */
    private static MongoIndexedSessionRepository newReplicaRepository() {
        MongoIndexedSessionRepository repository = new MongoIndexedSessionRepository(mongoOps);
        repository.setDefaultMaxInactiveInterval(Duration.ofDays(7));
        repository.afterPropertiesSet();
        return repository;
    }
}
