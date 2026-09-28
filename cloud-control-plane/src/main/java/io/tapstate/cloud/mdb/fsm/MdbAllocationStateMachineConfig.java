package io.tapstate.cloud.mdb.fsm;

import io.tapstate.cloud.mdb.MdbAllocationStatus;
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
 * MdbAllocation lifecycle state machine configuration.
 * <p>
 * State model:
 * <pre>
 * PROVISIONING_PENDING ──[START_PROVISION]──→ PROVISIONING
 * PROVISIONING         ──[PROVISION_SUCCESS]──→ READY
 * PROVISIONING         ──[PROVISION_FAIL]──→ PROVISIONING_FAILED
 * READY                ──[INITIATE_RECLAIM]──→ DELETING
 * DELETING             ──[RECLAIM_COMPLETE]──→ DELETED
 * DELETING             ──[RECLAIM_FAILED]──→ DELETED  (terminal regardless)
 * </pre>
 * <p>
 * {@link MdbAllocationStatus} enum is used directly as states (matching the SM state IDs).
 */
@Slf4j
@Configuration
@EnableStateMachineFactory(name = "mdbAllocationStateMachineFactory")
public class MdbAllocationStateMachineConfig
        extends EnumStateMachineConfigurerAdapter<MdbAllocationStatus, MdbAllocationEventType> {

    @Override
    public void configure(StateMachineStateConfigurer<MdbAllocationStatus, MdbAllocationEventType> states) throws Exception {
        states
            .withStates()
                .initial(MdbAllocationStatus.PROVISIONING_PENDING)
                .states(EnumSet.allOf(MdbAllocationStatus.class))
                .end(MdbAllocationStatus.DELETED);
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<MdbAllocationStatus, MdbAllocationEventType> transitions) throws Exception {
        transitions
            // ===== PROVISIONING_PENDING transitions =====
            .withExternal()
                .source(MdbAllocationStatus.PROVISIONING_PENDING)
                .event(MdbAllocationEventType.START_PROVISION)
                .target(MdbAllocationStatus.PROVISIONING)
            .and()

            // ===== PROVISIONING transitions =====
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

            // ===== READY transitions =====
            .withExternal()
                .source(MdbAllocationStatus.READY)
                .event(MdbAllocationEventType.INITIATE_RECLAIM)
                .target(MdbAllocationStatus.DELETING)
            .and()

            // ===== DELETING transitions =====
            .withExternal()
                .source(MdbAllocationStatus.DELETING)
                .event(MdbAllocationEventType.RECLAIM_COMPLETE)
                .target(MdbAllocationStatus.DELETED)
            .and()
            .withExternal()
                .source(MdbAllocationStatus.DELETING)
                .event(MdbAllocationEventType.RECLAIM_FAILED)
                .target(MdbAllocationStatus.DELETED);
    }

    @org.springframework.context.annotation.Bean
    public StateMachineListener<MdbAllocationStatus, MdbAllocationEventType> mdbAllocationStateMachineListener() {
        return new StateMachineListenerAdapter<>() {
            @Override
            public void stateChanged(State<MdbAllocationStatus, MdbAllocationEventType> from,
                                     State<MdbAllocationStatus, MdbAllocationEventType> to) {
                log.debug("[MdbAllocation FSM] stateChanged: {} → {}",
                    from != null ? from.getId() : "null",
                    to != null ? to.getId() : "null");
            }

            @Override
            public void transition(Transition<MdbAllocationStatus, MdbAllocationEventType> transition) {
                log.debug("[MdbAllocation FSM] transition: {} --[{}]--> {}",
                    transition.getSource().getId(),
                    transition.getTrigger().getEvent(),
                    transition.getTarget().getId());
            }
        };
    }
}
