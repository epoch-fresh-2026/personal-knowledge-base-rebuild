package com.ithwx.personalknowledgebase.index.application;

import com.ithwx.personalknowledgebase.index.domain.KnowledgeChunk;
import com.ithwx.personalknowledgebase.index.domain.KnowledgeIndex;
import com.ithwx.personalknowledgebase.index.domain.KnowledgeReranker;
import com.ithwx.personalknowledgebase.index.domain.SearchQuery;
import com.ithwx.personalknowledgebase.index.domain.SearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchKnowledgeTest {

    @Mock
    private KnowledgeIndex knowledgeIndex;
    @Mock
    private KnowledgeReranker knowledgeReranker;

    @Test
    void shouldRetrieveRerankAndLimitContext() {
        SearchKnowledge service = new SearchKnowledge(
                knowledgeIndex, knowledgeReranker, 2, 6, 0.55
        );
        when(knowledgeIndex.search(any())).thenReturn(List.of(
                chunk(1L, "一二三"),
                chunk(2L, "四五六"),
                chunk(3L, "七八九")
        ));
        when(knowledgeReranker.rerank(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(
                        result(1L, "一二三", 0.9),
                        result(2L, "四五六", 0.8),
                        result(3L, "七八九", 0.7)
        ));

        List<SearchResult> results = service.search("  Spring 数据库  ");

        ArgumentCaptor<SearchQuery> captor = ArgumentCaptor.forClass(SearchQuery.class);
        verify(knowledgeIndex).search(captor.capture());
        assertEquals("Spring 数据库", captor.getValue().text());
        assertEquals(4, captor.getValue().candidateLimit());
        assertEquals(2, results.size());
    }

    @Test
    void shouldRejectBlankQuestion() {
        SearchKnowledge service = new SearchKnowledge(
                knowledgeIndex, knowledgeReranker, 5, 3000, 0.55
        );

        assertThrows(IllegalArgumentException.class, () -> service.search("  "));
        verify(knowledgeIndex, never()).search(any());
        verify(knowledgeReranker, never()).rerank(anyString(), anyList(), anyInt());
    }

    @Test
    void shouldRerankFusedCandidatesAndKeepModelScoreAsFinalScore() {
        SearchKnowledge service = new SearchKnowledge(knowledgeIndex, knowledgeReranker, 1, 3000, 0.55);
        KnowledgeChunk fusedFirst = chunk(1L, "两路命中");
        KnowledgeChunk fusedSecond = chunk(2L, "单路命中");
        List<KnowledgeChunk> fused = List.of(fusedFirst, fusedSecond);
        when(knowledgeIndex.search(any())).thenReturn(fused);
        // 融合只负责候选筛选，最终顺序和 score 仍以模型重排序为准。
        when(knowledgeReranker.rerank("事务", fused, 1))
                .thenReturn(List.of(new SearchResult(fusedSecond, 0.92)));

        assertEquals(List.of(new SearchResult(fusedSecond, 0.92)), service.search("事务"));
        verify(knowledgeReranker).rerank("事务", fused, 1);
    }

    private SearchResult result(Long documentId, String text, double score) {
        return new SearchResult(chunk(documentId, text), score);
    }

    private KnowledgeChunk chunk(Long documentId, String text) {
        return new KnowledgeChunk(
                documentId, "资料" + documentId, "note", null,
                0, text
        );
    }
}
