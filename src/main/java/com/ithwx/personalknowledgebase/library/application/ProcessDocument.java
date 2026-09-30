package com.ithwx.personalknowledgebase.library.application;

import com.ithwx.personalknowledgebase.index.application.IndexDocument;
import com.ithwx.personalknowledgebase.library.domain.ContentHash;
import com.ithwx.personalknowledgebase.library.domain.Document;
import com.ithwx.personalknowledgebase.library.domain.DocumentRepository;
import com.ithwx.personalknowledgebase.library.domain.DocumentStatus;
import com.ithwx.personalknowledgebase.library.domain.DocumentTextReady;
import com.ithwx.personalknowledgebase.library.domain.IngestionJob;
import com.ithwx.personalknowledgebase.library.domain.IngestionJobRepository;
import com.ithwx.personalknowledgebase.library.domain.IngestionStage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Component
class ProcessDocument {

    private static final int MAX_FAILURE_REASON_LENGTH = 1000;

    private final DocumentRepository documentRepository;
    private final IngestionJobRepository jobRepository;
    private final DocumentExtractor extractor;
    private final IndexDocument indexDocument;
    private final TaskExecutor taskExecutor;
    private final Duration leaseDuration;
    private final String instanceId = UUID.randomUUID().toString();

    ProcessDocument(
            DocumentRepository documentRepository,
            IngestionJobRepository jobRepository,
            DocumentExtractor extractor,
            IndexDocument indexDocument,
            @Qualifier("ingestionTaskExecutor") TaskExecutor taskExecutor,
            @Value("${app.ingestion.lease-duration:15m}") Duration leaseDuration
    ) {
        this.documentRepository = documentRepository;
        this.jobRepository = jobRepository;
        this.extractor = extractor;
        this.indexDocument = indexDocument;
        this.taskExecutor = taskExecutor;
        this.leaseDuration = leaseDuration;
    }

    public void processAsync(Long documentId) {
        jobRepository.restart(documentId);
        triggerWorker();
    }

    void processNext() {
        String leaseOwner = instanceId + ":" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jobRepository.claimNext(leaseOwner, now, now.plus(leaseDuration))
                .ifPresent(job -> process(job, leaseOwner));
    }

    void process(IngestionJob job, String leaseOwner) {
        try {
            if (job.getStage() == IngestionStage.EXTRACTING) {
                Document extracted = extract(job.getDocumentId());
                if (!advance(job, leaseOwner, IngestionStage.INDEXING)) {
                    return;
                }
                index(job, leaseOwner, extracted);
                return;
            }
            if (job.getStage() == IngestionStage.INDEXING) {
                index(job, leaseOwner, requiredDocument(job.getDocumentId()));
            }
        } catch (Exception exception) {
            fail(job, leaseOwner, messageOf(exception));
        }
    }

    Document retry(Long id) {
        Document document = requiredDocument(id);
        if (!document.canRetry()) {
            throw new IllegalArgumentException("只有处理失败的资料可以重试");
        }
        document.prepareForProcessing();
        Document saved = documentRepository.save(document);
        processAsync(saved.getId());
        return saved;
    }

    void cancel(Long documentId) {
        jobRepository.cancelByDocumentId(documentId);
    }

    @EventListener(ApplicationReadyEvent.class)
    void resumeUnfinishedDocuments() {
        List<String> statuses = List.of(
                DocumentStatus.PENDING.name(),
                DocumentStatus.PROCESSING.name()
        );
        for (Document document : documentRepository.findByStatuses(statuses)) {
            jobRepository.ensureExists(document.getId());
        }
        triggerWorker();
    }

    @Scheduled(fixedDelayString = "${app.ingestion.poll-interval:1s}")
    void pollForWork() {
        processNext();
    }

    private Document extract(Long documentId) throws Exception {
        Document document = requiredDocument(documentId);
        document.startProcessing();
        document = documentRepository.save(document);

        DocumentExtractor.Result extracted = extractor.extract(document);
        String hash = ContentHash.of(extracted.content()).value();
        if (documentRepository.existsOtherWithHash(hash, documentId)) {
            throw new IllegalArgumentException("相同内容的资料已存在");
        }
        document.setName(extracted.name());
        document.setSourceUrl(extracted.sourceUrl());
        document.setContent(extracted.content());
        document.setContentHash(hash);
        return documentRepository.save(document);
    }

    private void index(
            IngestionJob job,
            String leaseOwner,
            Document document
    ) {
        int chunkCount = indexDocument.index(toTextReady(document));
        if (!advance(job, leaseOwner, IngestionStage.INDEXING)) {
            return;
        }
        document.markReady(chunkCount);
        documentRepository.save(document);
        jobRepository.complete(job.getId(), leaseOwner);
    }

    private boolean advance(
            IngestionJob job,
            String leaseOwner,
            IngestionStage stage
    ) {
        LocalDateTime nextLeaseUntil = LocalDateTime.now().plus(leaseDuration);
        boolean advanced = jobRepository.advance(
                job.getId(), leaseOwner, stage, nextLeaseUntil);
        if (advanced) {
            job.setStage(stage);
            job.setLeaseUntil(nextLeaseUntil);
        }
        return advanced;
    }

    private DocumentTextReady toTextReady(Document document) {
        if (document.getContent() == null || document.getContent().isBlank()) {
            throw new IllegalStateException("资料正文尚未完成解析");
        }
        return new DocumentTextReady(
                document.getId(),
                document.getName(),
                document.getFileType(),
                document.getSourceUrl(),
                document.getContent()
        );
    }

    private void triggerWorker() {
        try {
            taskExecutor.execute(this::processNext);
        } catch (TaskRejectedException ignored) {
            // 任务已经落库，线程池繁忙时由定时轮询继续处理。
        }
    }

    private Document requiredDocument(Long id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("资料不存在：" + id));
    }

    private void fail(IngestionJob job, String leaseOwner, String reason) {
        String shortReason = shortReason(reason);
        if (!jobRepository.fail(job.getId(), leaseOwner, shortReason)) {
            return;
        }
        documentRepository.findById(job.getDocumentId()).ifPresent(document -> {
            document.setContentHash(null);
            document.markFailed(shortReason);
            documentRepository.save(document);
        });
    }

    private String messageOf(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    private String shortReason(String reason) {
        String value = reason == null || reason.isBlank() ? "处理失败" : reason;
        return value.length() <= MAX_FAILURE_REASON_LENGTH
                ? value : value.substring(0, MAX_FAILURE_REASON_LENGTH);
    }
}
