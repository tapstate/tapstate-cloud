package io.tapstate.cloud.tawk;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Isolates Tawk delivery retries from control-plane maintenance scheduling. */
@Configuration
public class TawkWebhookSchedulerConfig {

    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("cloud-maintenance-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }

    @Bean(name = "tawkWebhookTaskScheduler")
    public ThreadPoolTaskScheduler tawkWebhookTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("tawk-webhook-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }
}
