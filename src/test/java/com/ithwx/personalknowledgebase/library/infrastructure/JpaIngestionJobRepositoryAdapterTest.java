package com.ithwx.personalknowledgebase.library.infrastructure;

import com.ithwx.personalknowledgebase.library.domain.IngestionJob;
import com.ithwx.personalknowledgebase.library.domain.IngestionJobStatus;
import com.ithwx.personalknowledgebase.library.domain.IngestionStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JpaIngestionJobRepositoryAdapterTest {

    @Mock
    private JpaIngestionJobRepository jpaRepository;

    private JpaIngestionJobRepositoryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new JpaIngestionJobRepositoryAdapter(jpaRepository);
        when(jpaRepository.saveAndFlush(any(IngestionJobEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void shouldRestartExistingJobFromExtraction() {
        IngestionJobEntity entity = entity();
        entity.setStage(IngestionStage.INDEXING);
        entity.setStatus(IngestionJobStatus.FAILED);
        when(jpaRepository.findByDocumentIdForUpdate(1L))
                .thenReturn(Optional.of(entity));

        IngestionJob restarted = adapter.restart(1L);

        assertEquals(IngestionStage.EXTRACTING, restarted.getStage());
        assertEquals(IngestionJobStatus.PENDING, restarted.getStatus());
        verify(jpaRepository).insertPendingIfAbsent(
                org.mockito.ArgumentMatchers.eq(1L), any(LocalDateTime.class));
        verify(jpaRepository).saveAndFlush(any(IngestionJobEntity.class));
    }

    @Test
    void shouldClaimExpiredJobAndProtectLeaseOwnership() {
        LocalDateTime now = LocalDateTime.now();
        IngestionJobEntity entity = entity();
        entity.setStatus(IngestionJobStatus.RUNNING);
        entity.setLeaseOwner("old-worker");
        entity.setLeaseUntil(now.minusSeconds(1));
        when(jpaRepository.findNextClaimable(now)).thenReturn(Optional.of(entity));

        IngestionJob claimed = adapter.claimNext(
                "new-worker", now, now.plusMinutes(5)).orElseThrow();

        assertEquals("new-worker", claimed.getLeaseOwner());
        assertEquals(1, claimed.getAttemptCount());

        IngestionJobEntity claimedEntity = entity();
        claimedEntity.setStatus(IngestionJobStatus.RUNNING);
        claimedEntity.setLeaseOwner("new-worker");
        claimedEntity.setLeaseUntil(now.plusMinutes(5));
        when(jpaRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(claimedEntity));

        assertFalse(adapter.findOwnedForUpdate(10L, "stale-worker").isPresent());
        assertTrue(adapter.findOwnedForUpdate(10L, "new-worker").isPresent());
    }

    @Test
    void shouldRecoverTerminalJobWhenDocumentIsStillUnfinished() {
        IngestionJobEntity entity = entity();
        entity.setStage(IngestionStage.INDEXING);
        entity.setStatus(IngestionJobStatus.FAILED);
        entity.setLastError("服务在同步资料状态前退出");
        when(jpaRepository.findByDocumentIdForUpdate(1L))
                .thenReturn(Optional.of(entity));

        IngestionJob recovered = adapter.ensureExists(1L);

        assertEquals(IngestionStage.EXTRACTING, recovered.getStage());
        assertEquals(IngestionJobStatus.PENDING, recovered.getStatus());
        verify(jpaRepository).saveAndFlush(any(IngestionJobEntity.class));
    }

    private IngestionJobEntity entity() {
        IngestionJobEntity entity = new IngestionJobEntity();
        entity.setId(10L);
        entity.setVersion(0L);
        entity.setDocumentId(1L);
        entity.setStage(IngestionStage.EXTRACTING);
        entity.setStatus(IngestionJobStatus.PENDING);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }
}
