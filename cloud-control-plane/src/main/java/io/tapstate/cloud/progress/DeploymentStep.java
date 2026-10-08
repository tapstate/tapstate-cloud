package io.tapstate.cloud.progress;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One deployment operation of one subscription — one document per
 * (subscriptionId, opType, operation). Steps are seeded up front as
 * {@link StepStatus#PENDING} and updated in place by the workers.
 */
@Document(collection = "subscription_deployment_steps")
@CompoundIndexes({
    @CompoundIndex(name = "sub_optype_op_unique",
        def = "{'subscriptionId': 1, 'opType': 1, 'operation': 1}", unique = true),
    @CompoundIndex(name = "org_sub_optype",
        def = "{'orgId': 1, 'subscriptionId': 1, 'opType': 1}")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One deployment operation of a subscription")
public class DeploymentStep {

    @Id
    @Schema(description = "Step ObjectId")
    private ObjectId id;

    @Schema(description = "Owning Organization")
    private ObjectId orgId;

    @Schema(description = "Subscription this operation belongs to")
    private ObjectId subscriptionId;

    @Schema(description = "Operation category: INSTALL / UNINSTALL")
    private DeploymentOpType opType;

    @Schema(description = "Stable operation code")
    private String operation;

    @Schema(description = "Presentation/dependency order")
    private int sequence;

    @Schema(description = "Weight of this operation in the aggregate percentage")
    private int weight;

    @Schema(description = "Current status")
    @Builder.Default
    private StepStatus status = StepStatus.PENDING;

    @Schema(description = "Success message or failure error detail")
    private String message;

    @Schema(description = "Most recent attempt start time")
    private Instant startedAt;

    @Schema(description = "Most recent attempt end time")
    private Instant finishedAt;

    @CreatedDate
    @Schema(description = "Creation time")
    private Instant createdAt;

    @LastModifiedDate
    @Schema(description = "Last update time")
    private Instant updatedAt;

    @Schema(description = "Historical attempts before the current one")
    @Builder.Default
    private List<StepAttempt> attempts = new ArrayList<>();
}
