package com.ithwx.personalknowledgebase.index.infrastructure;

import com.ithwx.personalknowledgebase.index.domain.KnowledgeChunk;
import com.ithwx.personalknowledgebase.index.domain.PreparedIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PgVectorKnowledgeIndexTest {
    @Mock private VectorStore vectorStore;
    @Mock private JdbcTemplate jdbc;
    @Mock private EmbeddingModel model;
    private PgVectorKnowledgeIndex index;

    @BeforeEach
    void setUp() {
        index = new PgVectorKnowledgeIndex(vectorStore, jdbc, model, 1);
    }

    @Test
    void shouldComputeVectorsInBoundedBatchesWithoutWriting() {
        when(model.embed(List.of("第一段"))).thenReturn(List.of(new float[]{1, 0, 0}));
        when(model.embed(List.of("第二段"))).thenReturn(List.of(new float[]{0, 1, 0}));
        PreparedIndex prepared = index.prepare(1L, List.of(chunk(1L, 0, "第一段"), chunk(1L, 1, "第二段")));
        assertEquals(2, prepared.chunks().size());
        assertArrayEquals(new float[]{0, 1, 0}, prepared.chunks().get(1).embedding());
        verifyNoInteractions(jdbc, vectorStore);
    }

    @Test
    void shouldRejectIncompleteEmbeddingResponseBeforeAnyWrite() {
        when(model.embed(List.of("正文"))).thenReturn(List.of());
        assertThrows(IllegalStateException.class, () -> index.prepare(1L, List.of(chunk(1L, 0, "正文"))));
        verifyNoInteractions(jdbc);
    }

    @Test
    void shouldWritePreparedVectorsWithoutCallingModel() {
        index.replace(new PreparedIndex(1L, List.of(new PreparedIndex.EmbeddedChunk(chunk(1L, 0, "正文"), new float[]{1, 0, 0}))));
        verify(jdbc).update("DELETE FROM vector_store WHERE metadata ->> 'documentId' = ?", "1");
        verifyNoInteractions(model, vectorStore);
    }

    @Test
    void shouldMergeAndRemoveDuplicateChunks() {
        KnowledgeChunk both = chunk(1L, 0, "同时命中");
        KnowledgeChunk vectorOnly = chunk(2L, 0, "仅向量命中");
        KnowledgeChunk keywordOnly = chunk(3L, 0, "仅关键词命中");
        assertEquals(List.of(both, vectorOnly, keywordOnly), index.merge(List.of(both, vectorOnly), List.of(keywordOnly, both)));
    }

    private KnowledgeChunk chunk(Long id, int number, String text) {
        return new KnowledgeChunk(id, "资料" + id, "note", null, number, text);
    }
}
