package com.ithwx.personalknowledgebase.library.infrastructure;

import com.ithwx.personalknowledgebase.library.domain.IngestionJob;
import com.ithwx.personalknowledgebase.library.domain.IngestionJobRepository;
import com.ithwx.personalknowledgebase.library.domain.IngestionJobStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.NoSuchElementException;
import java.util.Optional;

@Repository
public class JpaIngestionJobRepositoryAdapter implements IngestionJobRepository {

    private final JpaIngestionJobRepository jpaRepository;

    public JpaIngestionJobRepositoryAdapter(JpaIngestionJobRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public IngestionJob restart(Long documentId) {
        ensureRow(documentId);//保证记录存在，没有就新增
        IngestionJob job = requiredByDocumentIdForUpdate(documentId);//加锁读取这条记录，不存在就报错
        job.reset();
        return toDomain(jpaRepository.saveAndFlush(toEntity(job)));
    }

    @Override
    @Transactional
    public IngestionJob ensureExists(Long documentId) {
        ensureRow(documentId);
        IngestionJob job = requiredByDocumentIdForUpdate(documentId);
        if (job.getStatus() == IngestionJobStatus.COMPLETED
                || job.getStatus() == IngestionJobStatus.FAILED
                || job.getStatus() == IngestionJobStatus.CANCELLED) {
            job.reset();
            return toDomain(jpaRepository.saveAndFlush(toEntity(job)));
        }
        return job;
    }

    @Override
    @Transactional
    public Optional<IngestionJob> claimNext(
            String leaseOwner,
            LocalDateTime now,
            LocalDateTime leaseUntil
    ) {
        return jpaRepository.findNextClaimable(now).map(entity -> {
            IngestionJob job = toDomain(entity);
            job.claim(leaseOwner, leaseUntil, now);
            return toDomain(jpaRepository.saveAndFlush(toEntity(job)));
        });
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<IngestionJob> findOwnedForUpdate(Long jobId, String leaseOwner) {
        return jpaRepository.findByIdForUpdate(jobId)
                .map(this::toDomain)
                .filter(job -> job.holdsLease(leaseOwner, LocalDateTime.now()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(IngestionJob job) {
        jpaRepository.saveAndFlush(toEntity(job));
    }

    @Override
    @Transactional
    public boolean renew(Long jobId, String leaseOwner, Duration leaseDuration) {
        Optional<IngestionJob> candidate = jpaRepository.findByIdForUpdate(jobId).map(this::toDomain);
        LocalDateTime now = LocalDateTime.now();
        if (candidate.isEmpty() || !candidate.get().renew(leaseOwner, now.plus(leaseDuration), now)) {
            return false;
        }
        jpaRepository.saveAndFlush(toEntity(candidate.get()));
        return true;
    }

    @Override
    @Transactional
    public void cancelByDocumentId(Long documentId) {
        ensureRow(documentId);
        jpaRepository.findByDocumentIdForUpdate(documentId).ifPresent(entity -> {
            IngestionJob job = toDomain(entity);
            job.cancel();
            jpaRepository.saveAndFlush(toEntity(job));
        });
    }

    private void ensureRow(Long documentId) {
        jpaRepository.insertPendingIfAbsent(documentId, LocalDateTime.now());
    }

    private IngestionJob requiredByDocumentIdForUpdate(Long documentId) {
        return jpaRepository.findByDocumentIdForUpdate(documentId)
                .map(this::toDomain)
                .orElseThrow(() -> new NoSuchElementException(
                        "资料入库任务创建失败：" + documentId));
    }

    private IngestionJob toDomain(IngestionJobEntity entity) {
        IngestionJob job = new IngestionJob();
        job.setId(entity.getId());
        job.setVersion(entity.getVersion());
        job.setDocumentId(entity.getDocumentId());
        job.setStage(entity.getStage());
        job.setStatus(entity.getStatus());
        job.setAttemptCount(entity.getAttemptCount());
        job.setLeaseOwner(entity.getLeaseOwner());
        job.setLeaseUntil(entity.getLeaseUntil());
        job.setLastError(entity.getLastError());
        job.setCreatedAt(entity.getCreatedAt());
        job.setUpdatedAt(entity.getUpdatedAt());
        return job;
    }

    private IngestionJobEntity toEntity(IngestionJob job) {
        IngestionJobEntity entity = new IngestionJobEntity();
        entity.setId(job.getId());
        entity.setVersion(job.getVersion());
        entity.setDocumentId(job.getDocumentId());
        entity.setStage(job.getStage());
        entity.setStatus(job.getStatus());
        entity.setAttemptCount(job.getAttemptCount());
        entity.setLeaseOwner(job.getLeaseOwner());
        entity.setLeaseUntil(job.getLeaseUntil());
        entity.setLastError(job.getLastError());
        entity.setCreatedAt(job.getCreatedAt());
        entity.setUpdatedAt(job.getUpdatedAt());
        return entity;
    }
}
