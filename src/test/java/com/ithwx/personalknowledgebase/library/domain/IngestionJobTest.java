package com.ithwx.personalknowledgebase.library.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestionJobTest {

    @Test
    void shouldClaimAdvanceAndCompleteJobWithSameLease() {
        LocalDateTime now = LocalDateTime.now();
        IngestionJob job = IngestionJob.pending(1L);

        job.claim("worker-1", now.plusMinutes(5), now);

        assertEquals(IngestionJobStatus.RUNNING, job.getStatus());
        assertEquals(1, job.getAttemptCount());
        assertTrue(job.advance(
                "worker-1", IngestionStage.INDEXING, now.plusMinutes(10), now));
        assertTrue(job.complete("worker-1", now));
        assertEquals(IngestionStage.COMPLETED, job.getStage());
        assertEquals(IngestionJobStatus.COMPLETED, job.getStatus());
        assertNull(job.getLeaseOwner());
    }

    @Test
    void shouldRejectUnexpiredLeaseAndIgnoreStaleWorker() {
        LocalDateTime now = LocalDateTime.now();
        IngestionJob job = IngestionJob.pending(1L);
        job.claim("worker-1", now.plusMinutes(5), now);

        assertThrows(IllegalStateException.class,
                () -> job.claim("worker-2", now.plusMinutes(6), now.plusMinutes(1)));
        assertFalse(job.advance(
                "worker-2", IngestionStage.INDEXING, now.plusMinutes(6), now));
        assertFalse(job.fail("worker-2", "旧 Worker 失败", now));
        assertEquals(IngestionJobStatus.RUNNING, job.getStatus());
    }

    @Test
    void shouldAllowExpiredLeaseToBeReclaimedAndResetForRetry() {
        LocalDateTime now = LocalDateTime.now();
        IngestionJob job = IngestionJob.pending(1L);
        job.claim("worker-1", now.plusSeconds(1), now);

        job.claim("worker-2", now.plusMinutes(5), now.plusSeconds(2));
        assertEquals("worker-2", job.getLeaseOwner());
        assertEquals(2, job.getAttemptCount());

        job.reset();
        assertEquals(IngestionStage.EXTRACTING, job.getStage());
        assertEquals(IngestionJobStatus.PENDING, job.getStatus());
        assertNull(job.getLeaseOwner());
    }

    @Test
    void shouldRejectExpiredOwnerEvenBeforeAnotherWorkerClaims() {
        LocalDateTime now = LocalDateTime.now();
        IngestionJob job = IngestionJob.pending(1L);
        job.claim("worker", now.plusSeconds(1), now);
        LocalDateTime expired = now.plusSeconds(1);
        assertFalse(job.holdsLease("worker", expired));
        assertFalse(job.renew("worker", expired.plusMinutes(5), expired));
        assertFalse(job.advance("worker", IngestionStage.INDEXING, expired.plusMinutes(5), expired));
        assertFalse(job.complete("worker", expired));
        assertFalse(job.fail("worker", "迟到错误", expired));
        assertEquals(IngestionJobStatus.RUNNING, job.getStatus());
    }

    @Test
    void shouldExtendLiveLeaseWithoutChangingStageOrAttemptCount() {
        LocalDateTime now = LocalDateTime.now();
        IngestionJob job = IngestionJob.pending(1L);
        job.claim("worker", now.plusSeconds(2), now);
        assertTrue(job.renew("worker", now.plusSeconds(10), now.plusSeconds(1)));
        assertTrue(job.holdsLease("worker", now.plusSeconds(3)));
        assertEquals(IngestionStage.EXTRACTING, job.getStage());
        assertEquals(1, job.getAttemptCount());
    }

    @Test
    void shouldRejectInvalidClaimDeadlineWithoutMutatingJob() {
        LocalDateTime now = LocalDateTime.now();
        IngestionJob job = IngestionJob.pending(1L);
        assertThrows(IllegalArgumentException.class, () -> job.claim("worker", now, now));
        assertEquals(IngestionJobStatus.PENDING, job.getStatus());
    }
}
