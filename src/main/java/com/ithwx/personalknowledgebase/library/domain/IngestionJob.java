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
    private IngestionStage stage;//提取中，索引中，完成
    private IngestionJobStatus status;//待处理，运行中，已完成，失败，已取消
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
        requireFutureDeadline(until, now);
        boolean pending = status == IngestionJobStatus.PENDING;
        boolean expired = status == IngestionJobStatus.RUNNING
                && (leaseUntil == null || !leaseUntil.isAfter(now));
        if (!pending && !expired) {
            throw new IllegalStateException("入库任务当前不可领取");
        }
        String validOwner = requireOwner(owner);
        status = IngestionJobStatus.RUNNING;
        leaseOwner = validOwner;
        leaseUntil = Objects.requireNonNull(until);
        attemptCount++;
        lastError = null;
    }

    public boolean advance(
            String owner,
            IngestionStage nextStage,
            LocalDateTime nextLeaseUntil,
            LocalDateTime now
    ) {
        if (!holdsLease(owner, now)) {
            return false;
        }
        requireFutureDeadline(nextLeaseUntil, now);
        stage = Objects.requireNonNull(nextStage);
        leaseUntil = Objects.requireNonNull(nextLeaseUntil);
        return true;
    }

    public boolean renew(String owner, LocalDateTime until, LocalDateTime now) {
        if (!holdsLease(owner, now)) {
            return false;
        }
        requireFutureDeadline(until, now);
        leaseUntil = until;
        return true;
    }

    public boolean complete(String owner, LocalDateTime now) {
        if (!holdsLease(owner, now)) {
            return false;
        }
        stage = IngestionStage.COMPLETED;
        status = IngestionJobStatus.COMPLETED;
        clearLease();
        return true;
    }

    public boolean fail(String owner, String reason, LocalDateTime now) {
        if (!holdsLease(owner, now)) {
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

    public boolean holdsLease(String owner, LocalDateTime now) {
        return status == IngestionJobStatus.RUNNING
                && owner != null && Objects.equals(leaseOwner, owner)
                && leaseUntil != null && leaseUntil.isAfter(now);
    }

    private void requireFutureDeadline(LocalDateTime until, LocalDateTime now) {
        if (!Objects.requireNonNull(until).isAfter(Objects.requireNonNull(now))) {
            throw new IllegalArgumentException("租约截止时间必须晚于当前时间");
        }
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
