package io.tapstate.cloud.subscription;

import io.tapstate.cloud.subscription.fsm.SubscriptionEventType;
import io.tapstate.cloud.subscription.fsm.SubscriptionStateMachineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.SchedulerLock;
import org.bson.types.ObjectId;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Periodic subscription reapers — run every 5 minutes under a ShedLock
 * distributed lock so only one replica runs each job at a time.
 * <p>
 * Two reapers:
 * <ul>
 *   <li><b>Period-end reaper</b> — finds PENDING_CANCEL subscriptions whose
 *       currentPeriodEnd ≤ now, fires PERIOD_END_REAPED which transitions to
 *       CANCELLED and cascades Cluster termination.</li>
 *   <li><b>Pending-payment expiry</b> — finds PENDING_PAYMENT subscriptions
 *       older than 24h (no webhook received), fires PAYMENT_PENDING_EXPIRED.
 *       These never had resources provisioned so there's nothing to cascade.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionReaper {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionStateMachineService stateMachineService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    /** Every 5 min — PENDING_CANCEL subscriptions whose billing period has ended. */
    @Scheduled(cron = "0 */5 * * * ?")
    @SchedulerLock(name = "subscription_period_end_reaper", lockAtMostFor = "4m")
    public void reapPeriodEnd() {
        Instant now = Instant.now();
        List<Subscription> expired = subscriptionRepository.findByStatusAndCurrentPeriodEndBefore(
                SubscriptionStatus.PENDING_CANCEL, now);

        if (expired.isEmpty()) return;
        log.info("SubscriptionReaper: found {} PENDING_CANCEL subscriptions whose period ended", expired.size());

        for (Subscription sub : expired) {
            try {
                var result = stateMachineService.transition(
                        sub.getId(), SubscriptionEventType.PERIOD_END_REAPED, "reaper:period_end");
                if (result.accepted()) {
                    // Publish cancel event → listeners cascade Cluster termination
                    eventPublisher.publishEvent(new SubscriptionCancelledEvent(
                            sub.getOrgId().toHexString(),
                            sub.getId().toHexString(),
                            "reaper:period_end"));
                    log.info("SubscriptionReaper: {} → CANCELLED (period_end reaped)", sub.getId());
                } else {
                    log.warn("SubscriptionReaper: PERIOD_END_REAPED rejected for {} (status={})",
                            sub.getId(), sub.getStatus());
                }
            } catch (Exception e) {
                log.error("SubscriptionReaper: PERIOD_END_REAPED failed for {}", sub.getId(), e);
            }
        }
    }

    /** Every 10 min — PENDING_PAYMENT subscriptions older than 24h. */
    @Scheduled(cron = "0 */10 * * * ?")
    @SchedulerLock(name = "subscription_pending_payment_expiry", lockAtMostFor = "9m")
    public void expirePendingPayment() {
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        List<Subscription> expired = subscriptionRepository.findByStatusAndCreatedAtBefore(
                SubscriptionStatus.PENDING_PAYMENT, cutoff);

        if (expired.isEmpty()) return;
        log.info("SubscriptionReaper: found {} expired PENDING_PAYMENT subscriptions (>24h)", expired.size());

        for (Subscription sub : expired) {
            try {
                var result = stateMachineService.transition(
                        sub.getId(), SubscriptionEventType.PAYMENT_PENDING_EXPIRED, "reaper:pending_payment");
                if (result.accepted()) {
                    log.info("SubscriptionReaper: {} → CANCELLED (pending_payment expired)", sub.getId());
                } else {
                    log.warn("SubscriptionReaper: PAYMENT_PENDING_EXPIRED rejected for {}", sub.getId());
                }
            } catch (Exception e) {
                log.error("SubscriptionReaper: PAYMENT_PENDING_EXPIRED failed for {}", sub.getId(), e);
            }
        }
    }
}
