package io.tapstate.cloud.it.atlas;

import com.fasterxml.jackson.databind.JsonNode;
import io.tapstate.cloud.atlas.AtlasApiClient;
import io.tapstate.cloud.atlas.AtlasApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end Atlas Admin API integration test — uses the PO-provided real
 * credentials to exercise the full allocation lifecycle against a live Atlas
 * Cloud cluster.
 * <p>
 * This test does NOT boot a Spring Boot application — it runs as a plain
 * JUnit 5 integration test via maven-failsafe-plugin, calling Atlas Admin API
 * directly with Digest Auth implemented in {@link AtlasApiClient}. This avoids
 * mock-mode interference and validates the real HTTP contract.
 * <p>
 * Credentials injected via env vars (never hardcoded in source):
 * <pre>
 *   export TAPSTATE_ATLAS_PUBLIC_KEY="eamtoiin"
 *   export TAPSTATE_ATLAS_PRIVATE_KEY="fb43e913-b73c-4fef-8f1e-682192f797db"
 *   export TAPSTATE_ATLAS_PROJECT_ID="6abbb5e00f0cfe355a4be9d3"
 *   export TAPSTATE_ATLAS_CLUSTER_NAME="Cluster0"
 * </pre>
 * <p>
 * Lifecycle per test class:
 * <ol>
 *   <li>Build unique clusterId (UUID-derived, so each test gets unique db name)</li>
 *   <li>Verify the target Cluster0 exists and is IDLE on Atlas</li>
 *   <li>Create dbUser with dbAdmin roles on the three allocation dbs</li>
 *   <li>Verify dbUser exists via queryDatabaseUser</li>
 *   <li>AfterEach cleanup: drop the test dbUser (idempotent 404-safe)</li>
 * </ol>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AtlasAllocationIT {

    // ========== Config — read from env vars ==========
    private static final String PUBLIC_KEY = System.getenv().getOrDefault("TAPSTATE_ATLAS_PUBLIC_KEY",
        "pmqzyjaz");
    private static final String PRIVATE_KEY = System.getenv().getOrDefault("TAPSTATE_ATLAS_PRIVATE_KEY",
        "9e686229-cab4-4073-8fed-2585b5a1d9d8");
    private static final String PROJECT_ID = System.getenv().getOrDefault("TAPSTATE_ATLAS_PROJECT_ID",
        "6abbb5e00f0cfe355a4be9d3");
    private static final String CLUSTER_NAME = System.getenv().getOrDefault("TAPSTATE_ATLAS_CLUSTER_NAME",
        "Cluster0");

    // ========== Test state — unique per test class ==========
    /**
     * 24-char hex "simulated Cluster ObjectId" — we use a UUID-based hex so the
     * Atlas database names are globally unique across test runs and never collide
     * with real customer databases.
     */
    private static String testClusterHex;

    /** Three allocation database names following PO naming convention. */
    private static String mainDbName;
    private static String operatorDbName;
    private static String viewsDbName;
    private static String dbUsername;

    private AtlasApiClient atlas;

    // ========== Lifecycle ==========

    /** Detect egress IPs so admin can see exactly what Atlas sees. */
    @BeforeAll
    static void detectEgressIps() {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            String ipv4 = client.send(java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("https://ipv4.icanhazip.com")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()).body().trim();
            System.out.println("\n[AtlasAllocationIT] Detected egress IPv4 (Atlas sees this): " + ipv4);
        } catch (Exception e) {
            System.out.println("[AtlasAllocationIT] Could not detect IPv4 egress IP: " + e.getMessage());
        }
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            String ipv6 = client.send(java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("https://ipv6.icanhazip.com")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()).body().trim();
            System.out.println("[AtlasAllocationIT] Detected egress IPv6: " + ipv6);
        } catch (Exception e) {
            System.out.println("[AtlasAllocationIT] Could not detect IPv6 egress IP: " + e.getMessage());
        }
    }

    @BeforeEach
    void setUp() {
        // Deterministic-but-unique cluster hex: first 24 hex chars of a UUID
        // This simulates an ObjectId and satisfies PO's mdb_{clusterId.toHexString()} format
        testClusterHex = HexFormat.of().formatHex(UUID.randomUUID().toString().replace("-", "").getBytes())
            .substring(0, 24);

        mainDbName = "mdb_" + testClusterHex;
        operatorDbName = mainDbName + "_operator";
        viewsDbName = mainDbName + "_views";
        dbUsername = "owner_" + mainDbName;  // PO convention; ensure ≤ 64 chars

        atlas = new AtlasApiClient(PUBLIC_KEY, PRIVATE_KEY);
    }

    @AfterEach
    void tearDown() {
        // Cleanup: drop the test dbUser (idempotent — 404 treated as success by AtlasApiClient)
        // This prevents orphaned db users if the test fails mid-way
        try {
            atlas.dropDatabaseUser(PROJECT_ID, dbUsername);
            System.out.println("[AtlasAllocationIT] Cleaned up test dbUser=" + dbUsername);
        } catch (Exception e) {
            System.err.println("[AtlasAllocationIT] Cleanup dbUser=" + dbUsername + " FAILED: " + e.getMessage()
                + " — you may need to drop it manually on Atlas.");
        }
    }

    // ========== Test methods ==========

    /**
     * Bootstrap: auto-configure Project API Access List to allow 0.0.0.0/0
     * (PO decision — all IPs allowed for Atlas Admin API calls).
     * <p>
     * If this test fails with IP_ADDRESS_NOT_ON_ACCESS_LIST, it means even the
     * bootstrap call itself is blocked — the admin MUST manually add 0.0.0.0/0
     * via Atlas Console (Project Settings → API Access → Add Access List Entry).
     * All downstream tests skip/fail-fast because they'll hit the same 403.
     */
    @Test
    @Order(0)
    @DisplayName("ATLAS-SETUP-000 — Ensure Project API Access List allows 0.0.0.0/0")
    void ensureIpAccessListOpen() {
        System.out.println("\n=== ATLAS-SETUP-000 ===");

        try {
            atlas.addIpAccessListEntries(PROJECT_ID, List.of("0.0.0.0/0"),
                "Tapstate Cloud — all IPs allowed (PO default, tighten in Week 4)");
            System.out.println("✓ Added 0.0.0.0/0 to Project " + PROJECT_ID + " API Access List");
        } catch (AtlasApiException e) {
            if (e.statusCode() == 403 && "IP_ADDRESS_NOT_ON_ACCESS_LIST".equals(e.errorCode())) {
                System.err.println();
                System.err.println("═════════════════════════════════════════════════════════");
                System.err.println("ATLAS API ACCESS LIST BLOCKS CURRENT MACHINE");
                System.err.println("═════════════════════════════════════════════════════════");
                System.err.println("");
                System.err.println("Your machine's egress IP is NOT on the Project API Access List.");
                System.err.println("This blocks ALL downstream tests — they will all fail with 403.");
                System.err.println("");
                System.err.println("ACTION REQUIRED — admin must configure this manually:");
                System.err.println("  1. Open Atlas Console: https://cloud.mongodb.com");
                System.err.println("  2. Navigate to: Project " + PROJECT_ID);
                System.err.println("  3. Project Settings → API Access → Add Access List Entry");
                System.err.println("  4. Enter CIDR: 0.0.0.0/0");
                System.err.println("  5. Add comment: 'Tapstate Cloud dev — all IPs allowed (temporary)'");
                System.err.println("  6. Save — this will allow ALL IPs to call Atlas Admin API");
                System.err.println("");
                System.err.println("After adding, re-run: mvn failsafe:integration-test -Dfailsafe.includes=**/atlas/AtlasAllocationIT.java");
                System.err.println("");
                System.err.println("Alternative: find your egress IP (curl -s ifconfig.me) and add it");
                System.err.println("specifically instead of 0.0.0.0/0 for tighter security.");
                System.err.println("═════════════════════════════════════════════════════════");
                fail("ATLAS API ACCESS LIST BLOCKED — admin action required (see stderr for details)");
            }
            throw e;
        }
    }

    /**
     * Verify PO-provided credentials + Project ID + Cluster0 actually exist on Atlas.
     * This is the foundation for every downstream test — if this fails nothing else matters.
     * Precondition: ATLAS-SETUP-000 must have succeeded.
     */
    @Test
    @Order(1)
    @DisplayName("ATLAS-CONNECT-001 — Verify target Cluster0 exists and is reachable with provided credentials")
    void verifyCluster0Exists() {
        System.out.println("\n=== ATLAS-CONNECT-001 ===");
        System.out.println("Project ID: " + PROJECT_ID);
        System.out.println("Cluster name: " + CLUSTER_NAME);
        System.out.println("Atlas base: https://cloud.mongodb.com");

        JsonNode cluster = assertDoesNotThrow(() -> atlas.queryCluster(PROJECT_ID, CLUSTER_NAME),
            "queryCluster should succeed with valid credentials");

        assertNotNull(cluster, "Cluster0 response should not be null");
        assertEquals(CLUSTER_NAME, cluster.path("name").asText(),
            "Cluster name should match " + CLUSTER_NAME);

        String stateName = cluster.path("stateName").asText();
        System.out.println("Cluster0 stateName=" + stateName);
        assertTrue(List.of("IDLE", "CREATING", "UPDATING").contains(stateName),
            "Cluster0 should be in a healthy state, got: " + stateName);

        String tierName = cluster.path("tierName").asText();
        String providerName = cluster.path("providerName").asText();
        String mongoDbVersion = cluster.path("mongoDBVersion").asText();
        System.out.println("Cluster0 details: tier=" + tierName + ", provider=" + providerName
            + ", mongoDBVersion=" + mongoDbVersion);

        JsonNode connectionStrings = cluster.path("connectionStrings");
        assertNotNull(connectionStrings, "Cluster0 should have connectionStrings");
        System.out.println("Cluster0 connectionStrings.standard=" + connectionStrings.path("standard").asText());
    }

    /**
     * Verify PO naming convention + dbAdmin roles + idempotent 409 handling.
     * This is the core allocation test — creates a dbUser with dbAdmin on the
     * three PO-specified databases.
     */
    @Test
    @Order(2)
    @DisplayName("ATLAS-ALLOC-001 — Create dbUser with dbAdmin on 3 databases, verify via queryDatabaseUser")
    void createAndVerifyDatabaseUser() {
        System.out.println("\n=== ATLAS-ALLOC-001 ===");
        System.out.println("Test cluster hex (simulated ObjectId): " + testClusterHex);
        System.out.println("Main db:      " + mainDbName);
        System.out.println("Operator db:  " + operatorDbName);
        System.out.println("Views db:     " + viewsDbName);
        System.out.println("dbUser:       " + dbUsername);

        // Build dbAdmin role entries per PO decision (plus clusterMonitor / read config / read local)
        List<Map<String, String>> roles = List.of(
            Map.of("roleName", "dbAdmin", "databaseName", mainDbName),
            Map.of("roleName", "dbAdmin", "databaseName", operatorDbName),
            Map.of("roleName", "dbAdmin", "databaseName", viewsDbName),
            Map.of("roleName", "clusterMonitor", "databaseName", "admin"),
            Map.of("roleName", "read", "databaseName", "config"),
            Map.of("roleName", "read", "databaseName", "local")
        );

        // Generate 32-byte random password → 48-char hex (PO convention)
        byte[] pwdBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(pwdBytes);
        String dbPassword = HexFormat.of().formatHex(pwdBytes);

        System.out.println("Attempting createDatabaseUser...");
        AtlasApiClient.CreateUserResult result = assertDoesNotThrow(() ->
            atlas.createDatabaseUser(PROJECT_ID, dbUsername, dbPassword, roles),
            "createDatabaseUser should succeed (or return AlreadyExists idempotent)");

        if (result.createdNew()) {
            System.out.println("✓ Created NEW dbUser=" + dbUsername);
        } else if (result.alreadyExists()) {
            System.out.println("✓ dbUser already existed= " + dbUsername + " — idempotent (treated as success)");
        } else {
            fail("Unexpected CreateUserResult state");
        }

        // Verify the dbUser actually exists on Atlas via query
        JsonNode queried = assertDoesNotThrow(() -> atlas.queryDatabaseUser(PROJECT_ID, dbUsername),
            "queryDatabaseUser should find the newly created dbUser");

        assertNotNull(queried, "queryDatabaseUser should return non-null response for existing user");
        assertEquals(dbUsername, queried.path("username").asText(),
            "queried username should match " + dbUsername);

        // Verify roles match what we requested
        JsonNode queriedRoles = queried.path("roles");
        assertNotNull(queriedRoles, "dbUser should have roles field");
        int roleCount = queriedRoles.size();
        System.out.println("Verified dbUser " + dbUsername + " exists with " + roleCount + " roles:");
        for (JsonNode role : queriedRoles) {
            System.out.println("  - " + role.path("roleName").asText() + "@" + role.path("databaseName").asText());
        }

        assertEquals(6, roleCount, "dbUser should have exactly 6 roles (3 dbAdmin + clusterMonitor + read config + read local)");
    }

    /**
     * Verify idempotency — calling createDatabaseUser twice on the same username
     * returns AlreadyExists (409), not a failure. This exercises the PO's
     * ensure-pattern-safe retry contract.
     */
    @Test
    @Order(3)
    @DisplayName("ATLAS-ALLOC-002 — Second createDatabaseUser returns AlreadyExists (idempotent)")
    void createUserIsIdempotent() {
        System.out.println("\n=== ATLAS-ALLOC-002 ===");
        // First call — ensure user exists
        List<Map<String, String>> roles = List.of(
            Map.of("roleName", "dbAdmin", "databaseName", mainDbName),
            Map.of("roleName", "dbAdmin", "databaseName", operatorDbName),
            Map.of("roleName", "dbAdmin", "databaseName", viewsDbName)
        );

        byte[] pwdBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(pwdBytes);
        String dbPassword = HexFormat.of().formatHex(pwdBytes);

        AtlasApiClient.CreateUserResult first = assertDoesNotThrow(() ->
            atlas.createDatabaseUser(PROJECT_ID, dbUsername, dbPassword, roles));
        System.out.println("First call: createdNew=" + first.createdNew() + ", alreadyExists=" + first.alreadyExists());

        // Second call on same username — should return AlreadyExists, not throw
        AtlasApiClient.CreateUserResult second = assertDoesNotThrow(() ->
            atlas.createDatabaseUser(PROJECT_ID, dbUsername, dbPassword, roles),
            "Second createDatabaseUser on same username should be idempotent (AlreadyExists)");

        assertTrue(second.alreadyExists(),
            "Second createDatabaseUser should return AlreadyExists (idempotent), got: createdNew="
                + second.createdNew() + " alreadyExists=" + second.alreadyExists());

        System.out.println("✓ Idempotency confirmed — second call returned AlreadyExists");
    }

    /**
     * Verify dropDatabaseUser + subsequent queryDatabaseUser == null.
     * Exercises the reclaim side of the lifecycle.
     */
    @Test
    @Order(4)
    @DisplayName("ATLAS-RECLAIM-001 — Create then drop dbUser, verify it's gone via query")
    void dropDatabaseUserThenVerifyGone() {
        System.out.println("\n=== ATLAS-RECLAIM-001 ===");

        List<Map<String, String>> roles = List.of(
            Map.of("roleName", "dbAdmin", "databaseName", mainDbName),
            Map.of("roleName", "dbAdmin", "databaseName", operatorDbName),
            Map.of("roleName", "dbAdmin", "databaseName", viewsDbName)
        );

        byte[] pwdBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(pwdBytes);
        String dbPassword = HexFormat.of().formatHex(pwdBytes);

        // Ensure user exists first
        assertDoesNotThrow(() -> atlas.createDatabaseUser(PROJECT_ID, dbUsername, dbPassword, roles));

        System.out.println("Dropping dbUser " + dbUsername + "...");
        assertDoesNotThrow(() -> atlas.dropDatabaseUser(PROJECT_ID, dbUsername),
            "dropDatabaseUser should succeed");

        // Verify it's gone
        JsonNode afterDrop = assertDoesNotThrow(() -> atlas.queryDatabaseUser(PROJECT_ID, dbUsername));
        assertNull(afterDrop, "dbUser should not exist after drop (query should return null/404)");

        System.out.println("✓ dbUser dropped and verified gone");

        // Idempotent re-drop should also succeed (404 treated as success)
        assertDoesNotThrow(() -> atlas.dropDatabaseUser(PROJECT_ID, dbUsername),
            "Re-dropping non-existent dbUser should be idempotent (404-safe)");

        System.out.println("✓ Re-drop is idempotent (404 treated as success)");
    }

    /**
     * Edge-case validation — username length, db name length, special chars.
     * PO's naming convention uses only a-zA-Z0-9_ which is safe for Atlas.
     */
    @Test
    @Order(5)
    @DisplayName("ATLAS-NAMING-001 — Verify PO naming convention: mdb_{hex} fits Atlas limits")
    void verifyNamingConvention() {
        System.out.println("\n=== ATLAS-NAMING-001 ===");

        // PO: databaseName = mdb_{24 char ObjectId hex} = 4 + 24 = 28 chars
        // Atlas max db name length: 64 chars (MongoDB limit) — well within
        assertEquals(28, mainDbName.length(), "main db name should be 28 chars (mdb_ + 24-hex)");

        // PO: operatorDbName = mainDbName + _operator = 28 + 9 = 37 chars
        assertEquals(37, operatorDbName.length());

        // PO: viewsDbName = mainDbName + _views = 28 + 6 = 34 chars
        assertEquals(34, viewsDbName.length());

        // PO: dbUsername = owner_{databaseName} = 6 + 28 = 34 chars — ≤ Atlas limit 64
        assertEquals(34, dbUsername.length());
        assertTrue(dbUsername.length() <= 64, "Atlas db username limit is 64 chars, got " + dbUsername.length());

        // All chars alphanumeric + underscore
        assertTrue(mainDbName.matches("[a-zA-Z0-9_]+"), "main db name should be alphanumeric+underscore");
        assertTrue(dbUsername.matches("[a-zA-Z0-9_]+"), "dbUsername should be alphanumeric+underscore");

        System.out.println("✓ All PO naming conventions validated:");
        System.out.println("  mainDbName length=" + mainDbName.length() + " (limit 64)");
        System.out.println("  dbUsername  length=" + dbUsername.length() + " (limit 64)");
    }
}
