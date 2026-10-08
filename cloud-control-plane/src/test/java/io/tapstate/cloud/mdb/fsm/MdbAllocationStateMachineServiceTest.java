package io.tapstate.cloud.mdb.fsm;

import io.tapstate.cloud.mdb.AtlasMdbService;
import io.tapstate.cloud.mdb.MdbAllocation;
import io.tapstate.cloud.mdb.MdbAllocationRepository;
import io.tapstate.cloud.mdb.MdbAllocationStatus;
import io.tapstate.cloud.progress.DeploymentStepRecorder;
import io.tapstate.cloud.subscription.SubscriptionRepository;
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
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Regression for the lost-update race between the transition writer and the
 * asynchronous Atlas provisioning worker: the worker marks READY before the
 * request thread's post-effect save lands, and that stale full-document save
 * overwrites READY back to PROVISIONING, leaving the allocation stuck forever.
 * The fixed ordering applies synchronous fields, persists exactly once, and
 * only then schedules the worker on its own reloaded entity.
 */
class MdbAllocationStateMachineServiceTest {

    @Test
    void readyWrittenByProvisioningWorkerIsNeverOverwrittenByTransitionSave() throws Exception {
        Harness h = new Harness();
        ObjectId allocationId = h.seed(MdbAllocationStatus.PROVISIONING_PENDING);

        // Emulate an asynchronous worker that persists status on its own
        // document (the historic interleaving that exposed the lost update):
        // createDatabase touches PROVISIONING first, createDatabaseUser then
        // writes READY. The startup delay lets the caller's post-scheduling
        // save capture a stale document before the worker mutates anything.
        doAnswer(invocation -> {
            Thread.sleep(60);
            MdbAllocation allocation = invocation.getArgument(0);
            allocation.setStatus(MdbAllocationStatus.PROVISIONING);
            allocation.setUpdatedAt(Instant.now());
            h.save(allocation);
            return null;
        }).when(h.atlas).createDatabase(any());
        doAnswer(invocation -> {
            MdbAllocation allocation = invocation.getArgument(0);
            allocation.setStatus(MdbAllocationStatus.READY);
            allocation.setReadyAt(Instant.now());
            allocation.setUpdatedAt(Instant.now());
            h.save(allocation);
            return null;
        }).when(h.atlas).createDatabaseUser(any());

        MdbAllocationStateMachineService.TransitionResult result =
            h.service.transition(allocationId, MdbAllocationEventType.START_PROVISION, "test");
        assertTrue(result.accepted());

        MdbAllocation persisted = h.awaitStatus(allocationId, MdbAllocationStatus.READY, Duration.ofSeconds(5));
        assertNotNull(persisted, "allocation got stuck in PROVISIONING — stale transition save "
            + "overwrote the worker's READY status");
        assertEquals(MdbAllocationStatus.READY, persisted.getStatus());
        assertNotNull(persisted.getReadyAt());
    }

    @Test
    void rejectedEventPersistsNothingAndSchedulesNoWorker() {
        Harness h = new Harness();
        ObjectId allocationId = h.seed(MdbAllocationStatus.PROVISIONING_PENDING);
        MdbAllocation before = h.snapshot(allocationId);

        // INITIATE_RECLAIM is only valid from READY — the send is rejected.
        MdbAllocationStateMachineService.TransitionResult result =
            h.service.transition(allocationId, MdbAllocationEventType.INITIATE_RECLAIM, "test");

        assertTrue(!result.accepted());
        MdbAllocation after = h.snapshot(allocationId);
        assertEquals(MdbAllocationStatus.PROVISIONING_PENDING, after.getStatus());
        assertEquals(before.getUpdatedAt(), after.getUpdatedAt(),
            "a rejected event must not touch the document");
        verifyNoInteractions(h.atlas);
    }

    /**
     * Mock repository plus a real Spring StateMachine factory. The store mimics
     * Mongo full-document mapping: each save serializes the entity as it is
     * observed at call time.
     */
    private static final class Harness {
        private final Thread testThread = Thread.currentThread();
        private final Map<ObjectId, MdbAllocation> store = new HashMap<>();
        private final CountDownLatch workerObservedReady = new CountDownLatch(1);
        private final MdbAllocationRepository repo = mock(MdbAllocationRepository.class);
        private final AtlasMdbService atlas = mock(AtlasMdbService.class);
        private final MdbAllocationStateMachineService service;
        private int savesOnTestThread;

        Harness() {
            doAnswer(inv -> save(inv.getArgument(0))).when(repo).save(any());
            doAnswer(inv -> {
                MdbAllocation stored;
                synchronized (store) {
                    stored = store.get(inv.getArgument(0));
                    stored = stored == null ? null : detachedCopy(stored);
                    // Mirror the production sequence: the worker's status check
                    // observed READY and skipped PROVISION_SUCCESS.
                    if (Thread.currentThread() != testThread
                        && stored != null && stored.getStatus() == MdbAllocationStatus.READY) {
                        workerObservedReady.countDown();
                    }
                }
                return Optional.ofNullable(stored);
            }).when(repo).findById(any());

            service = new MdbAllocationStateMachineService(
                buildFactory(), repo, atlas, mock(ApplicationEventPublisher.class),
                mock(SubscriptionRepository.class), mock(DeploymentStepRecorder.class));
        }

        ObjectId seed(MdbAllocationStatus status) {
            ObjectId id = new ObjectId();
            MdbAllocation pending = MdbAllocation.builder().id(id).status(status).build();
            synchronized (store) {
                store.put(id, detachedCopy(pending));
            }
            return id;
        }

        /**
         * The fixed code persists exactly once before scheduling the worker, so
         * that save is written immediately. The old code performed a second
         * save of the same shared entity after scheduling: that save converts
         * its stale document while the worker is already running and only writes
         * after the worker has persisted and observed READY — the exact
         * interleaving that overwrote READY back to PROVISIONING.
         */
        MdbAllocation save(MdbAllocation allocation) {
            boolean postSchedulingSaveOnTestThread =
                Thread.currentThread() == testThread && ++savesOnTestThread == 2;
            if (postSchedulingSaveOnTestThread) {
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

        MdbAllocation awaitStatus(ObjectId id, MdbAllocationStatus expected, Duration timeout)
                throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                MdbAllocation allocation = snapshot(id);
                if (allocation != null && allocation.getStatus() == expected) {
                    return allocation;
                }
                Thread.sleep(20);
            }
            return snapshot(id);
        }

        MdbAllocation snapshot(ObjectId id) {
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

        private static StateMachineFactory<MdbAllocationStatus, MdbAllocationEventType> buildFactory() {
            try {
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
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
