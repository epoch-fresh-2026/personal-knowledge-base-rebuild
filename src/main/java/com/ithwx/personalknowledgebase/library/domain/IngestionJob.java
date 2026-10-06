package com.ithwx.personalknowledgebase.library.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Objects;

@Getter
@Setter
@NoArgsConstructor
public class IngestionJob {

    private Long id;
    private Long version;
    private Long documentId;
    private IngestionStage stage;
    private IngestionJobStatus status;
    private int attemptCount;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static IngestionJob pending(Long documentId) {
        IngestionJob job = new IngestionJob();
        job.setDocumentId(Objects.requireNonNull(documentId));
        job.reset();
        return job;
    }

    public void reset() {
        stage = IngestionStage.EXTRACTING;
        status = IngestionJobStatus.PENDING;
        leaseOwner = null;
        leaseUntil = null;
        lastError = null;
    }

    public void claim(String owner, LocalDateTime until, LocalDateTime now) {
        boolean pending = status == IngestionJobStatus.PENDING;
        boolean expired = status == IngestionJobStatus.RUNNING
                && (leaseUntil == null || !leaseUntil.isAfter(now));
        if (!pending && !expired) {
            throw new IllegalStateException("入库任务当前不可领取");
        }
        status = IngestionJobStatus.RUNNING;
        leaseOwner = requireOwner(owner);
        leaseUntil = Objects.requireNonNull(until);
        attemptCount++;
        lastError = null;
    }

    public boolean advance(
            String owner,
            IngestionStage nextStage,
            LocalDateTime nextLeaseUntil
    ) {
        if (!isOwnedBy(owner)) {
            return false;
        }
        stage = Objects.requireNonNull(nextStage);
        leaseUntil = Objects.requireNonNull(nextLeaseUntil);
        return true;
    }

    public boolean complete(String owner) {
        if (!isOwnedBy(owner)) {
            return false;
        }
        stage = IngestionStage.COMPLETED;
        status = IngestionJobStatus.COMPLETED;
        clearLease();
        return true;
    }

    public boolean fail(String owner, String reason) {
        if (!isOwnedBy(owner)) {
            return false;
        }
        status = IngestionJobStatus.FAILED;
        lastError = reason;
        clearLease();
        return true;
    }

    public void cancel() {
        status = IngestionJobStatus.CANCELLED;
        clearLease();
    }

    private boolean isOwnedBy(String owner) {
        return status == IngestionJobStatus.RUNNING
                && Objects.equals(leaseOwner, owner);
    }

    private String requireOwner(String owner) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("任务租约所有者不能为空");
        }
        return owner;
    }

    private void clearLease() {
        leaseOwner = null;
        leaseUntil = null;
    }
}
