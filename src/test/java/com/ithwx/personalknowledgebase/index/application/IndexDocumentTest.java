package com.ithwx.personalknowledgebase.index.application;

import com.ithwx.personalknowledgebase.index.domain.KnowledgeChunk;
import com.ithwx.personalknowledgebase.index.domain.KnowledgeIndex;
import com.ithwx.personalknowledgebase.index.domain.PreparedIndex;
import com.ithwx.personalknowledgebase.index.infrastructure.TextChunker;
import com.ithwx.personalknowledgebase.library.domain.DocumentDeleted;
import com.ithwx.personalknowledgebase.library.domain.DocumentTextReady;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IndexDocumentTest {
    @Mock private TextChunker chunker;
    @Mock private KnowledgeIndex index;
    private IndexDocument service;

    @BeforeEach
    void setUp() {
        service = new IndexDocument(chunker, index);
    }

    @Test
    void shouldPrepareChunksWithoutPublishingIndex() {
        DocumentTextReady document = new DocumentTextReady(1L, "笔记", "note", null, "正文");
        when(chunker.split("正文")).thenReturn(List.of("第一段", "第二段"));
        PreparedIndex prepared = new PreparedIndex(1L, List.of());
        when(index.prepare(eq(1L), anyList())).thenReturn(prepared);
        assertSame(prepared, service.prepare(document));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeChunk>> chunks = ArgumentCaptor.forClass(List.class);
        verify(index).prepare(eq(1L), chunks.capture());
        assertEquals("第一段", chunks.getValue().get(0).text());
        assertEquals("笔记", chunks.getValue().get(0).documentName());
        assertEquals(2, chunks.getValue().size());
        verify(index, never()).replace(any());
    }

    @Test
    void shouldPublishOnlyPreparedResultAndPropagateFailure() {
        PreparedIndex prepared = new PreparedIndex(1L, List.of());
        doThrow(new IllegalStateException("写入失败")).when(index).replace(prepared);
        assertThrows(IllegalStateException.class, () -> service.commit(prepared));
        verifyNoInteractions(chunker);
    }

    @Test
    void shouldSynchronizeDeletion() {
        service.onDocumentDeleted(new DocumentDeleted(1L));
        verify(index).delete(1L);
    }
}
