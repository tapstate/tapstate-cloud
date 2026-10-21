package io.tapstate.cloud.it;

import io.tapstate.cloud.sdk.model.CreateSubscriptionRequest;
import io.tapstate.cloud.sdk.model.SubscriptionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Cluster left behind by a provisioning run whose driver is gone must not stay
 * invisible in {@code PROVISIONING}.
 * <p>
 * Provisioning runs on an in-process thread of one control-plane replica, and only
 * that thread can move the Cluster onwards. When its process disappears mid-run the
 * Cluster keeps no failure reason, the deployment step it was executing stays
 * {@code RUNNING}, and retry is refused — the Cluster is stranded with no operator
 * recovery path. The fingerprint of that situation is exactly what {@link #strandRun}
 * writes: state {@code PROVISIONING} with an in-flight step and no step activity
 * inside the provisioning budget. It cannot be produced by a live run, which writes
 * at every step boundary, and the driver itself cannot be killed from here (it lives
 * in the forked application process) — so seeding the fingerprint is what
 * discriminates.
 * <p>
 * Each case below therefore drives the reconciler through the only two states that
 * matter: one stranded run has to be converged, and one run that is genuinely in
 * flight has to be left alone.
 */
@DisplayName("ProvisioningStallRecoveryIT — a stranded provisioning run is converged, a live one is not")
class ProvisioningStallRecoveryIT extends BaseIT {

    /** Age written onto the step records, well past the 30-minute stall budget. */
    private static final long STALLED_STEP_AGE_MS = 3_600_000L;

    /** The step a stranded run is left executing — the last one it recorded. */
    private static final String IN_FLIGHT_OPERATION = "start_runtime_stack";

    private SubscriptionResponse createReadyCluster(String clusterName) throws Exception {
        orgId = signupWithOrg(clusterName);
        SubscriptionResponse sub = seedProvisionedSubscription(orgId, "2c8g", "ap-southeast-1", clusterName);
        assertNotNull(sub.getClusterId(), "Subscription should carry its Cluster id");
        waitForReady(orgId, sub.getClusterId());
        return sub;
    }

    /** Organization this test's Cluster belongs to. */
    private String orgId;

    /**
     * Write the durable fingerprint of a provisioning run whose driver died
     * mid-step: the state of a run in progress, one step in flight, and no step
     * activity inside the budget.
     */
    private void strandRun(SubscriptionResponse sub, boolean withInFlightStep) throws Exception {
        String steps = "db.subscription_deployment_steps.updateMany("
            + "{subscriptionId: ObjectId('" + sub.getId() + "'), opType: 'INSTALL'},"
            + "{$set: {updatedAt: new Date(Date.now() - " + STALLED_STEP_AGE_MS + ")}});";
        String inFlight = withInFlightStep
            ? "db.subscription_deployment_steps.updateOne("
                + "{subscriptionId: ObjectId('" + sub.getId() + "'), opType: 'INSTALL',"
                + " operation: '" + IN_FLIGHT_OPERATION + "'}, {$set: {status: 'RUNNING'}});"
            : "";
        mongoshEval(
            "db.clusters.updateOne({_id: ObjectId('" + sub.getClusterId() + "')},"
                + "{$set: {state: 'PROVISIONING'}});"
                + steps + inFlight
                + "print('stranded');");
    }

    /** Status of one INSTALL step as stored, e.g. RUNNING / SUCCEEDED / FAILED. */
    private String stepStatus(SubscriptionResponse sub) throws Exception {
        return mongoshEval(
            "const s = db.subscription_deployment_steps.findOne({subscriptionId: ObjectId('"
                + sub.getId() + "'), opType: 'INSTALL', operation: '" + IN_FLIGHT_OPERATION + "'});"
                + "print(s ? s.status : 'NOT_FOUND');").trim();
    }

    /** Persisted provisioning failure detail of one Cluster. */
    private String provisionErrorDetail(String clusterId) throws Exception {
        return mongoshEval("const c = db.clusters.findOne({_id: ObjectId('" + clusterId + "')},"
            + " {provisionErrorDetail: 1});"
            + "print(c && c.provisionErrorDetail ? c.provisionErrorDetail : 'NONE');").trim();
    }

    @Test
    @DisplayName("T1 — A stranded run is failed with a reason, its in-flight step is failed, and retry recovers it")
    void strandedRunIsFailedAndRetryable() throws Exception {
        SubscriptionResponse sub = createReadyCluster("Stranded" + System.nanoTime());
        String clusterId = sub.getClusterId();
        assertEquals("SUCCEEDED", stepStatus(sub), "the mock run should have completed its steps first");

        strandRun(sub, true);

        // Without the convergence this Cluster never leaves PROVISIONING: nothing
        // else in the control plane moves it, and retry refuses from there.
        await().atMost(Duration.ofSeconds(60))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> assertEquals("PROVISIONING_FAILED", clustersApi
                .getCluster(orgId, clusterId).getData().getState().getValue()));

        assertEquals("PROVISIONING_TIMEOUT", clustersApi.getCluster(orgId, clusterId)
            .getData().getProvisionErrorCode().getValue(),
            "the stranded run must carry a coded reason");

        String detail = provisionErrorDetail(clusterId);
        assertNotNull(detail);
        assertTrue(detail.contains("no progress"),
            "the detail should name why the run was failed, was: " + detail);

        assertEquals("FAILED", stepStatus(sub),
            "the step the dead driver left RUNNING must stop claiming to be in flight");

        // The operator recovery path the stranded state used to deny.
        clustersApi.retryCluster(orgId, clusterId);
        await().atMost(Duration.ofMinutes(2))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> assertEquals("READY", clustersApi
                .getCluster(orgId, clusterId).getData().getState().getValue()));
    }

    @Test
    @DisplayName("T2 — A run that is genuinely in flight is left alone")
    void liveRunIsNotTouched() throws Exception {
        SubscriptionResponse sub = createReadyCluster("InFlight" + System.nanoTime());
        String clusterId = sub.getClusterId();

        // Same state, but with the step activity a live run writes: only the state
        // is moved, the step records keep their recent timestamps.
        mongoshEval("db.clusters.updateOne({_id: ObjectId('" + clusterId + "')},"
            + "{$set: {state: 'PROVISIONING'}}); print('in-flight');");

        // Several sweep intervals pass before anything may be concluded.
        Thread.sleep(10_000);

        assertEquals("PROVISIONING", clustersApi.getCluster(orgId, clusterId)
            .getData().getState().getValue(),
            "a provisioning run with recent step activity must not be failed");
    }
}
