package io.tapstate.cloud.progress;

import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Test-only service: lets the mock Cluster worker ask whether the current
 * attempt must fail, and consume that one-shot instruction. Active in dev/it
 * profiles only — the production application context has no failure-injection
 * capability.
 */
@Service
@Profile("dev | it")
@RequiredArgsConstructor
public class MockProvisionFailureService {

    private final MockProvisionFailureRepository failureRepository;

    /**
     * Insert a one-shot failure fixture for one Organization.
     *
     * @param orgId         organization whose next mock provisioning must fail
     * @param failOperation stable {@link DeploymentOperation} code to fail at
     */
    public void inject(Object orgId, String failOperation) {
        MockProvisionFailure fixture = MockProvisionFailure.builder()
            .orgId(toObjectId(orgId))
            .failOperation(failOperation)
            .consumed(false)
            .createdAt(Instant.now())
            .build();
        failureRepository.save(fixture);
    }

    /**
     * Consume the newest unconsumed fixture for the cluster's organization.
     *
     * @return the operation code the worker must fail at, or {@code null} when
     *         the current attempt should succeed
     */
    public String consumeFailure(Object orgId) {
        List<MockProvisionFailure> fixtures =
            failureRepository.findByOrgIdAndConsumedFalseOrderByCreatedAtDesc(toObjectId(orgId));
        if (fixtures.isEmpty()) {
            return null;
        }
        MockProvisionFailure fixture = fixtures.get(0);
        fixture.setConsumed(true);
        failureRepository.save(fixture);
        return fixture.getFailOperation();
    }

    private static ObjectId toObjectId(Object orgId) {
        if (orgId instanceof ObjectId objectId) {
            return objectId;
        }
        return new ObjectId(String.valueOf(orgId));
    }
}
