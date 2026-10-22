package io.tapstate.cloud.subscription.fsm;

import io.tapstate.cloud.subscription.SubscriptionStatus;
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
 * Subscription lifecycle state machine configuration.
 * <p>
 * State model:
 * <pre>
 * CHECKOUT_CREATED ──→ PENDING_PAYMENT ──CHECKOUT_COMPLETED──→ ACTIVE
 *                          │   └─CHECKOUT_COMPLETED_TRIAL──→ TRIAL ──PAYMENT_CONFIRMED──→ ACTIVE
 *                          │  (24h expired)                       │
 *                          └── PAYMENT_PENDING_EXPIRED → CANCELLED   CANCEL_AT_PERIOD_END_REQUESTED
 *                                                                 │
 *                                                                 └──→ PENDING_CANCEL
 *                                                              PERIOD_END_REAPED
 *                                                                 └──→ CANCELLED
 *
 * TRIAL ──PAYMENT_CONFIRMED──→ ACTIVE (legacy scaffold path, still valid)
 *   │
 *   └── CANCEL_REQUESTED / IMMEDIATE_CANCEL ──→ CANCELLED
 *
 * ACTIVE ──PAYMENT_FAILED──→ PAST_DUE ──PAYMENT_RECEIVED──→ ACTIVE
 *   │
 *   └── CANCEL_REQUESTED / IMMEDIATE_CANCEL ──→ CANCELLED
 *
 * PAST_DUE ── CANCEL_REQUESTED / IMMEDIATE_CANCEL ──→ CANCELLED
 *
 * CANCELLED ── ADMIN_RESTORE ──→ TRIAL
 * </pre>
 * {@link SubscriptionStatus} enum is used directly as states. CANCELLED is the
 * sole end state. Every non-CANCELLED state transitions to CANCELLED via either
 * {@code CANCEL_REQUESTED}, {@code IMMEDIATE_CANCEL}, or a scheduled reaper.
 */
@Slf4j
@Configuration
@EnableStateMachineFactory(name = "subscriptionStateMachineFactory")
public class SubscriptionStateMachineConfig extends EnumStateMachineConfigurerAdapter<SubscriptionStatus, SubscriptionEventType> {

