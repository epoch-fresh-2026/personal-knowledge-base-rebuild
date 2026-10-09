package com.ithwx.personalknowledgebase.library.application;

import com.ithwx.personalknowledgebase.library.domain.IngestionJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class IngestionHeartbeat {
    private static final Logger log = LoggerFactory.getLogger(IngestionHeartbeat.class);
    private final IngestionJobRepository jobs;
    private final ScheduledExecutorService scheduler;
    private final Duration leaseDuration;
    private final Duration interval;

    public IngestionHeartbeat(IngestionJobRepository jobs,
                              @Qualifier("ingestionHeartbeatScheduler") ScheduledExecutorService scheduler,
                              @Value("${app.ingestion.lease-duration:15m}") Duration leaseDuration,
                              @Value("${app.ingestion.heartbeat-interval:30s}") Duration interval) {
        if (interval.toMillis() < 1 || leaseDuration.isNegative() || leaseDuration.isZero()
                || interval.compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException("心跳间隔必须为正数且小于租约时长");
        }
        this.jobs = jobs;
        this.scheduler = scheduler;
        this.leaseDuration = leaseDuration;
        this.interval = interval;
    }

    public Lease watch(Long jobId, String owner) {
        Lease lease = new Lease();
        lease.future = scheduler.scheduleWithFixedDelay(() -> {
            if (!lease.valid.get()) {
                return;
            }
            try {
                if (!jobs.renew(jobId, owner, leaseDuration)) {
                    lease.valid.set(false);
                    log.info("Ingestion lease lost: jobId={}", jobId);
                }
            } catch (RuntimeException exception) {
                // 续租状态不确定时不提交结果；任务由租约过期恢复机制接手。
                lease.valid.set(false);
                log.warn("Ingestion heartbeat failed: jobId={}", jobId, exception);
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        return lease;
    }

    public static final class Lease implements AutoCloseable {
        private final AtomicBoolean valid = new AtomicBoolean(true);
        private ScheduledFuture<?> future;

        public boolean isValid() {
            return valid.get();
        }

        @Override
        public void close() {
            valid.set(false);
            future.cancel(false);
        }
    }
}
