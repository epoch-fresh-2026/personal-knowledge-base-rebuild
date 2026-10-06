package com.ithwx.personalknowledgebase.library.domain;

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

    boolean advance(
            Long jobId,
            String leaseOwner,
            IngestionStage stage,
            LocalDateTime leaseUntil
    );

    boolean complete(Long jobId, String leaseOwner);

    boolean fail(Long jobId, String leaseOwner, String reason);

    void cancelByDocumentId(Long documentId);
}
