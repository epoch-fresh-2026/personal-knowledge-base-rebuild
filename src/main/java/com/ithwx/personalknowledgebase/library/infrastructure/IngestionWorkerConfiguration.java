package com.ithwx.personalknowledgebase.library.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
@EnableScheduling
class IngestionWorkerConfiguration {

    @Bean(name = "ingestionHeartbeatScheduler", destroyMethod = "shutdownNow")
                                     //Spring 容器关闭时，自动调用 shutdownNow () 关闭线程池，强制中断正在执行的任务，释放线程。
    ScheduledExecutorService ingestionHeartbeatScheduler() {
        AtomicInteger sequence = new AtomicInteger();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(2, runnable -> {
            Thread thread = new Thread(runnable, "ingestion-heartbeat-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    @Bean("ingestionTaskExecutor")
    TaskExecutor ingestionTaskExecutor(
            @Value("${app.ingestion.worker.core-pool-size:2}") int corePoolSize,
            @Value("${app.ingestion.worker.max-pool-size:4}") int maxPoolSize,
            @Value("${app.ingestion.worker.queue-capacity:20}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("ingestion-");
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
