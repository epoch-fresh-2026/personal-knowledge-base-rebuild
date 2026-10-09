package com.ithwx.personalknowledgebase.library.application;

import com.ithwx.personalknowledgebase.index.application.IndexDocument;
import com.ithwx.personalknowledgebase.index.domain.PreparedIndex;
import com.ithwx.personalknowledgebase.library.domain.Document;
import com.ithwx.personalknowledgebase.library.domain.DocumentRepository;
import com.ithwx.personalknowledgebase.library.domain.IngestionJob;
import com.ithwx.personalknowledgebase.library.domain.IngestionJobRepository;
import com.ithwx.personalknowledgebase.library.domain.IngestionJobStatus;
import com.ithwx.personalknowledgebase.library.domain.IngestionStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngestionCommitterTest {
    @Mock private DocumentRepository documents;
    @Mock private IngestionJobRepository jobs;
    @Mock private IndexDocument index;
    private IngestionCommitter committer;
    private Document document;
    private IngestionJob job;

    @BeforeEach
    void setUp() {
        committer = new IngestionCommitter(documents, jobs, index, Duration.ofMinutes(5));
        document = new Document();
        document.setId(1L);
        job = IngestionJob.pending(1L);
        job.setId(10L);
        LocalDateTime now = LocalDateTime.now();
        job.claim("worker", now.plusMinutes(5), now);
    }

    @Test
    void shouldPersistBodyAndIndexingCheckpointTogether() {
        stubOwned();
        when(documents.save(document)).thenReturn(document);
        Document result = committer.extracted(10L, "worker", new DocumentExtractor.Result("笔记", "正文", null)).orElseThrow();
        assertEquals("正文", result.getContent());
        assertNotNull(result.getContentHash());
        assertEquals(IngestionStage.INDEXING, job.getStage());
        verify(jobs).save(job);
        verifyNoInteractions(index);
    }

    @Test
    void shouldRejectDuplicateBeforePersistingBody() {
        stubOwned();
        when(documents.existsOtherWithHash(anyString(), eq(1L))).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> committer.extracted(10L, "worker", new DocumentExtractor.Result("笔记", "正文", null)));
        verify(documents, never()).save(any());
        verify(jobs, never()).save(any());
    }

    @Test
    void shouldCommitIndexBeforeSavingReadyAndCompletedStates() {
        stubOwned();
        job.setStage(IngestionStage.INDEXING);
        PreparedIndex prepared = new PreparedIndex(1L, List.of());
        assertTrue(committer.indexed(10L, "worker", prepared));
        var order = inOrder(index, documents, jobs);
        order.verify(jobs).findOwnedForUpdate(10L, "worker");
        order.verify(documents).findById(1L);
        order.verify(index).commit(prepared);
        order.verify(documents).save(document);
        order.verify(jobs).save(job);
        assertEquals("READY", document.getStatus());
        assertEquals(IngestionJobStatus.COMPLETED, job.getStatus());
    }

    @Test
    void shouldNeverWriteForMissingOrExpiredLease() {
        assertFalse(committer.indexed(10L, "old", new PreparedIndex(1L, List.of())));
        assertTrue(committer.extracted(10L, "old", new DocumentExtractor.Result("旧", "正文", null)).isEmpty());
        committer.fail(10L, "old", "旧错误");
        verifyNoInteractions(documents, index);
        verify(jobs, never()).save(any());
    }

    @Test
    void shouldNotPersistCompletionWhenIndexWriteThrows() {
        stubOwned();
        job.setStage(IngestionStage.INDEXING);
        PreparedIndex prepared = new PreparedIndex(1L, List.of());
        doThrow(new IllegalStateException("写入失败")).when(index).commit(prepared);
        assertThrows(IllegalStateException.class, () -> committer.indexed(10L, "worker", prepared));
        verify(documents, never()).save(any());
        verify(jobs, never()).save(any());
    }

    private void stubOwned() {
        when(jobs.findOwnedForUpdate(10L, "worker")).thenReturn(Optional.of(job));
        when(documents.findById(1L)).thenReturn(Optional.of(document));
    }
}
