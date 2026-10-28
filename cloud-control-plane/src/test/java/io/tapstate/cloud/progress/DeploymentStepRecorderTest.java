package io.tapstate.cloud.progress;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DeploymentStepRecorder}: unique-key upserts, retry
 * archiving and the guarantee that a recording failure never propagates to the
 * business flow.
 */
class DeploymentStepRecorderTest {

    private MongoTemplate mongoTemplate;
    private DeploymentStepRecorder recorder;

    private final ObjectId orgId = new ObjectId();
    private final ObjectId subscriptionId = new ObjectId();

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        recorder = new DeploymentStepRecorder(mongoTemplate);
    }

    @Test
    void seedUpsertsEveryCatalogStepOfTheOpType() {
        recorder.seed(orgId, subscriptionId, DeploymentOpType.INSTALL);

        // Each catalog operation is upserted individually — upsert with setOnInsert
        // makes the seed idempotent (already-existing steps are kept).
        verify(mongoTemplate, atLeastOnce()).upsert(any(Query.class), any(Update.class),
            eq(DeploymentStep.class));
    }

    @Test
    void markStartUpsertsTheUniqueKey() {
        recorder.markStart(orgId, subscriptionId, DeploymentOpType.INSTALL,
            DeploymentOperation.PROVISION_EC2);

        verify(mongoTemplate).upsert(any(Query.class), any(Update.class),
            eq(DeploymentStep.class));
    }

    @Test
    void markSucceedUpsertsWithFinishedStatus() {
        recorder.markSucceed(orgId, subscriptionId, DeploymentOpType.UNINSTALL,
            DeploymentOperation.DROP_DATABASE, "done");

        verify(mongoTemplate).upsert(any(Query.class), any(Update.class),
            eq(DeploymentStep.class));
    }

    @Test
    void aMongoFailureIsSwallowedSoProvisioningContinues() {
        when(mongoTemplate.upsert(any(Query.class), any(Update.class), eq(DeploymentStep.class)))
            .thenThrow(new RuntimeException("mongo down"));

        assertThatCode(() -> recorder.markStart(orgId, subscriptionId,
            DeploymentOpType.INSTALL, DeploymentOperation.HEALTH_CHECK))
            .doesNotThrowAnyException();
    }

    @Test
    void aSeedUpsertFailureIsSwallowed() {
        when(mongoTemplate.upsert(any(Query.class), any(Update.class), eq(DeploymentStep.class)))
            .thenThrow(new RuntimeException("mongo down"));

        assertThatCode(() -> recorder.seed(orgId, subscriptionId, DeploymentOpType.INSTALL))
            .doesNotThrowAnyException();
    }

    @Test
    void markRetryArchivesFailedStepsAndResetsThemToPending() {
        DeploymentStep failed = DeploymentStep.builder()
            .orgId(orgId)
            .subscriptionId(subscriptionId)
            .opType(DeploymentOpType.INSTALL)
            .operation(DeploymentOperation.HEALTH_CHECK.code())
            .sequence(DeploymentOperation.HEALTH_CHECK.sequence())
            .weight(DeploymentOperation.HEALTH_CHECK.weight())
            .status(StepStatus.FAILED)
            .message("boom")
            .startedAt(Instant.now().minusSeconds(5))
            .finishedAt(Instant.now())
            .attempts(new java.util.ArrayList<>())
            .build();
        when(mongoTemplate.find(any(Query.class), eq(DeploymentStep.class)))
            .thenReturn(List.of(failed));

        recorder.markRetry(subscriptionId, DeploymentOpType.INSTALL);

        // The failed step is archived and reset via a targeted updateFirst.
        verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class),
            eq(DeploymentStep.class));
    }

    @Test
    void markRetryDoesNothingWhenNoStepFailed() {
        when(mongoTemplate.find(any(Query.class), eq(DeploymentStep.class)))
            .thenReturn(List.of());

        recorder.markRetry(subscriptionId, DeploymentOpType.INSTALL);

        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class),
            eq(DeploymentStep.class));
    }

    @Test
    void markRunningFailedFailsEveryRunningStep() {
        DeploymentStep running = DeploymentStep.builder()
            .orgId(orgId)
            .subscriptionId(subscriptionId)
            .opType(DeploymentOpType.UNINSTALL)
            .operation(DeploymentOperation.DROP_DATABASE.code())
            .status(StepStatus.RUNNING)
            .attempts(new java.util.ArrayList<>())
            .build();
        when(mongoTemplate.find(any(Query.class), eq(DeploymentStep.class)))
            .thenReturn(List.of(running));

        recorder.markRunningFailed(subscriptionId, DeploymentOpType.UNINSTALL, "boom");

        verify(mongoTemplate).upsert(any(Query.class), any(Update.class),
            eq(DeploymentStep.class));
    }
}
