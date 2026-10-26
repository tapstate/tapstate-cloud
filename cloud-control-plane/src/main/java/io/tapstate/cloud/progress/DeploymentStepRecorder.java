package io.tapstate.cloud.progress;

import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.mdb.MdbAllocation;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Sole write point for deployment progress. Every mutation targets the unique
 * key (subscriptionId, opType, operation) with an upsert, so concurrent
 * workers can never create duplicate documents.
 * <p>
 * Progress recording is observational: a recording failure is logged and
 * swallowed — it must never break or delay the provisioning/reclamation
 * business flow.
 */
@Component
@Slf4j
public class DeploymentStepRecorder {

    /** Maximum persisted message length — error diagnostics can be very large. */
    private static final int MAX_MESSAGE = 4_000;

    private final MongoTemplate mongoTemplate;

    public DeploymentStepRecorder(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // ========== Seeding ==========

    /**
     * Insert every catalog operation of one category as PENDING. Already
     * existing steps are kept, so repeated seeding is safe — each step uses
     * an upsert on its unique (subscriptionId, opType, operation) key.
     */
    public void seed(ObjectId orgId, ObjectId subscriptionId, DeploymentOpType opType) {
        safe(() -> {
            Instant now = Instant.now();
            for (DeploymentOperation op : DeploymentOperation.byOpType(opType)) {
                Update update = new Update()
                    .setOnInsert("orgId", orgId)
                    .setOnInsert("subscriptionId", subscriptionId)
                    .setOnInsert("opType", opType)
                    .setOnInsert("operation", op.code())
                    .setOnInsert("sequence", op.sequence())
                    .setOnInsert("weight", op.weight())
                    .setOnInsert("status", StepStatus.PENDING)
                    .setOnInsert("attempts", new ArrayList<StepAttempt>())
                    .setOnInsert("createdAt", now)
                    .setOnInsert("updatedAt", now);
                mongoTemplate.upsert(uniqueQuery(subscriptionId, opType, op), update, DeploymentStep.class);
            }
        }, "seed", subscriptionId, opType);
    }

    // ========== Generic state mutations ==========

    public void markStart(ObjectId orgId, ObjectId subscriptionId,
                          DeploymentOpType opType, DeploymentOperation operation) {
        safe(() -> {
            Instant now = Instant.now();
            Update update = new Update()
                .set("status", StepStatus.RUNNING)
                .set("startedAt", now)
                .set("updatedAt", now)
                .setOnInsert("orgId", orgId)
                .setOnInsert("sequence", operation.sequence())
                .setOnInsert("weight", operation.weight())
                .setOnInsert("finishedAt", null)
                .setOnInsert("message", null)
                .setOnInsert("attempts", new ArrayList<StepAttempt>())
                .setOnInsert("createdAt", now);
            mongoTemplate.upsert(uniqueQuery(subscriptionId, opType, operation), update, DeploymentStep.class);
        }, "start", subscriptionId, opType, operation);
    }

    public void markSucceed(ObjectId orgId, ObjectId subscriptionId,
                            DeploymentOpType opType, DeploymentOperation operation, String message) {
        safe(() -> {
            Instant now = Instant.now();
            Update update = new Update()
                .set("status", StepStatus.SUCCEEDED)
                .set("message", clip(message))
                .set("finishedAt", now)
                .set("updatedAt", now)
                .setOnInsert("orgId", orgId)
                .setOnInsert("sequence", operation.sequence())
                .setOnInsert("weight", operation.weight())
                .setOnInsert("startedAt", now)
                .setOnInsert("attempts", new ArrayList<StepAttempt>())
                .setOnInsert("createdAt", now);
            mongoTemplate.upsert(uniqueQuery(subscriptionId, opType, operation), update, DeploymentStep.class);
        }, "succeed", subscriptionId, opType, operation);
    }

    public void markFail(ObjectId orgId, ObjectId subscriptionId,
                         DeploymentOpType opType, DeploymentOperation operation, String message) {
        safe(() -> {
            Instant now = Instant.now();
            Update update = new Update()
                .set("status", StepStatus.FAILED)
                .set("message", clip(message))
                .set("finishedAt", now)
                .set("updatedAt", now)
                .setOnInsert("orgId", orgId)
                .setOnInsert("sequence", operation.sequence())
                .setOnInsert("weight", operation.weight())
                .setOnInsert("startedAt", now)
                .setOnInsert("attempts", new ArrayList<StepAttempt>())
                .setOnInsert("createdAt", now);
            mongoTemplate.upsert(uniqueQuery(subscriptionId, opType, operation), update, DeploymentStep.class);
        }, "fail", subscriptionId, opType, operation);
    }

    /**
     * Archive the failed attempts of one category and reset them to PENDING so
     * the operation can run again. Called when a failed provisioning is retried.
     */
    public void markRetry(ObjectId subscriptionId, DeploymentOpType opType) {
        safe(() -> {
            Query query = Query.query(Criteria.where("subscriptionId").is(subscriptionId)
                .and("opType").is(opType)
                .and("status").is(StepStatus.FAILED));
            List<DeploymentStep> failed = mongoTemplate.find(query, DeploymentStep.class);
            Instant now = Instant.now();
            for (DeploymentStep step : failed) {
                StepAttempt attempt = new StepAttempt(
                    StepStatus.FAILED, step.getMessage(), step.getStartedAt(), step.getFinishedAt());
                Query stepQuery = uniqueQuery(subscriptionId, opType,
                    DeploymentOperation.fromCode(step.getOperation()));
                Update update = new Update()
                    .set("status", StepStatus.PENDING)
                    .set("message", null)
                    .set("startedAt", null)
                    .set("finishedAt", null)
                    .set("updatedAt", now)
                    .push("attempts", attempt);
                mongoTemplate.updateFirst(stepQuery, update, DeploymentStep.class);
            }
        }, "retry", subscriptionId, opType);
    }

    /**
     * Mark every RUNNING step of one category as FAILED — used when a worker
     * covering several operations fails and the exact in-flight operation is
     * not known at the catch site.
     */
    public void markRunningFailed(ObjectId subscriptionId, DeploymentOpType opType, String message) {
        safe(() -> {
            Query query = Query.query(Criteria.where("subscriptionId").is(subscriptionId)
                .and("opType").is(opType)
                .and("status").is(StepStatus.RUNNING));
            List<DeploymentStep> running = mongoTemplate.find(query, DeploymentStep.class);
            for (DeploymentStep step : running) {
                markFail(step.getOrgId(), subscriptionId, opType,
                    DeploymentOperation.fromCode(step.getOperation()), message);
            }
        }, "fail-running", subscriptionId, opType);
    }

    // ========== Convenience overloads ==========

    public void start(Cluster cluster, DeploymentOperation operation) {
        markStart(cluster.getOrgId(), cluster.getSubscriptionId(), operation.opType(), operation);
    }

    public void start(ObjectId subscriptionId, DeploymentOperation operation) {
        markStart(null, subscriptionId, operation.opType(), operation);
    }

    public void succeed(Cluster cluster, DeploymentOperation operation, String message) {
        markSucceed(cluster.getOrgId(), cluster.getSubscriptionId(), operation.opType(), operation, message);
    }

    public void succeed(ObjectId subscriptionId, DeploymentOperation operation, String message) {
        markSucceed(null, subscriptionId, operation.opType(), operation, message);
    }

    public void fail(Cluster cluster, DeploymentOperation operation, String message) {
        markFail(cluster.getOrgId(), cluster.getSubscriptionId(), operation.opType(), operation, message);
    }

    public void fail(ObjectId subscriptionId, DeploymentOperation operation, String message) {
        markFail(null, subscriptionId, operation.opType(), operation, message);
    }

    public void start(MdbAllocation allocation, DeploymentOperation operation) {
        markStart(allocation.getOrgId(), allocation.getSubscriptionId(), operation.opType(), operation);
    }

    public void succeed(MdbAllocation allocation, DeploymentOperation operation, String message) {
        markSucceed(allocation.getOrgId(), allocation.getSubscriptionId(), operation.opType(), operation, message);
    }

    public void fail(MdbAllocation allocation, DeploymentOperation operation, String message) {
        markFail(allocation.getOrgId(), allocation.getSubscriptionId(), operation.opType(), operation, message);
    }

    // ========== Internal ==========

    private static Query uniqueQuery(ObjectId subscriptionId, DeploymentOpType opType,
                                     DeploymentOperation operation) {
        return Query.query(Criteria.where("subscriptionId").is(subscriptionId)
            .and("opType").is(opType)
            .and("operation").is(operation.code()));
    }

    private static String clip(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > MAX_MESSAGE ? message.substring(0, MAX_MESSAGE) : message;
    }

    /** Run one mutation and swallow any failure so recording never breaks business flows. */
    private void safe(ThrowingRunnable runnable, String action, Object... context) {
        try {
            runnable.run();
        } catch (Exception e) {
            log.warn("[DeploymentProgress] recording {} failed context={}: {}",
                action, java.util.Arrays.toString(context), e.getMessage());
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
