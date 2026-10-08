package io.tapstate.cloud.progress;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the read-time aggregation in {@link DeploymentProgressService}:
 * percentage weighting, overall status, failure freeze, skip handling and the
 * catalog-synthesized empty view.
 */
class DeploymentProgressAggregationTest {

    private DeploymentStepRepository repository;
    private DeploymentProgressService service;

    private final ObjectId subscriptionId = new ObjectId();

    @BeforeEach
    void setUp() {
        repository = mock(DeploymentStepRepository.class);
        service = new DeploymentProgressService(repository);
        when(repository.findBySubscriptionIdAndOpTypeOrderBySequenceAsc(
            any(ObjectId.class), any(DeploymentOpType.class)))
            .thenAnswer(inv -> storedSteps(inv.getArgument(0), inv.getArgument(1)));
    }

    private List<DeploymentStep> steps = new ArrayList<>();

    private List<DeploymentStep> storedSteps(ObjectId sid, DeploymentOpType opType) {
        return steps.stream()
            .filter(s -> s.getSubscriptionId().equals(sid) && s.getOpType() == opType)
            .collect(Collectors.toList());
    }

    private void stubInstall(java.util.function.Consumer<Map<DeploymentOperation, StepStatus>> config) {
        Map<DeploymentOperation, StepStatus> statuses =
            DeploymentOperation.byOpType(DeploymentOpType.INSTALL).stream()
                .collect(Collectors.toMap(Function.identity(), op -> StepStatus.PENDING));
        config.accept(statuses);

        Instant now = Instant.now();
        for (DeploymentOperation operation : DeploymentOperation.byOpType(DeploymentOpType.INSTALL)) {
            StepStatus status = statuses.get(operation);
            DeploymentStep.DeploymentStepBuilder builder = DeploymentStep.builder()
                .orgId(new ObjectId())
                .subscriptionId(subscriptionId)
                .opType(DeploymentOpType.INSTALL)
                .operation(operation.code())
                .sequence(operation.sequence())
                .weight(operation.weight())
                .status(status)
                .attempts(new ArrayList<>());
            if (status != StepStatus.PENDING) {
                builder.startedAt(now.minusSeconds(20));
                if (status == StepStatus.SUCCEEDED || status == StepStatus.SKIPPED) {
                    builder.finishedAt(now.minusSeconds(10));
                }
            }
            steps.add(builder.build());
        }
    }

    @Test
    void noStepsAtAllSynthesizesTheCatalogAsPending() {
        DeploymentProgressResponse progress =
            service.getProgress(subscriptionId, DeploymentOpType.INSTALL);

        assertThat(progress.overallStatus()).isEqualTo("PENDING");
        assertThat(progress.percent()).isZero();
        assertThat(progress.operations()).hasSize(14);
        assertThat(progress.operations()).allSatisfy(op ->
            assertThat(op.status()).isEqualTo("PENDING"));
        assertThat(progress.finishedAt()).isNull();
    }

    @Test
    void uninstallCatalogHasFourOperations() {
        DeploymentProgressResponse progress =
            service.getProgress(subscriptionId, DeploymentOpType.UNINSTALL);

        assertThat(progress.operations()).hasSize(4);
        assertThat(progress.opType()).isEqualTo("UNINSTALL");
    }

    @Test
    void partialCompletionIsInProgressWithWeightedPercent() {
        stubInstall(statuses -> {
            statuses.put(DeploymentOperation.RESERVE_MDB_SLOT, StepStatus.SUCCEEDED); // 3
            statuses.put(DeploymentOperation.GENERATE_SSH_KEY, StepStatus.SUCCEEDED); // 2
            statuses.put(DeploymentOperation.PROVISION_EC2, StepStatus.RUNNING);
        });

        DeploymentProgressResponse progress =
            service.getProgress(subscriptionId, DeploymentOpType.INSTALL);

        assertThat(progress.overallStatus()).isEqualTo("IN_PROGRESS");
        assertThat(progress.percent()).isEqualTo(5);
        assertThat(progress.startedAt()).isNotNull();
        assertThat(progress.finishedAt()).isNull();
    }

    @Test
    void aFailureFreezesThePercentageAndAggregatesTheMessage() {
        stubInstall(statuses -> {
            statuses.put(DeploymentOperation.RESERVE_MDB_SLOT, StepStatus.SUCCEEDED); // 3
            statuses.put(DeploymentOperation.GENERATE_SSH_KEY, StepStatus.SUCCEEDED); // 2
            statuses.put(DeploymentOperation.PROVISION_EC2, StepStatus.FAILED);
        });
        // Give the failed step an explicit message.
        steps.stream()
            .filter(s -> DeploymentOperation.PROVISION_EC2.code().equals(s.getOperation()))
            .findFirst()
            .ifPresent(s -> s.setMessage("EC2 boom"));

        DeploymentProgressResponse progress =
            service.getProgress(subscriptionId, DeploymentOpType.INSTALL);

        assertThat(progress.overallStatus()).isEqualTo("FAILED");
        assertThat(progress.percent())
            .as("succeeded weights stay counted, failed adds 0").isEqualTo(5);
        assertThat(progress.message()).contains("provision_ec2").contains("EC2 boom");
        assertThat(progress.finishedAt()).isNull();
    }

    @Test
    void allSucceededIsOneHundredWithFinishedAt() {
        stubInstall(statuses -> DeploymentOperation.byOpType(DeploymentOpType.INSTALL)
            .forEach(op -> statuses.put(op, StepStatus.SUCCEEDED)));

        DeploymentProgressResponse progress =
            service.getProgress(subscriptionId, DeploymentOpType.INSTALL);

        assertThat(progress.overallStatus()).isEqualTo("SUCCEEDED");
        assertThat(progress.percent()).isEqualTo(100);
        assertThat(progress.finishedAt()).isNotNull();
        assertThat(progress.message()).contains("completed");
    }

    @Test
    void skippedOperationsContributeTheirWeight() {
        stubInstall(statuses -> {
            statuses.put(DeploymentOperation.RESERVE_MDB_SLOT, StepStatus.SUCCEEDED); // 3
            statuses.put(DeploymentOperation.GENERATE_SSH_KEY, StepStatus.SKIPPED);   // 2
        });

        DeploymentProgressResponse progress =
            service.getProgress(subscriptionId, DeploymentOpType.INSTALL);

        assertThat(progress.overallStatus()).isEqualTo("IN_PROGRESS");
        assertThat(progress.percent()).isEqualTo(5);
    }

    @Test
    void summaryCarriesOverallStatusAndPercent() {
        stubInstall(statuses -> {
            statuses.put(DeploymentOperation.RESERVE_MDB_SLOT, StepStatus.SUCCEEDED);
            statuses.put(DeploymentOperation.GENERATE_SSH_KEY, StepStatus.SUCCEEDED);
        });

        DeploymentProgressSummary summary =
            service.summary(subscriptionId, DeploymentOpType.INSTALL);

        assertThat(summary.overallStatus()).isEqualTo("IN_PROGRESS");
        assertThat(summary.percent()).isEqualTo(5);
    }
}
