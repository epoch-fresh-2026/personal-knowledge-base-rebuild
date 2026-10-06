package com.ithwx.personalknowledgebase.library.application;

import com.ithwx.personalknowledgebase.library.domain.Document;
import com.ithwx.personalknowledgebase.library.domain.DocumentDeleted;
import com.ithwx.personalknowledgebase.library.domain.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

        service.update(1L, "新名称", "新正文");

        assertEquals("PENDING", existingDocument.getStatus());
        assertEquals("新名称", existingDocument.getName());
        assertEquals("新正文", existingDocument.getContent());
        verify(processDocument).processAsync(1L);
    }

    @Test
    void shouldReplaceFileAndResetPreviousSource() throws Exception {
        stubSavingWithId();
        byte[] content = "替换正文".getBytes();
        existingDocument.setSourceUrl("https://example.com/old");
        existingDocument.setContent("旧正文");
        when(repository.findById(1L)).thenReturn(Optional.of(existingDocument));
        when(fileStorage.save("新版.PDF", content)).thenReturn("new.pdf");

        service.replaceFile(1L, "新版.PDF", content);

        assertEquals("pdf", existingDocument.getFileType());
        assertEquals("new.pdf", existingDocument.getFilePath());
        assertEquals("PENDING", existingDocument.getStatus());
        assertNull(existingDocument.getSourceUrl());
        assertNull(existingDocument.getContent());
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
