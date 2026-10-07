package io.tapstate.cloud.mdb.fsm;

import io.tapstate.cloud.mdb.AtlasMdbService;
import io.tapstate.cloud.mdb.MdbAllocation;
import io.tapstate.cloud.mdb.MdbAllocationRepository;
import io.tapstate.cloud.mdb.MdbAllocationStatus;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.access.StateMachineAccess;
import org.springframework.statemachine.access.StateMachineAccessor;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.state.State;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deterministic concurrency tests for {@link MdbAllocationStateMachineService}.
 * <p>
 * The real Spring StateMachine is replaced by a mock whose reported target
 * state mirrors the event fired, while persistence is a mock repository backed
 * by an in-memory map. Barriers force the exact caller/async interleaving that
 * previously let the caller's stale second save overwrite the durable READY,
 * so the result does not depend on OS scheduling.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MdbAllocationStateMachineServiceTest {

    @Mock
    private AtlasMdbService atlasMdbService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private StateMachineFactory<MdbAllocationStatus, MdbAllocationEventType> factory;
    @Mock
    private StateMachine<MdbAllocationStatus, MdbAllocationEventType> machine;
    @SuppressWarnings("rawtypes")
    @Mock
    private StateMachineAccessor accessor;
    @SuppressWarnings("rawtypes")
    @Mock
    private StateMachineAccess smAccess;
    @Mock
    private State<MdbAllocationStatus, MdbAllocationEventType> machineState;
    @Mock
    private MdbAllocationRepository repo;

    private MdbAllocationStateMachineService service;

    // Backing store for the mock repository; values are snapshots.
    private final Map<ObjectId, MdbAllocation> store = new ConcurrentHashMap<>();
    private final List<String> regressions = new CopyOnWriteArrayList<>();

    // Released by the test once the async provisioning thread has marked READY
    // and skipped PROVISION_SUCCESS; the caller's stale second save waits for it.
    private CountDownLatch releaseSecondSave;
    // Counted down when the async thread reaches its post-provision read.
    private CountDownLatch skipReached;
    // Counted down when the caller's stale second save has landed.
    private CountDownLatch staleSaveDone;

    private int provisioningSaves;
    private boolean gateArmed;

    /** Per-thread target state so the async transition never races the main read. */
    private final ThreadLocal<MdbAllocationStatus> target = new ThreadLocal<>();

    @BeforeEach
    void setUp() {
        releaseSecondSave = new CountDownLatch(1);
        skipReached = new CountDownLatch(1);
        staleSaveDone = new CountDownLatch(1);
        provisioningSaves = 0;
        gateArmed = false;
        service = new MdbAllocationStateMachineService(factory, repo, atlasMdbService, eventPublisher);

        lenient().when(factory.getStateMachine(anyString())).thenReturn(machine);
        lenient().when(machine.getStateMachineAccessor()).thenReturn(accessor);
        lenient().doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<StateMachineAccess> consumer = inv.getArgument(0);
            consumer.accept(smAccess);
            return null;
        }).when(accessor).doWithAllRegions(any());
        lenient().when(machine.getState()).thenReturn(machineState);
        lenient().when(machineState.getId()).thenAnswer(inv -> target.get());
        // Report the state the fired event leads to before the service reads it back.
        lenient().when(machine.sendEvent(any(MdbAllocationEventType.class))).thenAnswer(inv -> {
            target.set(switch (inv.getArgument(0, MdbAllocationEventType.class)) {
                case START_PROVISION -> MdbAllocationStatus.PROVISIONING;
                case PROVISION_SUCCESS -> MdbAllocationStatus.READY;
                case PROVISION_FAIL -> MdbAllocationStatus.PROVISIONING_FAILED;
                case INITIATE_RECLAIM -> MdbAllocationStatus.DELETING;
                case RECLAIM_COMPLETE, RECLAIM_FAILED -> MdbAllocationStatus.DELETED;
            });
            return true;
        });

        lenient().when(repo.save(any())).thenAnswer(inv -> {
            MdbAllocation entity = inv.getArgument(0);

            // The caller's second PROVISIONING save is the stale write: hold it
            // until the test releases the barrier after the async skip.
            boolean block = gateArmed
                && entity.getStatus() == MdbAllocationStatus.PROVISIONING
                && ++provisioningSaves == 2;
            // Snapshot BEFORE the barrier: the stale write must serialize the
            // PROVISIONING view as it was at save-entry. The async thread mutates
            // the shared object to READY while we wait, so snapshotting after the
            // wait would hide the lost update instead of reproducing it.
            MdbAllocation persisted = snapshot(entity);
            if (block) {
                releaseSecondSave.await(5, TimeUnit.SECONDS);
            }

            // Detect the lost update at apply time: by now markReady has stored
            // READY, so a stale PROVISIONING snapshot landing over it regresses.
            MdbAllocation prevAtPut = store.get(entity.getId());
            if (prevAtPut != null
                && prevAtPut.getStatus() == MdbAllocationStatus.READY
                && persisted.getStatus() == MdbAllocationStatus.PROVISIONING) {
                regressions.add("READY -> PROVISIONING for " + entity.getId());
            }
            store.put(entity.getId(), persisted);
            if (block) {
                staleSaveDone.countDown();
            }
            return entity;
        });

        lenient().when(repo.findById(any())).thenAnswer(inv -> {
            ObjectId id = inv.getArgument(0);
            Optional<MdbAllocation> result = Optional.ofNullable(store.get(id)).map(this::snapshot);
            // The async thread's post-provision read observes READY here.
            result.filter(a -> a.getStatus() == MdbAllocationStatus.READY && a.getReadyAt() != null)
                .ifPresent(a -> skipReached.countDown());
            return result;
        });

        lenient().when(repo.findByClusterId(any())).thenReturn(Optional.empty());
        lenient().when(repo.findByOrgId(any())).thenReturn(List.of());
        lenient().when(repo.findByStatus(any())).thenReturn(List.of());
        lenient().when(repo.findByMdbClusterId(any())).thenReturn(List.of());
        lenient().when(repo.findBySubscriptionId(any())).thenReturn(List.of());
    }

    private MdbAllocation seed(MdbAllocationStatus status) {
        MdbAllocation a = MdbAllocation.builder()
            .id(new ObjectId())
            .status(status)
            .build();
        return repo.save(a);
    }

    @Test
    @DisplayName("START_PROVISION: async markReady must never be overwritten back to PROVISIONING")
    void startProvision_durableReadyIsNotLost() throws Exception {
        MdbAllocation allocation = seed(MdbAllocationStatus.PROVISIONING_PENDING);
        // Arm after seeding so the seeding save is not the caller's transition save.
        provisioningSaves = 0;
        gateArmed = true;

        // Async Atlas work runs immediately and ends in markReady (READY).
        doAnswer(inv -> {
            MdbAllocation a = inv.getArgument(0);
            a.setStatus(MdbAllocationStatus.READY);
            a.setReadyAt(Instant.now());
            repo.save(a); // markReady: durably READY
            return null;
        }).when(atlasMdbService).createDatabaseUser(any());

        // Run on a worker thread: the caller's stale second save blocks on the
        // barrier, so transition() cannot return on the test thread.
        Thread worker = Thread.ofPlatform().start(() ->
            service.transition(allocation.getId(), MdbAllocationEventType.START_PROVISION, "test"));

        assertTrue(skipReached.await(5, TimeUnit.SECONDS),
            "async thread never reached its post-provision read");
        // Release the blocked stale save (old code) - no-op path in fixed code.
        releaseSecondSave.countDown();
        staleSaveDone.await(2, TimeUnit.SECONDS);
        worker.join(5000);

        MdbAllocationStatus persisted = repo.findById(allocation.getId()).orElseThrow().getStatus();
        assertEquals(MdbAllocationStatus.READY, persisted,
            "the allocation must finish READY; a stale PROVISIONING save overwrote markReady");
        assertTrue(regressions.isEmpty(),
            "no save may demote READY back to PROVISIONING: " + regressions);
    }

    @Test
    @DisplayName("Rejected event: no persistence and no async work")
    void rejectedEvent_persistsNothing() {
        when(machine.sendEvent(any(MdbAllocationEventType.class))).thenReturn(false);
        target.set(MdbAllocationStatus.PROVISIONING_PENDING);

        MdbAllocation allocation = seed(MdbAllocationStatus.PROVISIONING_PENDING);
        releaseSecondSave.countDown();
        service.transition(allocation.getId(), MdbAllocationEventType.START_PROVISION, "test");

        assertEquals(MdbAllocationStatus.PROVISIONING_PENDING,
            repo.findById(allocation.getId()).orElseThrow().getStatus());
        verify(atlasMdbService, never()).createDatabase(any());
    }

    private MdbAllocation snapshot(MdbAllocation a) {
        return MdbAllocation.builder()
            .id(a.getId())
            .orgId(a.getOrgId())
            .subscriptionId(a.getSubscriptionId())
            .clusterId(a.getClusterId())
            .mdbClusterId(a.getMdbClusterId())
            .databaseName(a.getDatabaseName())
            .operatorDbName(a.getOperatorDbName())
            .viewsDbName(a.getViewsDbName())
            .dbUsername(a.getDbUsername())
            .dbPassword(a.getDbPassword())
            .status(a.getStatus())
            .provisionErrorCode(a.getProvisionErrorCode())
            .provisionErrorDetail(a.getProvisionErrorDetail())
            .createdAt(a.getCreatedAt())
            .updatedAt(a.getUpdatedAt())
            .readyAt(a.getReadyAt())
            .deletedAt(a.getDeletedAt())
            .build();
    }
}
