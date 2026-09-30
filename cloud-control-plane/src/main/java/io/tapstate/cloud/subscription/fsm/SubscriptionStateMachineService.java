package io.tapstate.cloud.subscription.fsm;

import io.tapstate.cloud.subscription.Subscription;
import io.tapstate.cloud.subscription.SubscriptionRepository;
import io.tapstate.cloud.subscription.SubscriptionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.bson.types.ObjectId;

/**
 * Subscription state machine orchestrator — mirrors the Cluster FSM factory pattern
 * with DB state reset + event send + persist cycle.
 * <p>
 * Wires {@link io.tapstate.cloud.subscription.SubscriptionService} (which previously
 * used direct field assignment) to the state machine so every status transition goes
 * through a validated path.
 */
@Service
@Slf4j
public class SubscriptionStateMachineService {

    private final StateMachineFactory<SubscriptionStatus, SubscriptionEventType> subscriptionStateMachineFactory;
    private final SubscriptionRepository subscriptionRepository;

    public SubscriptionStateMachineService(
        @Qualifier("subscriptionStateMachineFactory")
        StateMachineFactory<SubscriptionStatus, SubscriptionEventType> subscriptionStateMachineFactory,
        SubscriptionRepository subscriptionRepository) {
        this.subscriptionStateMachineFactory = subscriptionStateMachineFactory;
        this.subscriptionRepository = subscriptionRepository;
    }

    /**
     * Send an event to the Subscription state machine and persist the new state.
     *
     * @param subscriptionId target Subscription UUID
     * @param event          event to fire
     * @param operator       human / system identifier (for audit)
     */
    public TransitionResult transition(ObjectId subscriptionId, SubscriptionEventType event, String operator) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new IllegalStateException("Subscription not found: " + subscriptionId));

        StateMachine<SubscriptionStatus, SubscriptionEventType> sm =
            subscriptionStateMachineFactory.getStateMachine("subscription-" + subscriptionId.toHexString());

        sm.start();
        resetTo(sm, subscription.getStatus());

        boolean accepted = sm.sendEvent(event);

        TransitionResult result;
        if (accepted) {
            SubscriptionStatus newStatus = sm.getState().getId();
            subscription.setStatus(newStatus);
            subscription.setUpdatedAt(Instant.now());

            // Apply side-effects
            if (newStatus == SubscriptionStatus.ACTIVE) {
                // Reset billing period on activation (from TRIAL / PAST_DUE)
                Instant now = Instant.now();
                subscription.setCurrentPeriodStart(now);
                subscription.setCurrentPeriodEnd(now.plus(30, ChronoUnit.DAYS));
            }

            subscriptionRepository.save(subscription);
            log.info("[Subscription FSM] {} subscription {}: {} → {} on event={}",
                operator, subscriptionId, subscription.getStatus(), newStatus, event);
            result = TransitionResult.accepted(newStatus);
        } else {
            log.warn("[Subscription FSM] {} subscription {}: event {} rejected from state {}",
                operator, subscriptionId, event, subscription.getStatus());
            result = TransitionResult.rejected(subscription.getStatus());
        }

        sm.stop();
        return result;
    }

    private void resetTo(StateMachine<SubscriptionStatus, SubscriptionEventType> sm, SubscriptionStatus persistedStatus) {
        sm.getStateMachineAccessor()
            .doWithAllRegions(accessor -> accessor.resetStateMachine(
                new DefaultStateMachineContext<>(persistedStatus, null, null, null)
            ));
    }

    public record TransitionResult(boolean accepted, SubscriptionStatus newStatus) {
        public static TransitionResult accepted(SubscriptionStatus s) { return new TransitionResult(true, s); }
        public static TransitionResult rejected(SubscriptionStatus s) { return new TransitionResult(false, s); }
    }
}