    @Override
    public void configure(StateMachineStateConfigurer<SubscriptionStatus, SubscriptionEventType> states) throws Exception {
        states
            .withStates()
                .initial(SubscriptionStatus.TRIAL)
                .states(EnumSet.allOf(SubscriptionStatus.class))
                .end(SubscriptionStatus.CANCELLED);
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<SubscriptionStatus, SubscriptionEventType> transitions) throws Exception {
        transitions
            // ===== PENDING_PAYMENT (Stripe Checkout shell) =====
            .withExternal()
                .source(SubscriptionStatus.PENDING_PAYMENT)
                .event(SubscriptionEventType.CHECKOUT_COMPLETED)
                .target(SubscriptionStatus.ACTIVE)
            .and()
            // A trial spec binds the card without charging, so the subscription
            // starts in TRIAL and bills from the first invoice after the trial.
            .withExternal()
                .source(SubscriptionStatus.PENDING_PAYMENT)
                .event(SubscriptionEventType.CHECKOUT_COMPLETED_TRIAL)
                .target(SubscriptionStatus.TRIAL)
            .and()
            .withExternal()
                .source(SubscriptionStatus.PENDING_PAYMENT)
                .event(SubscriptionEventType.PAYMENT_PENDING_EXPIRED)
                .target(SubscriptionStatus.CANCELLED)
            .and()
            .withExternal()
                .source(SubscriptionStatus.PENDING_PAYMENT)
                .event(SubscriptionEventType.IMMEDIATE_CANCEL)
                .target(SubscriptionStatus.CANCELLED)
            .and()

            // ===== TRIAL transitions (legacy scaffold path) =====
            .withExternal()
                .source(SubscriptionStatus.TRIAL)
                .event(SubscriptionEventType.PAYMENT_CONFIRMED)
                .target(SubscriptionStatus.ACTIVE)
            .and()
            .withExternal()
                .source(SubscriptionStatus.TRIAL)
                .event(SubscriptionEventType.CANCEL_REQUESTED)
                .target(SubscriptionStatus.CANCELLED)
            .and()
            // Cancel during the trial keeps the instance to the end of the trial,
            // exactly like cancelling a paid period.
            .withExternal()
                .source(SubscriptionStatus.TRIAL)
                .event(SubscriptionEventType.CANCEL_AT_PERIOD_END_REQUESTED)
                .target(SubscriptionStatus.PENDING_CANCEL)
            .and()
            .withExternal()
                .source(SubscriptionStatus.TRIAL)
                .event(SubscriptionEventType.IMMEDIATE_CANCEL)
                .target(SubscriptionStatus.CANCELLED)
            .and()

            // ===== ACTIVE transitions =====
            .withExternal()
                .source(SubscriptionStatus.ACTIVE)
                .event(SubscriptionEventType.PAYMENT_FAILED)
                .target(SubscriptionStatus.PAST_DUE)
            .and()
            .withExternal()
                .source(SubscriptionStatus.ACTIVE)
                .event(SubscriptionEventType.CANCEL_REQUESTED)
                .target(SubscriptionStatus.CANCELLED)
            .and()
            .withExternal()
                .source(SubscriptionStatus.ACTIVE)
                .event(SubscriptionEventType.CANCEL_AT_PERIOD_END_REQUESTED)
                .target(SubscriptionStatus.PENDING_CANCEL)
            .and()
            .withExternal()
                .source(SubscriptionStatus.ACTIVE)
                .event(SubscriptionEventType.IMMEDIATE_CANCEL)
                .target(SubscriptionStatus.CANCELLED)
            .and()

            // ===== PAST_DUE transitions =====
            .withExternal()
                .source(SubscriptionStatus.PAST_DUE)
                .event(SubscriptionEventType.PAYMENT_RECEIVED)
                .target(SubscriptionStatus.ACTIVE)
            .and()
            .withExternal()
                .source(SubscriptionStatus.PAST_DUE)
                .event(SubscriptionEventType.CANCEL_REQUESTED)
                .target(SubscriptionStatus.CANCELLED)
            .and()
            .withExternal()
                .source(SubscriptionStatus.PAST_DUE)
                .event(SubscriptionEventType.IMMEDIATE_CANCEL)
                .target(SubscriptionStatus.CANCELLED)
            .and()

            // ===== PENDING_CANCEL transitions =====
            .withExternal()
                .source(SubscriptionStatus.PENDING_CANCEL)
                .event(SubscriptionEventType.PERIOD_END_REAPED)
                .target(SubscriptionStatus.CANCELLED)
            .and()
            .withExternal()
                .source(SubscriptionStatus.PENDING_CANCEL)
                .event(SubscriptionEventType.IMMEDIATE_CANCEL)
                .target(SubscriptionStatus.CANCELLED)
            .and()

            // ===== CANCELLED transitions — only ADMIN_RESTORE can bring back =====
            .withExternal()
                .source(SubscriptionStatus.CANCELLED)
                .event(SubscriptionEventType.ADMIN_RESTORE)
                .target(SubscriptionStatus.TRIAL);
    }

    @org.springframework.context.annotation.Bean
    public StateMachineListener<SubscriptionStatus, SubscriptionEventType> subscriptionStateMachineListener() {
        return new StateMachineListenerAdapter<>() {
            @Override
            public void stateChanged(State<SubscriptionStatus, SubscriptionEventType> from, State<SubscriptionStatus, SubscriptionEventType> to) {
                log.debug("[Subscription FSM] stateChanged: {} → {}",
                    from != null ? from.getId() : "null",
                    to != null ? to.getId() : "null");
            }

            @Override
            public void transition(Transition<SubscriptionStatus, SubscriptionEventType> transition) {
                log.debug("[Subscription FSM] transition: {} --[{}]--> {}",
                    transition.getSource().getId(),
                    transition.getTrigger().getEvent(),
                    transition.getTarget().getId());
            }
        };
    }
}
