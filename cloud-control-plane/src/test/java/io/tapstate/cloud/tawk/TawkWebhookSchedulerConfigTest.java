package io.tapstate.cloud.tawk;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

class TawkWebhookSchedulerConfigTest {

    @Test
    void blockedTawkDeliveryDoesNotBlockMaintenanceScheduling() throws Exception {
        CountDownLatch deliveryStarted = new CountDownLatch(1);
        CountDownLatch releaseDelivery = new CountDownLatch(1);
        CountDownLatch maintenanceRan = new CountDownLatch(1);

        TawkWebhookEventRepository eventRepository = mock(TawkWebhookEventRepository.class);
        FeishuBotClient feishuBotClient = mock(FeishuBotClient.class);
        TawkWebhookEvent pending = TawkWebhookEvent.builder()
            .eventId("event-1")
            .propertyId("property-1")
            .eventType("chat:start")
            .deliveryStatus(TawkWebhookEvent.DeliveryStatus.PENDING)
            .attemptCount(0)
            .nextAttemptAt(Instant.EPOCH)
            .build();
        when(eventRepository
                .findTop20ByDeliveryStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                    any(), any()))
            .thenReturn(List.of(pending));
        doAnswer(invocation -> {
            deliveryStarted.countDown();
            releaseDelivery.await(5, TimeUnit.SECONDS);
            return FeishuBotClient.DeliveryResult.success();
        }).when(feishuBotClient).send(anyString(), anyString(), anyString());

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
            "tawk-scheduler-test", Map.of("tapstate.cloud.tawk.retry-interval-ms", "1")));
        context.registerBean(TawkWebhookEventRepository.class, () -> eventRepository);
        context.registerBean(FeishuBotClient.class, () -> feishuBotClient);
        context.registerBean(
            MaintenanceScheduledTask.class,
            () -> new MaintenanceScheduledTask(maintenanceRan));
        context.register(ScheduledTestConfiguration.class);

        try {
            context.refresh();
            assertTrue(deliveryStarted.await(5, TimeUnit.SECONDS),
                "The Tawk retry task should start on its dedicated scheduler");
            assertTrue(maintenanceRan.await(2, TimeUnit.SECONDS),
                "Maintenance scheduling should continue while Feishu delivery is blocked");
        } finally {
            releaseDelivery.countDown();
            context.close();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @Import({TawkWebhookSchedulerConfig.class, TawkWebhookService.class})
    static class ScheduledTestConfiguration {}

    static class MaintenanceScheduledTask {

        private final CountDownLatch maintenanceRan;

        MaintenanceScheduledTask(CountDownLatch maintenanceRan) {
            this.maintenanceRan = maintenanceRan;
        }

        @Scheduled(fixedDelay = 10)
        void run() {
            maintenanceRan.countDown();
        }
    }
}
