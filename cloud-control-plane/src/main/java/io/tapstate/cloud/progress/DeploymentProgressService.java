package io.tapstate.cloud.progress;

import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-side aggregation of deployment progress. The aggregate (overall status,
 * percentage, timestamps, message) is computed on every read and never stored
 * as a second ledger.
 * <p>
 * Missing steps are synthesized from the catalog as PENDING, so the API always
 * returns the full operation list even before seeding or for a category that
 * has not started.
 */
@Service
@RequiredArgsConstructor
public class DeploymentProgressService {

    private final DeploymentStepRepository stepRepository;

    /** Get the full aggregated progress of one subscription and category. */
    public DeploymentProgressResponse getProgress(ObjectId subscriptionId, DeploymentOpType opType) {
        List<DeploymentStep> steps = resolveSteps(subscriptionId, opType);
        return aggregate(subscriptionId, opType, steps);
    }

    /** Get the lightweight summary used in subscription responses. */
    public DeploymentProgressSummary summary(ObjectId subscriptionId, DeploymentOpType opType) {
        DeploymentProgressResponse progress = getProgress(subscriptionId, opType);
        return new DeploymentProgressSummary(progress.overallStatus(), progress.percent());
    }

    // ========== Aggregation ==========

    private DeploymentProgressResponse aggregate(ObjectId subscriptionId, DeploymentOpType opType,
                                                 List<DeploymentStep> steps) {
        int percent = 0;
        List<DeploymentStep> failures = new ArrayList<>();
        boolean allTerminal = true;
        boolean anyStarted = false;
        Instant earliestStart = null;
        Instant latestFinish = null;

        for (DeploymentStep step : steps) {
            StepStatus status = step.getStatus();
            if (status == StepStatus.SUCCEEDED || status == StepStatus.SKIPPED) {
                percent += step.getWeight();
            }
            if (status == StepStatus.FAILED) {
                failures.add(step);
            }
            if (status != StepStatus.SUCCEEDED && status != StepStatus.SKIPPED) {
                allTerminal = false;
            }
            if (status != StepStatus.PENDING) {
                anyStarted = true;
            }
            if (step.getStartedAt() != null
                && (earliestStart == null || step.getStartedAt().isBefore(earliestStart))) {
                earliestStart = step.getStartedAt();
            }
            if (step.getFinishedAt() != null
                && (latestFinish == null || step.getFinishedAt().isAfter(latestFinish))) {
                latestFinish = step.getFinishedAt();
            }
        }

        DeploymentOverallStatus overall;
        Instant finishedAt = null;
        String message = null;

        if (!failures.isEmpty()) {
            overall = DeploymentOverallStatus.FAILED;
            message = failureMessage(failures);
        } else if (anyStarted && allTerminal) {
            overall = DeploymentOverallStatus.SUCCEEDED;
            finishedAt = latestFinish;
            message = opType == DeploymentOpType.INSTALL
                ? "Deployment completed successfully"
                : "Reclamation completed successfully";
        } else if (anyStarted) {
            overall = DeploymentOverallStatus.IN_PROGRESS;
        } else {
            overall = DeploymentOverallStatus.PENDING;
        }

        List<DeploymentStepResponse> operations = steps.stream()
            .map(DeploymentStepResponse::from)
            .toList();

        return new DeploymentProgressResponse(
            subscriptionId != null ? subscriptionId.toHexString() : null,
            steps.stream().map(DeploymentStep::getOrgId).filter(java.util.Objects::nonNull)
                .findFirst().map(ObjectId::toHexString).orElse(null),
            opType.name(),
            overall.name(),
            percent,
            earliestStart,
            finishedAt,
            message,
            operations);
    }

    /** Build the full catalog-ordered step list, filling missing steps as PENDING. */
    private List<DeploymentStep> resolveSteps(ObjectId subscriptionId, DeploymentOpType opType) {
        List<DeploymentStep> stored =
            stepRepository.findBySubscriptionIdAndOpTypeOrderBySequenceAsc(subscriptionId, opType);
        Map<String, DeploymentStep> byCode = new LinkedHashMap<>();
        for (DeploymentStep step : stored) {
            byCode.put(step.getOperation(), step);
        }

        List<DeploymentStep> ordered = new ArrayList<>();
        for (DeploymentOperation operation : DeploymentOperation.byOpType(opType)) {
            DeploymentStep step = byCode.get(operation.code());
            ordered.add(step != null ? step : syntheticStep(subscriptionId, operation));
        }
        return ordered;
    }

    private static DeploymentStep syntheticStep(ObjectId subscriptionId, DeploymentOperation operation) {
        return DeploymentStep.builder()
            .subscriptionId(subscriptionId)
            .operation(operation.code())
            .sequence(operation.sequence())
            .weight(operation.weight())
            .status(StepStatus.PENDING)
            .attempts(new ArrayList<>())
            .build();
    }

    private static String failureMessage(List<DeploymentStep> failures) {
        return failures.stream()
            .map(step -> step.getOperation() + ": "
                + (step.getMessage() != null ? step.getMessage() : "failed"))
            .collect(java.util.stream.Collectors.joining("; "));
    }
}
