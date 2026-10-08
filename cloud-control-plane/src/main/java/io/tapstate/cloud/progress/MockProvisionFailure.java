package io.tapstate.cloud.progress;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Test-only fixture that makes the mock Cluster provisioning worker fail once
 * at the specified operation. One document per failure injection; it is
 * consumed (marked) by the first matching mock worker, so a subsequent retry
 * succeeds. Only used in dev/it profiles — production never seeds this
 * collection.
 */
@Document(collection = "mock_provision_failures")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MockProvisionFailure {

    @Id
    private ObjectId id;

    @Indexed
    private ObjectId orgId;

    /** Stable {@link DeploymentOperation} code the worker must fail at. */
    private String failOperation;

    /** True once a worker has consumed this fixture. */
    private boolean consumed;

    private Instant createdAt;
}
