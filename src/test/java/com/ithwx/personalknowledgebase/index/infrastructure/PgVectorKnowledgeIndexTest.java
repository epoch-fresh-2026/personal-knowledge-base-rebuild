package com.ithwx.personalknowledgebase.index.infrastructure;

import com.ithwx.personalknowledgebase.index.domain.KnowledgeChunk;
import com.ithwx.personalknowledgebase.index.domain.PreparedIndex;
import com.ithwx.personalknowledgebase.index.domain.SearchQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

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
    void shouldFuseUsingRanksAndRemoveCrossRouteDuplicates() {
        KnowledgeChunk both = chunk(1L, 0, "同时命中");
        KnowledgeChunk vectorOnly = chunk(2L, 0, "仅向量命中");
        KnowledgeChunk keywordOnly = chunk(3L, 0, "仅关键词命中");
        // 两路命中排第一；仅关键词命中的第 1 名超过仅向量命中的第 2 名。
        assertEquals(List.of(both, keywordOnly, vectorOnly),
                index.fuse(List.of(both, vectorOnly), List.of(keywordOnly, both), 10));
    }

    @Test
    void shouldAccumulateRanksRatherThanOnlyCountMatches() {
        KnowledgeChunk first = chunk(2L, 0, "两路第一");
        KnowledgeChunk second = chunk(1L, 0, "两路第二");
        assertEquals(List.of(first, second),
                index.fuse(List.of(first, second), List.of(first, second), 10));
    }

    @Test
    void shouldNotCountRepeatedChunkWithinOneRouteTwice() {
        KnowledgeChunk repeated = chunk(1L, 0, "单路重复");
        KnowledgeChunk both = chunk(2L, 0, "真正两路命中");
        assertEquals(List.of(both, repeated),
                index.fuse(List.of(repeated, repeated, repeated, both), List.of(both), 10));
    }

    @Test
    void shouldRankAfterDeduplicatingEachRoute() {
        KnowledgeChunk first = chunk(3L, 0, "向量第一");
        KnowledgeChunk second = chunk(1L, 0, "向量去重后第二");
        KnowledgeChunk keywordFirst = chunk(4L, 0, "关键词第一");
        KnowledgeChunk keywordSecond = chunk(2L, 0, "关键词第二");
        assertEquals(List.of(first, keywordFirst, second, keywordSecond), index.fuse(
                List.of(first, first, second), List.of(keywordFirst, keywordSecond), 10));
    }

    @Test
    void shouldKeepSingleRouteRankWhenOtherRouteIsEmpty() {
        List<KnowledgeChunk> ranked = List.of(chunk(3L, 0, "第一"), chunk(1L, 0, "第二"));
        assertEquals(ranked, index.fuse(ranked, List.of(), 10));
        assertEquals(ranked, index.fuse(List.of(), ranked, 10));
    }

    @Test
    void shouldReturnEmptyWhenNeitherRouteMatches() {
        assertEquals(List.of(), index.fuse(List.of(), List.of(), 10));
    }

    @Test
    void shouldLimitCandidatesAfterFusingBothRoutes() {
        KnowledgeChunk vectorFirst = chunk(1L, 0, "向量第一");
        KnowledgeChunk both = chunk(3L, 0, "两路第二");
        KnowledgeChunk keywordFirst = chunk(2L, 0, "关键词第一");
        assertEquals(List.of(both, vectorFirst),
                index.fuse(List.of(vectorFirst, both), List.of(keywordFirst, both), 2));
        assertEquals(List.of(), index.fuse(List.of(both), List.of(both), 0));
    }

    @Test
    void shouldResolveTiesByDocumentIdAndChunkIndex() {
        KnowledgeChunk later = chunk(2L, 0, "资料二");
        KnowledgeChunk earlier = chunk(1L, 0, "资料一");
        assertEquals(List.of(earlier, later), index.fuse(List.of(later), List.of(earlier), 10));
        assertEquals(List.of(earlier, later), index.fuse(List.of(earlier), List.of(later), 10));
        KnowledgeChunk nextChunk = chunk(1L, 1, "第二块");
        assertEquals(List.of(earlier, nextChunk), index.fuse(List.of(nextChunk), List.of(earlier), 10));
    }

    @Test
    void shouldKeepDistinctChunksEvenWhenTheirTextIsIdentical() {
        KnowledgeChunk first = chunk(1L, 0, "相同正文");
        KnowledgeChunk next = chunk(1L, 1, "相同正文");
        KnowledgeChunk other = chunk(2L, 0, "相同正文");
        assertEquals(List.of(first, other, next),
                index.fuse(List.of(first, next), List.of(other), 10));
    }

    @Test
    void shouldKeepFirstChunkMetadataWhenBothRoutesMatchItsIdentity() {
        KnowledgeChunk first = chunk(1L, 0, "原始正文");
        KnowledgeChunk sameIdentity = new KnowledgeChunk(1L, "另一份元数据", "pdf", "https://example.com", 0, "另一路正文");
        assertEquals(List.of(first), index.fuse(List.of(first), List.of(sameIdentity), 10));
    }

    @Test
    void shouldWireBothSearchRoutesIntoBoundedFusion() {
        KnowledgeChunk vectorFirst = chunk(1L, 0, "向量第一");
        KnowledgeChunk both = chunk(3L, 0, "同时命中");
        KnowledgeChunk keywordFirst = chunk(2L, 0, "关键词第一");
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                vectorDocument(vectorFirst), vectorDocument(both)));
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<KnowledgeChunk>>any(),
                eq("事务"), eq("事务"), eq("事务"), eq(2)))
                .thenReturn(List.of(keywordFirst, both));

        assertEquals(List.of(both, vectorFirst), index.search(new SearchQuery("事务", 2, 0.55)));

        ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(request.capture());
        assertEquals(2, request.getValue().getTopK());
        assertEquals("事务", request.getValue().getQuery());
        assertEquals(0.55, request.getValue().getSimilarityThreshold());
        verifyNoInteractions(model);
    }

    private org.springframework.ai.document.Document vectorDocument(KnowledgeChunk chunk) {
        return org.springframework.ai.document.Document.builder()
                .text(chunk.text())
                .metadata(Map.of("documentId", chunk.documentId(), "documentName", chunk.documentName(),
                        "sourceType", chunk.sourceType(), "chunkIndex", chunk.chunkIndex()))
                .build();
    }

    private KnowledgeChunk chunk(Long id, int number, String text) {
        return new KnowledgeChunk(id, "资料" + id, "note", null, number, text);
    }
}
