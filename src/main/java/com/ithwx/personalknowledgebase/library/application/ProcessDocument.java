package com.ithwx.personalknowledgebase.library.application;

import com.ithwx.personalknowledgebase.index.application.IndexDocument;
import com.ithwx.personalknowledgebase.index.domain.PreparedIndex;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final IngestionCommitter committer;
    private final IngestionHeartbeat heartbeat;
    private final String instanceId = UUID.randomUUID().toString();

    ProcessDocument(
            DocumentRepository documentRepository,
            IngestionJobRepository jobRepository,
            DocumentExtractor extractor,
            IndexDocument indexDocument,
            @Qualifier("ingestionTaskExecutor") TaskExecutor taskExecutor,
            @Value("${app.ingestion.lease-duration:15m}") Duration leaseDuration,
            IngestionCommitter committer,
            IngestionHeartbeat heartbeat
    ) {
        this.documentRepository = documentRepository;
        this.jobRepository = jobRepository;
        this.extractor = extractor;
        this.indexDocument = indexDocument;
        this.taskExecutor = taskExecutor;
        this.leaseDuration = leaseDuration;
        this.committer = committer;
        this.heartbeat = heartbeat;
    }

    public void processAsync(Long documentId) {
        jobRepository.restart(documentId);//在数据库中创建或重置这份文档的处理任务。
        triggerWorker();//通知线程池安排后台执行。
    }

    void processNext() {
        String leaseOwner = instanceId + ":" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jobRepository.claimNext(leaseOwner, now, now.plus(leaseDuration))//查找可执行任务，通过数据库行锁抢占任务，
                                                                 // 分配leaseOwner租赁持有者、设置租赁到期时间，更新任务记录
                .ifPresent(job -> process(job, leaseOwner));
    }

    void process(IngestionJob job, String leaseOwner) {
        try (IngestionHeartbeat.Lease lease = heartbeat.watch(job.getId(), leaseOwner)) {
                                 //取出任务对应的文档，标记为处理中
            Document document = committer.start(job.getId(), leaseOwner).orElse(null);
            if (document == null || !lease.isValid()) {
                return;
            }
            if (job.getStage() == IngestionStage.EXTRACTING) {
                                                 //得到正文；文字笔记直接使用你输入的文字
                DocumentExtractor.Result result = extractor.extract(document);
                if (!lease.isValid()) {
                    return;
                }
                           //保存正文，记录“接下来建立索引”
                document = committer.extracted(job.getId(), leaseOwner, result).orElse(null);
                if (document == null) {
                    return;
                }
            }
            if (lease.isValid()) {
                                          //切分正文、计算向量，得到准备好的索引数据
                PreparedIndex prepared = indexDocument.prepare(toTextReady(document));
                if (lease.isValid()) {
                    //保存索引，标记文档可用、任务完成
                    committer.indexed(job.getId(), leaseOwner, prepared);
                }
            }
        } catch (Exception exception) {
            fail(job, leaseOwner, messageOf(exception));
        }
    }

    Document retry(Long id) {
        jobRepository.restart(id);
        Document document = requiredDocument(id);
        if (!document.canRetry()) {
            throw new IllegalArgumentException("只有处理失败的资料可以重试");
        }
        document.prepareForProcessing();
        Document saved = documentRepository.save(document);
        triggerWorker();
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
        triggerWorker();
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
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submitWorker();
                }
            });
        } else {
            submitWorker();
        }
    }

    private void submitWorker() {
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
        committer.fail(job.getId(), leaseOwner, shortReason(reason));
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
