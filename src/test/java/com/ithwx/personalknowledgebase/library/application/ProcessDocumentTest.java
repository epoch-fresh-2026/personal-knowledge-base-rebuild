package com.ithwx.personalknowledgebase.library.application;

import com.ithwx.personalknowledgebase.index.application.IndexDocument;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessDocumentTest {

    @Mock
    private DocumentRepository repository;
    @Mock
    private IngestionJobRepository jobRepository;
    @Mock
    private DocumentExtractor extractor;
    @Mock
    private IndexDocument indexDocument;
    @Mock
    private TaskExecutor taskExecutor;

    private ProcessDocument processDocument;
    private Document document;
    private IngestionJob job;
    private final String leaseOwner = "worker:attempt";

    @BeforeEach
    void setUp() {
        processDocument = new ProcessDocument(
                repository, jobRepository, extractor, indexDocument,
                taskExecutor, Duration.ofMinutes(15));
        document = new Document();
        document.setId(1L);
        document.setName("笔记.txt");
        document.setFileType("txt");
        document.setStatus("PENDING");
        job = IngestionJob.pending(1L);
        job.setId(10L);
        job.claim(leaseOwner, LocalDateTime.now().plusMinutes(15), LocalDateTime.now());
    }

    @Test
    void shouldPersistExtractionCheckpointAndCompleteIndexing() throws Exception {
        stubDocument();
        when(extractor.extract(document))
                .thenReturn(new DocumentExtractor.Result("笔记.txt", "正文", null));
        when(jobRepository.advance(
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq(leaseOwner),
                any(IngestionStage.class),
                any(LocalDateTime.class)))
                .thenReturn(true);
        when(indexDocument.index(any())).thenReturn(2);

        processDocument.process(job, leaseOwner);

        assertEquals("READY", document.getStatus());
        assertEquals("正文", document.getContent());
        assertEquals(2, document.getChunkCount());
        verify(jobRepository, org.mockito.Mockito.times(2)).advance(
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq(leaseOwner),
                org.mockito.ArgumentMatchers.eq(IngestionStage.INDEXING),
                any(LocalDateTime.class));
        verify(indexDocument).index(any());
        verify(jobRepository).complete(10L, leaseOwner);
    }

    @Test
    void shouldPersistFailureWhenContentIsDuplicate() throws Exception {
        stubDocument();
        when(extractor.extract(document))
                .thenReturn(new DocumentExtractor.Result("笔记.txt", "重复正文", null));
        when(repository.existsOtherWithHash(anyString(), org.mockito.ArgumentMatchers.eq(1L)))
                .thenReturn(true);
        when(jobRepository.fail(10L, leaseOwner, "相同内容的资料已存在"))
                .thenReturn(true);

        processDocument.process(job, leaseOwner);

        assertEquals("FAILED", document.getStatus());
        assertEquals("相同内容的资料已存在", document.getFailureReason());
        verify(jobRepository).fail(10L, leaseOwner, "相同内容的资料已存在");
    }

    @Test
    void shouldResumeDirectlyFromPersistedIndexingStage() {
        stubDocument();
        document.setContent("已经解析的正文");
        job.setStage(IngestionStage.INDEXING);
        when(indexDocument.index(any())).thenReturn(3);
        when(jobRepository.advance(
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq(leaseOwner),
                org.mockito.ArgumentMatchers.eq(IngestionStage.INDEXING),
                any(LocalDateTime.class)))
                .thenReturn(true);

        processDocument.process(job, leaseOwner);

        assertEquals("READY", document.getStatus());
        assertEquals(3, document.getChunkCount());
        verify(indexDocument).index(any());
        verify(jobRepository).complete(10L, leaseOwner);
    }

    @Test
    void shouldRetryFailedDocumentThroughDurableJob() {
        stubDocument();
        document.setStatus("FAILED");
        processDocument.retry(1L);

        verify(repository).save(document);
        verify(jobRepository).restart(1L);
        verify(taskExecutor).execute(any(Runnable.class));
    }

    @Test
    void shouldResumeUnfinishedDocumentsAfterRestart() {
        Document another = new Document();
        another.setId(2L);
        when(repository.findByStatuses(List.of("PENDING", "PROCESSING")))
                .thenReturn(List.of(document, another));

        processDocument.resumeUnfinishedDocuments();

        verify(repository).findByStatuses(List.of("PENDING", "PROCESSING"));
        verify(jobRepository).ensureExists(1L);
        verify(jobRepository).ensureExists(2L);
        verify(taskExecutor).execute(any(Runnable.class));
    }

    private void stubDocument() {
        when(repository.findById(1L)).thenReturn(Optional.of(document));
        when(repository.save(any(Document.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }
}
