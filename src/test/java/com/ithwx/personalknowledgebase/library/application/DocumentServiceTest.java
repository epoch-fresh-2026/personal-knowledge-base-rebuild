package com.ithwx.personalknowledgebase.library.application;

import com.ithwx.personalknowledgebase.library.domain.Document;
import com.ithwx.personalknowledgebase.library.domain.DocumentDeleted;
import com.ithwx.personalknowledgebase.library.domain.DocumentRepository;
import com.ithwx.personalknowledgebase.library.domain.RelatedDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentRepository repository;
    @Mock
    private FileStorage fileStorage;
    @Mock
    private ProcessDocument processDocument;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private DocumentService service;
    private Document existingDocument;

    @BeforeEach
    void setUp() {
        service = new DocumentService(
                repository, fileStorage, processDocument, eventPublisher);
        existingDocument = new Document();
        existingDocument.setId(1L);
    }

    @Test
    void shouldLookUpAtMostFiveRelatedDocumentsForCurrentConcepts() {
        existingDocument.setStatus("READY");
        existingDocument.completeSummary("摘要");
        existingDocument.setConcepts(Set.of("行锁"));
        when(repository.findById(1L)).thenReturn(Optional.of(existingDocument));
        var related = List.of(new RelatedDocument(2L, 1L, "并发控制", List.of("行锁")));
        when(repository.findRelated(1L, Set.of("行锁"), 5)).thenReturn(related);
        assertEquals(related, service.related(1L));
    }

    @Test
    void shouldNotUseUnfinishedOrOldConceptsAndShouldReportDeletedDocument() {
        when(repository.findById(1L)).thenReturn(Optional.of(existingDocument));
        existingDocument.setConcepts(Set.of("旧概念"));
        existingDocument.setStatus("PROCESSING");
        assertEquals(List.of(), service.related(1L));
        existingDocument.setStatus("READY");
        existingDocument.setSummaryStatus("PROCESSING");
        assertEquals(List.of(), service.related(1L));
        existingDocument.completeSummary("旧资料只有摘要");
        existingDocument.setConcepts(Set.of());
        assertEquals(List.of(), service.related(1L));
        verify(repository, never()).findRelated(anyLong(), any(), anyInt());
        assertThrows(NoSuchElementException.class, () -> service.related(2L));
    }

    @Test
    void shouldReplaceWithPowerPointAndQueueExtraction() throws Exception {
        stubSavingWithId();
        when(repository.findById(1L)).thenReturn(Optional.of(existingDocument));
        byte[] content = "PPT 文件内容".getBytes();
        when(fileStorage.save("课件.PPTX", content)).thenReturn("lecture.pptx");
        Document replaced = service.replaceFile(1L, "课件.PPTX", content);
        assertEquals("pptx", replaced.getFileType());
        assertEquals("PENDING", replaced.getStatus());
        verify(processDocument).processAsync(1L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"doc", "docx", "ppt", "pptx", "xls", "xlsx", "pdf", "txt",
            "md", "markdown", "csv", "tsv", "html", "htm", "rtf", "odt", "ods", "odp", "epub"})
    void shouldAcceptAllDeclaredFormatsAtUploadBoundary(String type) throws Exception {
        stubSavingWithId();
        String filename = "课程." + type;
        byte[] content = "文件内容".getBytes();
        when(fileStorage.save(filename, content)).thenReturn(filename);
        assertEquals(type, service.submitFile(filename, content).getFileType());
        verify(processDocument).processAsync(1L);
    }

    @Test
    void shouldSaveFileAsPendingBeforeProcessing() throws Exception {
        stubSavingWithId();
        byte[] content = "正文".getBytes();
        when(fileStorage.save("笔记.TXT", content)).thenReturn("saved.txt");

        Document result = service.submitFile("笔记.TXT", content);

        assertEquals("PENDING", result.getStatus());
        assertEquals("txt", result.getFileType());
        assertEquals("saved.txt", result.getFilePath());
        verify(processDocument).processAsync(1L);
    }

    @Test
    void shouldSaveNoteAndWebAddressWithoutWaitingForExtraction() {
        stubSavingWithId();
        Document note = service.createNote("学习笔记", "正文");
        Document web = service.collectWebPage("https://example.com", null);

        assertEquals("正文", note.getContent());
        assertEquals("PENDING", note.getStatus());
        assertEquals("https://example.com", web.getSourceUrl());
        assertEquals("PENDING", web.getStatus());
    }

    @Test
    void shouldListAndGetDocuments() {
        when(repository.listNewestFirst()).thenReturn(List.of(existingDocument));
        when(repository.findById(1L)).thenReturn(Optional.of(existingDocument));

        assertSame(existingDocument, service.list().get(0));
        assertSame(existingDocument, service.get(1L));
    }

    @Test
    void shouldUpdateContentAndStartProcessing() {
        stubSavingWithId();
        when(repository.findById(1L)).thenReturn(Optional.of(existingDocument));

        existingDocument.completeSummary("旧摘要");
        service.update(1L, "新名称", "新正文");

        assertEquals("PENDING", existingDocument.getStatus());
        assertEquals("新名称", existingDocument.getName());
        assertEquals("新正文", existingDocument.getContent());
        assertNull(existingDocument.getSummary());
        assertEquals("PENDING", existingDocument.getSummaryStatus());
        verify(processDocument).processAsync(1L);
    }

    @Test
    void shouldReplaceFileAndResetPreviousSource() throws Exception {
        stubSavingWithId();
        byte[] content = "替换正文".getBytes();
        existingDocument.setSourceUrl("https://example.com/old");
        existingDocument.setContent("旧正文");
        existingDocument.completeSummary("旧摘要");
        when(repository.findById(1L)).thenReturn(Optional.of(existingDocument));
        when(fileStorage.save("新版.PDF", content)).thenReturn("new.pdf");

        service.replaceFile(1L, "新版.PDF", content);

        assertEquals("pdf", existingDocument.getFileType());
        assertEquals("new.pdf", existingDocument.getFilePath());
        assertEquals("PENDING", existingDocument.getStatus());
        assertNull(existingDocument.getSourceUrl());
        assertNull(existingDocument.getContent());
        assertNull(existingDocument.getSummary());
        verify(processDocument).processAsync(1L);
    }

    @Test
    void shouldDeleteBeforePublishingEvent() {
        when(repository.findById(1L)).thenReturn(Optional.of(existingDocument));

        service.delete(1L);

        InOrder order = inOrder(processDocument, repository, eventPublisher);
        order.verify(processDocument).cancel(1L);
        order.verify(repository).delete(existingDocument);
        order.verify(eventPublisher).publishEvent(new DocumentDeleted(1L));
    }

    @Test
    void shouldDelegateRetryToAsyncProcessor() {
        when(processDocument.retry(1L)).thenReturn(existingDocument);

        assertSame(existingDocument, service.retry(1L));
    }

    private void stubSavingWithId() {
        when(repository.save(any(Document.class))).thenAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            if (document.getId() == null) {
                document.setId(1L);
            }
            return document;
        });
    }
}
