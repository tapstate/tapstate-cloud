package io.tapstate.cloud.mdb.fsm;

import io.tapstate.cloud.mdb.AtlasMdbService;
import io.tapstate.cloud.mdb.MdbAllocation;
import io.tapstate.cloud.mdb.MdbAllocationRepository;
import io.tapstate.cloud.mdb.MdbAllocationStatus;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.statemachine.config.StateMachineBuilder;
import org.springframework.statemachine.config.StateMachineBuilder.Builder;
import org.springframework.statemachine.config.StateMachineFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Regression for the lost-update race between the transition writer and the
 * asynchronous Atlas provisioning worker: the worker marks READY before the
 * request thread's post-effect save lands, and that stale full-document save
 * overwrites READY back to PROVISIONING, leaving the allocation stuck forever.
 */
class MdbAllocationStateMachineServiceTest {

    private final ObjectId allocationId = new ObjectId();
    private final Thread testThread = Thread.currentThread();
    private final Map<ObjectId, MdbAllocation> store = new HashMap<>();
    private final CountDownLatch workerObservedReady = new CountDownLatch(1);
    private int savesOnTestThread;

    @Test
    void readyWrittenByProvisioningWorkerIsNeverOverwrittenByTransitionSave() throws Exception {
        MdbAllocation pending = MdbAllocation.builder()
            .id(allocationId)
            .status(MdbAllocationStatus.PROVISIONING_PENDING)
            .build();
        synchronized (store) {
            store.put(allocationId, detachedCopy(pending));
        }

        MdbAllocationRepository repo = mock(MdbAllocationRepository.class);
        doAnswer(invocation -> save(invocation.getArgument(0))).when(repo).save(any());
        doAnswer(invocation -> {
            MdbAllocation stored;
            synchronized (store) {
                stored = store.get(invocation.getArgument(0));
                stored = stored == null ? null : detachedCopy(stored);
                // Mirror the production sequence: the worker's post-provisioning
                // status check observed READY and skipped PROVISION_SUCCESS.
                if (Thread.currentThread() != testThread
                    && stored != null && stored.getStatus() == MdbAllocationStatus.READY) {
                    workerObservedReady.countDown();
                }
            }
            return Optional.ofNullable(stored);
        }).when(repo).findById(any());

        AtlasMdbService atlas = mock(AtlasMdbService.class);
        // Emulate an asynchronous worker that persists status on its own
        // document (the historic interleaving that exposed the lost update):
        // createDatabase touches PROVISIONING first, createDatabaseUser then
        // writes READY. The startup delay guarantees the request thread's
        // post-effect save converts its document before the worker mutates the
        // shared entity (the exact production window).
        doAnswer(invocation -> {
            Thread.sleep(60);
            MdbAllocation allocation = invocation.getArgument(0);
            allocation.setStatus(MdbAllocationStatus.PROVISIONING);
            allocation.setUpdatedAt(Instant.now());
            repo.save(allocation);
            return null;
        }).when(atlas).createDatabase(any());
        doAnswer(invocation -> {
            MdbAllocation allocation = invocation.getArgument(0);
            allocation.setStatus(MdbAllocationStatus.READY);
            allocation.setReadyAt(Instant.now());
            allocation.setUpdatedAt(Instant.now());
            repo.save(allocation);
            return null;
        }).when(atlas).createDatabaseUser(any());

        MdbAllocationStateMachineService service = new MdbAllocationStateMachineService(
            factory(), repo, atlas, mock(ApplicationEventPublisher.class));

        MdbAllocationStateMachineService.TransitionResult result =
            service.transition(allocationId, MdbAllocationEventType.START_PROVISION, "test");
        assertTrue(result.accepted());

        MdbAllocation persisted = awaitStatus(MdbAllocationStatus.READY, Duration.ofSeconds(5));
        assertNotNull(persisted, "allocation got stuck in PROVISIONING — stale transition save "
            + "overwrote the worker's READY status");
        assertEquals(MdbAllocationStatus.READY, persisted.getStatus());
        assertNotNull(persisted.getReadyAt());
    }

    /**
     * Emulate MongoRepository full-document mapping: each save serializes the
     * entity as it is seen at save time. The request thread's second save
     * converts its stale document before the worker reaches READY, then writes
     * it only after the worker has persisted and observed READY — the exact
     * interleaving observed in production. When the worker is scheduled only
     * after this save returns (the fixed ordering), the latch expires and the
     * stale write lands first as a harmless no-op.
     */
    private MdbAllocation save(MdbAllocation allocation) {
        boolean secondSaveOnTestThread = Thread.currentThread() == testThread
            && ++savesOnTestThread == 2;
        if (secondSaveOnTestThread) {
            MdbAllocation staleDocument = detachedCopy(allocation);
            try {
                workerObservedReady.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            synchronized (store) {
                store.put(allocation.getId(), staleDocument);
            }
        } else {
            synchronized (store) {
                store.put(allocation.getId(), detachedCopy(allocation));
            }
        }
        return allocation;
    }

    private MdbAllocation awaitStatus(MdbAllocationStatus expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            MdbAllocation allocation = repoFind(allocationId);
            if (allocation != null && allocation.getStatus() == expected) {
                return allocation;
            }
            Thread.sleep(20);
        }
        return repoFind(allocationId);
    }

    private MdbAllocation repoFind(ObjectId id) {
        synchronized (store) {
            MdbAllocation stored = store.get(id);
            return stored == null ? null : detachedCopy(stored);
        }
    }

    /** Shallow field-by-field copy — Mongo mapped documents are detached instances. */
    private static MdbAllocation detachedCopy(MdbAllocation source) {
        try {
            MdbAllocation target = new MdbAllocation();
            for (Field field : MdbAllocation.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                field.set(target, field.get(source));
            }
            return target;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static StateMachineFactory<MdbAllocationStatus, MdbAllocationEventType> factory() throws Exception {
        Builder<MdbAllocationStatus, MdbAllocationEventType> builder = StateMachineBuilder.builder();
        builder.configureStates().withStates()
            .initial(MdbAllocationStatus.PROVISIONING_PENDING)
            .states(EnumSet.allOf(MdbAllocationStatus.class))
            .end(MdbAllocationStatus.DELETED);
        builder.configureTransitions()
            .withExternal()
                .source(MdbAllocationStatus.PROVISIONING_PENDING)
                .event(MdbAllocationEventType.START_PROVISION)
                .target(MdbAllocationStatus.PROVISIONING)
            .and()
            .withExternal()
                .source(MdbAllocationStatus.PROVISIONING)
                .event(MdbAllocationEventType.PROVISION_SUCCESS)
                .target(MdbAllocationStatus.READY)
            .and()
            .withExternal()
                .source(MdbAllocationStatus.PROVISIONING)
                .event(MdbAllocationEventType.PROVISION_FAIL)
                .target(MdbAllocationStatus.PROVISIONING_FAILED)
            .and()
            .withExternal()
                .source(MdbAllocationStatus.READY)
                .event(MdbAllocationEventType.INITIATE_RECLAIM)
                .target(MdbAllocationStatus.DELETING)
            .and()
            .withExternal()
                .source(MdbAllocationStatus.DELETING)
                .event(MdbAllocationEventType.RECLAIM_COMPLETE)
                .target(MdbAllocationStatus.DELETED)
            .and()
            .withExternal()
                .source(MdbAllocationStatus.DELETING)
                .event(MdbAllocationEventType.RECLAIM_FAILED)
                .target(MdbAllocationStatus.DELETED);
        return builder.createFactory();
    }
}
