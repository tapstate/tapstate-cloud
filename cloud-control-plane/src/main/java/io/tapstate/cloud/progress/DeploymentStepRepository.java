package io.tapstate.cloud.progress;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * Repository for {@link DeploymentStep} — declarative queries only; all
 * concurrent state updates go through {@link DeploymentStepRecorder}.
 */
public interface DeploymentStepRepository extends MongoRepository<DeploymentStep, ObjectId> {

    /** Load every step of one operation category, ordered by sequence. */
    List<DeploymentStep> findBySubscriptionIdAndOpTypeOrderBySequenceAsc(
        ObjectId subscriptionId, DeploymentOpType opType);
}
