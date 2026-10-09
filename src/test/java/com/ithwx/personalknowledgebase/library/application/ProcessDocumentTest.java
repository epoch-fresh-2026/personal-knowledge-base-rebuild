package com.ithwx.personalknowledgebase.library.application;

import com.ithwx.personalknowledgebase.index.application.IndexDocument;
import com.ithwx.personalknowledgebase.index.domain.PreparedIndex;
import com.ithwx.personalknowledgebase.library.domain.Document;
import com.ithwx.personalknowledgebase.library.domain.DocumentRepository;
import com.ithwx.personalknowledgebase.library.domain.IngestionJob;
import com.ithwx.personalknowledgebase.library.domain.IngestionJobRepository;
import com.ithwx.personalknowledgebase.library.domain.IngestionStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProcessDocumentTest {
    @Mock private DocumentRepository repository;
    @Mock private IngestionJobRepository jobs;
    @Mock private DocumentExtractor extractor;
    @Mock private IndexDocument indexDocument;
    @Mock private TaskExecutor executor;
    @Mock private IngestionCommitter committer;
    @Mock private IngestionHeartbeat heartbeat;
    @Mock private IngestionHeartbeat.Lease lease;
    private ProcessDocument worker;
    private Document document;
    private IngestionJob job;
    private final String owner = "worker:attempt";

    @BeforeEach
    void setUp() {
        worker = new ProcessDocument(repository, jobs, extractor, indexDocument, executor,
                Duration.ofMinutes(15), committer, heartbeat);
        document = new Document();
        document.setId(1L);
        document.setName("笔记.txt");
        document.setFileType("txt");
        document.setContent("正文");
        job = IngestionJob.pending(1L);
        job.setId(10L);
        LocalDateTime now = LocalDateTime.now();
        job.claim(owner, now.plusMinutes(15), now);
    }

    @Test
    void shouldPrepareOutsideCommitAndCloseHeartbeat() throws Exception {
        stubLease();
        when(lease.isValid()).thenReturn(true);
        DocumentExtractor.Result result = new DocumentExtractor.Result("笔记.txt", "正文", null);
        when(extractor.extract(document)).thenReturn(result);
        when(committer.extracted(10L, owner, result)).thenReturn(Optional.of(document));
        PreparedIndex prepared = new PreparedIndex(1L, List.of());
        when(indexDocument.prepare(any())).thenReturn(prepared);
        worker.process(job, owner);
        var order = inOrder(committer, extractor, indexDocument);
        order.verify(committer).start(10L, owner);
        order.verify(extractor).extract(document);
        order.verify(committer).extracted(10L, owner, result);
        order.verify(indexDocument).prepare(any());
        order.verify(committer).indexed(10L, owner, prepared);
        verify(lease).close();
    }

    @Test
    void shouldResumeIndexingWithoutExtractingAgain() {
        stubLease();
        when(lease.isValid()).thenReturn(true);
        job.setStage(IngestionStage.INDEXING);
        PreparedIndex prepared = new PreparedIndex(1L, List.of());
        when(indexDocument.prepare(any())).thenReturn(prepared);
        worker.process(job, owner);
        verifyNoInteractions(extractor);
        verify(committer, never()).extracted(anyLong(), anyString(), any());
        verify(committer).indexed(10L, owner, prepared);
    }

    @Test
    void shouldDiscardExtractionAfterHeartbeatFailure() throws Exception {
        stubLease();
        when(lease.isValid()).thenReturn(true, false);
        when(extractor.extract(document)).thenReturn(new DocumentExtractor.Result("笔记", "迟到正文", null));
        worker.process(job, owner);
        verify(committer, never()).extracted(anyLong(), anyString(), any());
        verifyNoInteractions(indexDocument);
        verify(lease).close();
    }

    @Test
    void shouldDiscardPreparedVectorsAfterHeartbeatFailure() {
        stubLease();
        job.setStage(IngestionStage.INDEXING);
        when(lease.isValid()).thenReturn(true, true, false);
        when(indexDocument.prepare(any())).thenReturn(new PreparedIndex(1L, List.of()));
        worker.process(job, owner);
        verify(indexDocument).prepare(any());
        verify(committer, never()).indexed(anyLong(), anyString(), any());
    }

    @Test
    void shouldPersistFailureThroughProtectedCommitter() throws Exception {
        stubLease();
        when(lease.isValid()).thenReturn(true);
        when(extractor.extract(document)).thenThrow(new IllegalStateException("解析失败"));
        worker.process(job, owner);
        verify(committer).fail(10L, owner, "解析失败");
        verify(lease).close();
    }

    @Test
    void shouldPollOnlyThroughBoundedExecutor() {
        worker.pollForWork();
        verify(executor).execute(any(Runnable.class));
        verifyNoInteractions(jobs, extractor, indexDocument);
    }

    @Test
    void shouldKeepDurableJobWhenExecutorIsFull() {
        doThrow(new TaskRejectedException("busy")).when(executor).execute(any(Runnable.class));
        worker.processAsync(1L);
        verify(jobs).restart(1L);
        worker.pollForWork();
        verify(executor, times(2)).execute(any(Runnable.class));
    }

    @Test
    void shouldDispatchOnlyAfterTransactionCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            worker.processAsync(1L);
            verifyNoInteractions(executor);
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
            verify(executor).execute(any(Runnable.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void shouldRetryFailedDocumentAfterInvalidatingOldAttempt() {
        document.setStatus("FAILED");
        when(repository.findById(1L)).thenReturn(Optional.of(document));
        when(repository.save(document)).thenReturn(document);
        worker.retry(1L);
        var order = inOrder(jobs, repository);
        order.verify(jobs).restart(1L);
        order.verify(repository).findById(1L);
        order.verify(repository).save(document);
        verify(executor).execute(any(Runnable.class));
    }

    @Test
    void shouldBackfillUnfinishedDocumentsAfterRestart() {
        when(repository.findByStatuses(List.of("PENDING", "PROCESSING"))).thenReturn(List.of(document));
        worker.resumeUnfinishedDocuments();
        verify(jobs).ensureExists(1L);
        verify(executor).execute(any(Runnable.class));
    }

    private void stubLease() {
        when(heartbeat.watch(10L, owner)).thenReturn(lease);
        when(committer.start(10L, owner)).thenReturn(Optional.of(document));
    }
}
