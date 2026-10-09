package com.ithwx.personalknowledgebase.library.application;

import com.ithwx.personalknowledgebase.library.domain.IngestionJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngestionHeartbeatTest {
    @Mock private IngestionJobRepository jobs;
    @Mock private ScheduledExecutorService scheduler;
    @Mock private ScheduledFuture<?> future;
    private IngestionHeartbeat heartbeat;
    private final Duration duration = Duration.ofMinutes(5);

    @BeforeEach
    void setUp() {
        heartbeat = new IngestionHeartbeat(jobs, scheduler, duration, Duration.ofSeconds(10));
    }

    @Test
    void shouldRenewAndCancelTimerWhenClosed() {
        Runnable pulse;
        try (var lease = watch()) {
            when(jobs.renew(1L, "worker", duration)).thenReturn(true);
            pulse = pulse();
            pulse.run();
            assertTrue(lease.isValid());
        }
        verify(future).cancel(false);
        pulse.run();
        verify(jobs, times(1)).renew(1L, "worker", duration);
    }

    @Test
    void shouldStopAdvancingAfterLeaseIsLost() {
        try (var lease = watch()) {
            pulse().run();
            assertFalse(lease.isValid());
        }
    }

    @Test
    void shouldFailClosedWhenRenewalOutcomeIsUnknown() {
        try (var lease = watch()) {
            when(jobs.renew(1L, "worker", duration)).thenThrow(new IllegalStateException("数据库暂时不可用"));
            pulse().run();
            assertFalse(lease.isValid());
        }
    }

    @Test
    void shouldRejectHeartbeatIntervalNotShorterThanLease() {
        assertThrows(IllegalArgumentException.class, () -> new IngestionHeartbeat(jobs, scheduler, duration, duration));
    }

    private IngestionHeartbeat.Lease watch() {
        doReturn(future).when(scheduler).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));
        return heartbeat.watch(1L, "worker");
    }

    private Runnable pulse() {
        ArgumentCaptor<Runnable> pulse = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleWithFixedDelay(pulse.capture(), eq(10000L), eq(10000L), eq(TimeUnit.MILLISECONDS));
        return pulse.getValue();
    }
}
