package io.tapstate.cloud.cluster.fsm;

import io.tapstate.cloud.cluster.ClusterState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.EnumStateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;
import org.springframework.statemachine.listener.StateMachineListener;
import org.springframework.statemachine.listener.StateMachineListenerAdapter;
import org.springframework.statemachine.state.State;
import org.springframework.statemachine.transition.Transition;

import java.util.EnumSet;

/**
 * Cluster lifecycle state machine configuration.
 * <p>
 * State model (Week 2 — DRAINED / TERMINATED deferred to Week 4):
 * <pre>
 * PROVISIONING_PENDING ──[START_PROVISION]──→ PROVISIONING
 * PROVISIONING        ──[PROVISION_SUCCESS]──→ READY
 * PROVISIONING        ──[PROVISION_FAIL]──→ PROVISIONING_FAILED
 * PROVISIONING_FAILED ──[RETRY]──→ PROVISIONING_PENDING
 * READY               ──[HEARTBEAT_TIMEOUT]──→ DEGRADED
 * DEGRADED            ──[HEARTBEAT_RECOVERY]──→ READY
 * READY               ──[INITIATE_TERMINATION]──→ DRAINED   (Week 4)
 * DRAINED             ──[DRAIN_COMPLETE / DRAIN_FAILED]──→ TERMINATED   (Week 4)
 * </pre>
 * <p>
 * Each transition has an associated action — see {@link ClusterFsmActions}.
 * Uses Spring Statemachine 4.0 factory adapter (pattern referenced from
 * hare {@code JobStateMachineConfig}).
 */
@Slf4j
@Configuration
@EnableStateMachineFactory(name = "clusterStateMachineFactory")
public class ClusterStateMachineConfig extends EnumStateMachineConfigurerAdapter<ClusterState, ClusterEventType> {

    @Override
    public void configure(StateMachineStateConfigurer<ClusterState, ClusterEventType> states) throws Exception {
        states
            .withStates()
                .initial(ClusterState.PROVISIONING_PENDING)
                .states(EnumSet.allOf(ClusterState.class));
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<ClusterState, ClusterEventType> transitions) throws Exception {
        transitions
            // ===== PROVISIONING_PENDING transitions =====
            .withExternal()
                .source(ClusterState.PROVISIONING_PENDING)
                .event(ClusterEventType.START_PROVISION)
                .target(ClusterState.PROVISIONING)
            .and()

            // ===== PROVISIONING transitions =====
            .withExternal()
                .source(ClusterState.PROVISIONING)
                .event(ClusterEventType.PROVISION_SUCCESS)
                .target(ClusterState.READY)
            .and()
            .withExternal()
                .source(ClusterState.PROVISIONING)
                .event(ClusterEventType.PROVISION_FAIL)
                .target(ClusterState.PROVISIONING_FAILED)
            .and()

            // ===== PROVISIONING_FAILED transitions =====
            .withExternal()
                .source(ClusterState.PROVISIONING_FAILED)
                .event(ClusterEventType.RETRY)
                .target(ClusterState.PROVISIONING_PENDING)
            .and()

            // ===== READY transitions =====
            .withExternal()
                .source(ClusterState.READY)
                .event(ClusterEventType.HEARTBEAT_TIMEOUT)
                .target(ClusterState.DEGRADED)
            .and()
            // Week 4: READY → DRAINED (graceful drain before terminate)
            // Week 2 cancel uses direct READY → TERMINATED below
            .withExternal()
                .source(ClusterState.READY)
                .event(ClusterEventType.INITIATE_TERMINATION)
                .target(ClusterState.TERMINATED)
            .and()
            // Week 4: DRAINED → TERMINATED kept for graceful drain path
            .withExternal()
                .source(ClusterState.DRAINED)
                .event(ClusterEventType.DRAIN_COMPLETE)
                .target(ClusterState.TERMINATED)
            .and()
            .withExternal()
                .source(ClusterState.DRAINED)
                .event(ClusterEventType.DRAIN_FAILED)
                .target(ClusterState.TERMINATED)
            .and()
            // PROVISIONING_FAILED / DEGRADED → TERMINATED on cancel
            .withExternal()
                .source(ClusterState.PROVISIONING_FAILED)
                .event(ClusterEventType.INITIATE_TERMINATION)
                .target(ClusterState.TERMINATED)
            .and()
            .withExternal()
                .source(ClusterState.DEGRADED)
                .event(ClusterEventType.INITIATE_TERMINATION)
                .target(ClusterState.TERMINATED)
            .and()

            // ===== DEGRADED transitions =====
            .withExternal()
                .source(ClusterState.DEGRADED)
                .event(ClusterEventType.HEARTBEAT_RECOVERY)
                .target(ClusterState.READY);
    }

    /**
     * Debug listener — logs every transition for development diagnostics.
     * Not persisted; production observability goes via structured event log.
     */
    @org.springframework.context.annotation.Bean
    public StateMachineListener<ClusterState, ClusterEventType> clusterStateMachineListener() {
        return new StateMachineListenerAdapter<>() {
            @Override
            public void stateChanged(State<ClusterState, ClusterEventType> from, State<ClusterState, ClusterEventType> to) {
                log.debug("[Cluster FSM] stateChanged: {} → {}",
                    from != null ? from.getId() : "null",
                    to != null ? to.getId() : "null");
            }

            @Override
            public void transition(Transition<ClusterState, ClusterEventType> transition) {
                log.debug("[Cluster FSM] transition: {} --[{}]--> {}",
                    transition.getSource().getId(),
                    transition.getTrigger().getEvent(),
                    transition.getTarget().getId());
            }
        };
    }
}
