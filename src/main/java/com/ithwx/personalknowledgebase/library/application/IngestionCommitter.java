package com.ithwx.personalknowledgebase.library.application;

import com.ithwx.personalknowledgebase.index.application.IndexDocument;
import com.ithwx.personalknowledgebase.index.domain.PreparedIndex;
import com.ithwx.personalknowledgebase.library.domain.ContentHash;
import com.ithwx.personalknowledgebase.library.domain.Document;
import com.ithwx.personalknowledgebase.library.domain.DocumentRepository;
import com.ithwx.personalknowledgebase.library.domain.IngestionJob;
import com.ithwx.personalknowledgebase.library.domain.IngestionJobRepository;
import com.ithwx.personalknowledgebase.library.domain.IngestionStage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/** 所有 Worker 写入都先锁任务行并验证租约；此处不得执行解析或模型调用。 */
@Component
public class IngestionCommitter {
    private final DocumentRepository documents;
    private final IngestionJobRepository jobs;
    private final IndexDocument indexDocument;
    private final Duration leaseDuration;

    public IngestionCommitter(DocumentRepository documents, IngestionJobRepository jobs,
                              IndexDocument indexDocument,
                              @Value("${app.ingestion.lease-duration:15m}") Duration leaseDuration) {
        this.documents = documents;
        this.jobs = jobs;
        this.indexDocument = indexDocument;
        this.leaseDuration = leaseDuration;
    }

    @Transactional
    public Optional<Document> start(Long jobId, String owner) {
        Optional<IngestionJob> owned = jobs.findOwnedForUpdate(jobId, owner);
        if (owned.isEmpty()) {
            return Optional.empty();
        }
        Optional<Document> document = documents.findById(owned.get().getDocumentId());
        if (document.isEmpty()) {
            owned.get().cancel();
            jobs.save(owned.get());
            return Optional.empty();
        }
        if (owned.get().getStage() == IngestionStage.SUMMARIZING) {
            document.get().startSummary();
        } else {
            document.get().startProcessing();
        }
        return Optional.of(documents.save(document.get()));
    }

    @Transactional
    public Optional<Document> extracted(Long jobId, String owner, DocumentExtractor.Result result) {
        Optional<IngestionJob> owned = jobs.findOwnedForUpdate(jobId, owner);
        if (owned.isEmpty() || owned.get().getStage() != IngestionStage.EXTRACTING) {
            return Optional.empty();
        }
        IngestionJob job = owned.get();
        LocalDateTime now = LocalDateTime.now();
        if (!job.advance(owner, IngestionStage.INDEXING, now.plus(leaseDuration), now)) {
            return Optional.empty();
        }
        Document document = documents.findById(job.getDocumentId()).orElseThrow();
        if (result.content() == null || result.content().isBlank()) {
            throw new IllegalArgumentException("资料正文不能为空");
        }
        String hash = ContentHash.of(result.content()).value();
        if (documents.existsOtherWithHash(hash, document.getId())) {
            throw new IllegalArgumentException("相同内容的资料已存在");
        }
        document.setName(result.name());
        document.setSourceUrl(result.sourceUrl());
        document.setContent(result.content());
        document.setContentHash(hash);
        Document saved = documents.save(document);
        jobs.save(job);
        return Optional.of(saved);
    }

    @Transactional
    public boolean indexed(Long jobId, String owner, PreparedIndex prepared) {
        //找到任务 → 锁住这条任务记录 → 检查提交者是否仍有资格。
        Optional<IngestionJob> owned = jobs.findOwnedForUpdate(jobId, owner);
        if (owned.isEmpty() || owned.get().getStage() != IngestionStage.INDEXING) {
            return false;
        }
        IngestionJob job = owned.get();
        if (!job.getDocumentId().equals(prepared.documentId())) {
            throw new IllegalArgumentException("索引结果与任务资料不一致");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!job.advance(owner, IngestionStage.SUMMARIZING, now.plus(leaseDuration), now)) {
            return false;
        }
        Document document = documents.findById(job.getDocumentId()).orElseThrow();
        indexDocument.commit(prepared);
        document.markReady(prepared.chunks().size());
        document.startSummary();
        documents.save(document);
        jobs.save(job);
        return true;
    }

    @Transactional
    public boolean summarized(Long jobId, String owner, SummarizeDocument.Result result) {
        Optional<IngestionJob> owned = jobs.findOwnedForUpdate(jobId, owner);
        if (owned.isEmpty() || owned.get().getStage() != IngestionStage.SUMMARIZING) {
            return false;
        }
        IngestionJob job = owned.get();
        if (!job.complete(owner, LocalDateTime.now())) {
            return false;
        }
        // 和索引一样，先验证任务身份，再写入；删改后的旧结果不会写回。
        Document document = documents.findById(job.getDocumentId()).orElseThrow();
        document.completeSummary(result.summary());
        document.setConcepts(result.concepts());
        documents.save(document);
        jobs.save(job);
        return true;
    }

    @Transactional
    public void fail(Long jobId, String owner, String reason) {
        Optional<IngestionJob> owned = jobs.findOwnedForUpdate(jobId, owner);
        if (owned.isEmpty() || !owned.get().fail(owner, reason, LocalDateTime.now())) {
            return;
        }
        documents.findById(owned.get().getDocumentId()).ifPresent(document -> {
            if (owned.get().getStage() == IngestionStage.SUMMARIZING) {
                document.failSummary(reason);
            } else {
                document.setContentHash(null);
                document.markFailed(reason);
            }
            documents.save(document);
        });
        jobs.save(owned.get());
    }
}
