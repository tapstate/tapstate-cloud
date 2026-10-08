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

import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
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
 *   <li>Create dbUser with dbAdmin + readWrite roles on the three allocation dbs</li>
 *   <li>Verify dbUser exists via queryDatabaseUser</li>
 *   <li>AfterEach cleanup: drop the test dbUser (idempotent 404-safe)</li>
 * </ol>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AtlasAllocationIT {

    // ========== Config — read from env vars (never hardcode real keys) ==========
    private static final String PUBLIC_KEY = System.getenv("TAPSTATE_ATLAS_PUBLIC_KEY");
    private static final String PRIVATE_KEY = System.getenv("TAPSTATE_ATLAS_PRIVATE_KEY");
    private static final String PROJECT_ID = System.getenv("TAPSTATE_ATLAS_PROJECT_ID");
    private static final String CLUSTER_NAME = System.getenv("TAPSTATE_ATLAS_CLUSTER_NAME");

    /**
     * Registered Project network access-list entry for this JVM's egress IP.
     * Atlas has two independent allowlists: the API-key edge list (which gates
     * the Admin REST API) and the Project network list (which gates driver
     * connections). The edge list must be opened by an org admin before this
     * test can run; the Project network entry is registered dynamically here —
     * wildcard CIDRs are rejected on some Atlas organizations, so the entry is
     * the machine's exact egress address. The entry is not removed per class
     * (other failsafe classes still need it); CI removes it after the suite and
     * the 3-hour deleteAfterDate covers local runs.
     */
    private static String registeredEgressIp;

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

    /**
     * Real Atlas credentials are required — without them this class skips
     * rather than fails, so an unconfigured environment stays green.
     */
    @BeforeAll
    static void requireCredentials() {
        boolean configured = notBlank(PUBLIC_KEY) && notBlank(PRIVATE_KEY)
            && notBlank(PROJECT_ID) && notBlank(CLUSTER_NAME);
        assumeTrue(configured,
            "Skipping real Atlas ITs: TAPSTATE_ATLAS_PUBLIC_KEY / PRIVATE_KEY / PROJECT_ID / CLUSTER_NAME not all set");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** Detect egress IPs so admin can see exactly what Atlas sees. */
    @BeforeAll
    static void detectEgressIps() {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            String ipv4 = client.send(java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("https://ipv4.icanhazip.com")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()).body().trim();
            registeredEgressIp = ipv4;
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
     * Bootstrap: register this machine's exact egress IP in the Project
     * network access list (which gates mongodb+srv driver connections).
     * <p>
     * The entry carries a 3-hour {@code deleteAfterDate} safety expiry; CI
     * deletes it after the whole suite. Wildcard CIDRs (0.0.0.0/0) are rejected
     * on some organizations, and would be a standing open rule anyway — a
     * per-run exact IP is both accepted and tighter.
     * <p>
     * Edge-gate caveat: the Admin REST API itself is gated by the API key's
     * own access list, which cannot be self-registered (the registering call
     * would be blocked too). The org admin must either allow this key from the
     * required network range or switch off "Require IP Access List for the
     * Atlas Administration API" (Organization Settings) and leave the key's
     * list empty. A 403 IP_ADDRESS_NOT_ON_ACCESS_LIST here means that gate is
     * still closed.
     */
    @Test
    @Order(0)
    @DisplayName("ATLAS-SETUP-000 — Register runner egress IP in Project network access list")
    void ensureIpAccessListOpen() {
        System.out.println("\n=== ATLAS-SETUP-000 ===");
        assertNotNull(registeredEgressIp, "Egress IPv4 detection failed; cannot register Project network access");

        try {
            // The CI workflow gates this suite behind an operator-managed
            // allowlist step: a human may already have added this exact IP as a
            // PERMANENT entry. Atlas rejects posting a temporary (expiring)
            // entry over a permanent one with
            // PERMANENT_ENTITY_CANNOT_BE_MADE_TEMPORARY, so check first and
            // accept an existing entry instead of upserting.
            if (atlas.accessListContains(PROJECT_ID, registeredEgressIp)) {
                System.out.println("Egress IP " + registeredEgressIp
                    + " already present in Project " + PROJECT_ID
                    + " network access list (operator-managed) — skipping registration");
                return;
            }

            atlas.addIpAccessListEntry(PROJECT_ID, registeredEgressIp,
                "tapstate-it " + Instant.now(), Instant.now().plus(Duration.ofHours(3)));
            System.out.println("Registered egress IP " + registeredEgressIp
                + " in Project " + PROJECT_ID + " network access list (auto-expires in 3h)");

            // Entry propagation takes a few seconds; poll before driver tests run.
            AtlasApiException lastErr = null;
            for (int i = 0; i < 12; i++) {
                try {
                    if (atlas.accessListContains(PROJECT_ID, registeredEgressIp)) {
                        System.out.println("Access-list entry is visible/active for " + registeredEgressIp);
                        return;
                    }
                } catch (AtlasApiException e) {
                    lastErr = e;
                }
                try {
                    Thread.sleep(Duration.ofSeconds(5).toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    fail("Interrupted while waiting for access-list propagation");
                }
            }
            if (lastErr != null) throw lastErr;
            fail("Access-list entry " + registeredEgressIp + " did not become visible within 60s");
        } catch (AtlasApiException e) {
            if (e.statusCode() == 403 && "IP_ADDRESS_NOT_ON_ACCESS_LIST".equals(e.errorCode())) {
                System.err.println();
                System.err.println("============================================================");
                System.err.println("ATLAS ADMIN API EDGE GATE BLOCKS THIS MACHINE");
                System.err.println("============================================================");
                System.err.println("Egress IP " + registeredEgressIp + " is not on API key " + PUBLIC_KEY
                    + "'s API access list.");
                System.err.println("The API-key edge list cannot be self-registered from CI.");
                System.err.println("Admin action (one of):");
                System.err.println("  A. Organization Settings -> turn OFF");
                System.err.println("     'Require IP Access List for the Atlas Administration API'");
                System.err.println("     AND empty this key's API Access List (any entry enforces it).");
                System.err.println("  B. Add a stable CI egress IP/CIDR to the key's API Access List.");
                System.err.println("Downstream tests all fail with the same 403 until this is fixed.");
                System.err.println("============================================================");
                fail("ATLAS ADMIN API EDGE GATE CLOSED — admin action required (see stderr)");
            }
            throw new AssertionError(e);
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
     * Verify PO naming convention + dbAdmin/readWrite roles + idempotent 409 handling.
     * This is the core allocation test — creates a dbUser carrying the production
     * role set: dbAdmin and readWrite on the three PO-specified databases, plus
     * clusterMonitor@admin and read on config/local. readWrite is mandatory: a
     * dbAdmin-only user cannot read or insert user data, so the runtime fails its
     * bootstrap migrations and the node never becomes healthy.
     */
    @Test
    @Order(2)
    @DisplayName("ATLAS-ALLOC-001 — Create dbUser with dbAdmin+readWrite on 3 databases, verify via queryDatabaseUser")
    void createAndVerifyDatabaseUser() {
        System.out.println("\n=== ATLAS-ALLOC-001 ===");
        System.out.println("Test cluster hex (simulated ObjectId): " + testClusterHex);
        System.out.println("Main db:      " + mainDbName);
        System.out.println("Operator db:  " + operatorDbName);
        System.out.println("Views db:     " + viewsDbName);
        System.out.println("dbUser:       " + dbUsername);

        // Role set identical to AtlasMdbService.buildDbUserRoles in production.
        List<Map<String, String>> roles = List.of(
            Map.of("roleName", "dbAdmin", "databaseName", mainDbName),
            Map.of("roleName", "readWrite", "databaseName", mainDbName),
            Map.of("roleName", "dbAdmin", "databaseName", operatorDbName),
            Map.of("roleName", "readWrite", "databaseName", operatorDbName),
            Map.of("roleName", "dbAdmin", "databaseName", viewsDbName),
            Map.of("roleName", "readWrite", "databaseName", viewsDbName),
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

        assertEquals(9, roleCount,
            "dbUser should have exactly 9 roles (dbAdmin + readWrite on 3 databases"
                + " + clusterMonitor + read config + read local)");

        // readWrite on every allocation database is the regression guard: without
        // it the runtime's data access and bootstrap migrations are unauthorized.
        for (String databaseName : List.of(mainDbName, operatorDbName, viewsDbName)) {
            boolean hasReadWrite = false;
            boolean hasDbAdmin = false;
            for (JsonNode role : queriedRoles) {
                if (databaseName.equals(role.path("databaseName").asText())) {
                    String roleName = role.path("roleName").asText();
                    hasReadWrite |= "readWrite".equals(roleName);
                    hasDbAdmin |= "dbAdmin".equals(roleName);
                }
            }
            assertTrue(hasReadWrite, databaseName + " must grant readWrite (runtime data access)");
            assertTrue(hasDbAdmin, databaseName + " must grant dbAdmin (dropDatabase on reclaim)");
        }
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
