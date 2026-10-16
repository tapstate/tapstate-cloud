package io.tapstate.cloud.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tapstate.cloud.sdk.model.CreateSubscriptionRequest;
import io.tapstate.cloud.sdk.model.SubscriptionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A partial mutation of a Cluster must not erase fields the running revision
 * does not map.
 * <p>
 * A repository {@code save} rewrites the whole document from the fields the
 * revision knows, so during a rolling update a replica still on the previous
 * revision drops any attribute added by the new one — including a value a
 * startup backfill has just written. Each case below seeds an attribute no
 * revision maps, drives a partial mutation through the public API, and then
 * requires that attribute to still be present: the mutation must touch only its
 * own fields.
 */
@DisplayName("ClusterFieldPreservationIT — a partial Cluster mutation keeps unmapped fields")
class ClusterFieldPreservationIT extends BaseIT {

    /**
     * Stands in for an attribute a newer revision adds and starts persisting:
     * this revision maps no such property, exactly as the previous revision
     * would not know a newly added one.
     */
    private static final String PROBE_FIELD = "probeUnmappedField";
    private static final String PROBE_VALUE = "written-before-the-mutation";

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpClient c2Client;
    private String orgId;

    @Override
    @BeforeEach
    void setUp() {
        super.setUp();
        c2Client = HttpClient.newBuilder().build();
    }

    /** Register a fresh user, create an Organization and return its id. */
    private String createOrg() throws Exception {
        return signupWithOrg("Preserve");
    }

    /** Create a subscription and wait until its Cluster reaches READY. */
    private SubscriptionResponse createReadyCluster(String clusterName) throws Exception {
        SubscriptionResponse sub = subscriptionApi.createSubscription(orgId,
            new CreateSubscriptionRequest().region("ap-southeast-1")
                .clusterName(clusterName).specKey("basic")).getData();
        assertNotNull(sub.getClusterId(), "Subscription should carry its Cluster id");
        waitForReady(orgId, sub.getClusterId());
        return sub;
    }

    /** Write the probe attribute straight onto the stored document. */
    private void seedProbeField(String clusterId) throws Exception {
        mongoshEval("db.clusters.updateOne({_id: ObjectId('" + clusterId + "')}, " +
            "{$set: {" + PROBE_FIELD + ": '" + PROBE_VALUE + "'}}); print('seeded');");
    }

    /** Read the probe attribute back from the stored document, or null when absent. */
    private String readProbeField(String clusterId) throws Exception {
        String output = mongoshEval(
            "const d = db.clusters.findOne({_id: ObjectId('" + clusterId + "')}, {" + PROBE_FIELD + ": 1});" +
            "print(d ? JSON.stringify(d) : 'NOT_FOUND');").trim();
        if ("NOT_FOUND".equals(output)) {
            return null;
        }
        return mapper.readTree(output).path(PROBE_FIELD).asText(null);
    }

    /** Latest status-report snapshot stored on the Cluster, or null when absent. */
    private JsonNode readLastStatusReport(String clusterId) throws Exception {
        String output = mongoshEval(
            "const d = db.clusters.findOne({_id: ObjectId('" + clusterId + "')}, {lastStatusReport: 1});" +
            "print(d && d.lastStatusReport ? JSON.stringify(d.lastStatusReport) : 'NOT_FOUND');").trim();
        if ("NOT_FOUND".equals(output)) {
            return null;
        }
        return mapper.readTree(output);
    }

    private HttpResponse<String> sendStatusReport(String clusterId, String bearerToken, String nonce)
        throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("nonce", nonce);
        body.put("runtimeVersion", "2.1.0");
        body.put("uptimeMs", 12345L);
        body.put("activePipelines", 1);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/v1/api/clusters/" + clusterId + "/status-report"))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + bearerToken)
            .build();
        return c2Client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("T1 — An unmapped field survives a C2 status-report write")
    void unmappedField_survivesStatusReport() throws Exception {
        orgId = createOrg();
        String clusterId = createReadyCluster("PreserveStatus").getClusterId();
        seedProbeField(clusterId);

        String token = mongoshEval("db.clusters.findOne({_id: ObjectId('" + clusterId + "')}, " +
            "{sdkStaticToken:1}).sdkStaticToken").trim();
        HttpResponse<String> response = sendStatusReport(clusterId, token, "preserve-nonce-1");
        assertEquals(200, response.statusCode(), "Body: " + response.body());

        // The mutation itself must land — otherwise the preservation check below
        // could pass because nothing was written at all.
        JsonNode snapshot = readLastStatusReport(clusterId);
        assertNotNull(snapshot, "status report should be persisted on the Cluster");
        assertEquals("preserve-nonce-1", snapshot.path("nonce").asText(),
            "the status-report write should persist the new nonce");

        assertEquals(PROBE_VALUE, readProbeField(clusterId),
            "the status-report write must not drop a field this revision does not map");
    }

    @Test
    @DisplayName("T2 — An unmapped field survives a lifecycle transition write")
    void unmappedField_survivesStateTransition() throws Exception {
        orgId = createOrg();
        SubscriptionResponse sub = createReadyCluster("PreserveTransition");
        String clusterId = sub.getClusterId();
        seedProbeField(clusterId);

        subscriptionApi.cancelSubscription(orgId, sub.getId());
        waitForClusterState(orgId, clusterId, "TERMINATED", 60, 1000);

        assertEquals(PROBE_VALUE, readProbeField(clusterId),
            "the termination transition must not drop a field this revision does not map");
    }
}
