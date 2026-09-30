package com.ithwx.personalknowledgebase.library.infrastructure;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

interface JpaIngestionJobRepository extends JpaRepository<IngestionJobEntity, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO ingestion_job (
                version, document_id, stage, status, attempt_count,
                created_at, updated_at
            ) VALUES (
                0, :documentId, 'EXTRACTING', 'PENDING', 0,
                :now, :now
            )
            ON CONFLICT (document_id) DO NOTHING
            """, nativeQuery = true)
    int insertPendingIfAbsent(
            @Param("documentId") Long documentId,
            @Param("now") LocalDateTime now
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from IngestionJobEntity job where job.documentId = :documentId")
    Optional<IngestionJobEntity> findByDocumentIdForUpdate(
            @Param("documentId") Long documentId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from IngestionJobEntity job where job.id = :id")
    Optional<IngestionJobEntity> findByIdForUpdate(@Param("id") Long id);

    @Query(value = """
            SELECT *
            FROM ingestion_job
            WHERE status = 'PENDING'
               OR (status = 'RUNNING' AND (lease_until IS NULL OR lease_until <= :now))
            ORDER BY updated_at, id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<IngestionJobEntity> findNextClaimable(@Param("now") LocalDateTime now);
}
