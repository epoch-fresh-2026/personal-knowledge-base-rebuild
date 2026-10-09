package com.ithwx.personalknowledgebase.library.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

public interface IngestionJobRepository {

    IngestionJob restart(Long documentId);

    IngestionJob ensureExists(Long documentId);

    Optional<IngestionJob> claimNext(
            String leaseOwner,
            LocalDateTime now,
            LocalDateTime leaseUntil
    );

    // 必须在调用者的事务中使用，行锁保持到结果提交或回滚。
    Optional<IngestionJob> findOwnedForUpdate(Long jobId, String leaseOwner);

    void save(IngestionJob job);

    boolean renew(Long jobId, String leaseOwner, Duration leaseDuration);

    void cancelByDocumentId(Long documentId);
}
