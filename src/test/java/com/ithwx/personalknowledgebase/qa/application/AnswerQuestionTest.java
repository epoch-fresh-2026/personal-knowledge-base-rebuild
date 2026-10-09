package com.ithwx.personalknowledgebase.qa.application;

import com.ithwx.personalknowledgebase.index.application.SearchKnowledge;
import com.ithwx.personalknowledgebase.index.domain.KnowledgeChunk;
import com.ithwx.personalknowledgebase.index.domain.SearchResult;
import com.ithwx.personalknowledgebase.qa.domain.AnswerGenerator;
import com.ithwx.personalknowledgebase.qa.domain.Evidence;
import com.ithwx.personalknowledgebase.qa.domain.QuestionJudge;
import com.ithwx.personalknowledgebase.qa.domain.RetrievalDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnswerQuestionTest {

    @Mock
    private SearchKnowledge searchKnowledge;
    @Mock
    private QuestionJudge questionJudge;
    @Mock
    private AnswerGenerator answerGenerator;

    @Test
    void shouldAnswerWhenFirstEvidenceIsEnough() {
        AnswerQuestion service = service();
        when(searchKnowledge.search("支持什么格式？"))
                .thenReturn(List.of(result(1L, 0, "支持 TXT。", 0.9)));
        when(questionJudge.decide(
                org.mockito.ArgumentMatchers.eq("支持什么格式？"),
                anyList(), anyList()))
                .thenReturn(RetrievalDecision.enough());
        when(answerGenerator.generate(
                org.mockito.ArgumentMatchers.eq("支持什么格式？"),
                anyList(), anyList()))
                .thenReturn("支持 TXT。[证据 1]");

        ChatAnswer answer = service.answer("支持什么格式？", List.of());

        assertFalse(answer.refused());
        assertFalse(answer.secondSearchExecuted());
        assertEquals("支持 TXT。[证据 1]", answer.answer());
        assertEquals(List.of(0), answer.sources().get(0).chunkIndexes());
    }

    @Test
    void shouldRewriteAndSearchOnceMore() {
        AnswerQuestion service = service();
        when(searchKnowledge.search("它怎么连接？"))
                .thenReturn(List.of(result(1L, 0, "Spring Boot 项目。", 0.6)));
        when(searchKnowledge.search("Spring Boot 如何连接数据库？"))
                .thenReturn(List.of(result(1L, 1, "使用 JDBC。", 0.9)));
        when(questionJudge.decide(
                org.mockito.ArgumentMatchers.eq("它怎么连接？"),
                anyList(), anyList()))
                .thenReturn(
                        RetrievalDecision.retry("Spring Boot 如何连接数据库？"),
                        RetrievalDecision.enough()
                );
        when(answerGenerator.generate(
                org.mockito.ArgumentMatchers.eq("它怎么连接？"),
                anyList(), anyList()))
                .thenReturn("使用 JDBC。[证据 1]");

        ChatAnswer answer = service.answer("它怎么连接？", List.of());

        assertFalse(answer.refused());
        assertTrue(answer.secondSearchExecuted());
        assertEquals("Spring Boot 如何连接数据库？", answer.rewrittenQuestion());
        assertEquals(List.of(1, 0), answer.sources().get(0).chunkIndexes());
    }

    @Test
    void shouldRefuseWhenSecondSearchIsStillInsufficient() {
        AnswerQuestion service = service();
        when(searchKnowledge.search("未知问题"))
                .thenReturn(List.of());
        when(searchKnowledge.search("改写后的未知问题"))
                .thenReturn(List.of());
        when(questionJudge.decide(
                org.mockito.ArgumentMatchers.eq("未知问题"),
                anyList(), anyList()))
                .thenReturn(
                        RetrievalDecision.retry("改写后的未知问题"),
                        RetrievalDecision.retry("仍然无法检索")
                );

        ChatAnswer answer = service.answer("未知问题", List.of());

        assertTrue(answer.refused());
        assertEquals(AnswerQuestion.NO_EVIDENCE_MESSAGE, answer.answer());
        assertTrue(answer.sources().isEmpty());
        verify(answerGenerator, never()).generate(
                org.mockito.ArgumentMatchers.anyString(), anyList(), anyList());
    }

    @Test
    void shouldStreamWithRealRetrievalStagesAndKeepSources() {
        when(searchKnowledge.search("问题"))
                .thenReturn(List.of(result(1L, 0, "证据", 0.9)));
        when(questionJudge.decide(anyString(), anyList(), anyList()))
                .thenReturn(RetrievalDecision.retry("改写问题"), RetrievalDecision.enough());
        when(searchKnowledge.search("改写问题"))
                .thenReturn(List.of(result(1L, 1, "补充证据", 0.95)));
        doAnswer(invocation -> {
            Consumer<String> delta = invocation.getArgument(3);
            delta.accept("第一段");
            delta.accept("第二段");
            return "第一段第二段";
        }).when(answerGenerator).generateStreaming(anyString(), anyList(), anyList(), any());
        List<String> stages = new ArrayList<>();
        List<String> deltas = new ArrayList<>();

        ChatAnswer answer = service().answerStreaming("问题", List.of(), stages::add, deltas::add);

        assertEquals(List.of("正在检索资料", "找到 1 个片段，正在判断证据",
                "证据不足，正在二次检索", "二次检索完成，正在重新判断证据", "正在生成回答"), stages);
        assertEquals(List.of("第一段", "第二段"), deltas);
        assertEquals("第一段第二段", answer.answer());
        assertTrue(answer.secondSearchExecuted());
        assertEquals(List.of(1, 0), answer.sources().get(0).chunkIndexes());
        verify(answerGenerator, never()).generate(anyString(), anyList(), anyList());
    }

    @Test
    void shouldStreamRefusalWithoutCallingAnswerModel() {
        when(searchKnowledge.search("未知问题")).thenReturn(List.of());
        when(questionJudge.decide(anyString(), anyList(), anyList()))
                .thenReturn(RetrievalDecision.retry(null));
        List<String> deltas = new ArrayList<>();

        ChatAnswer answer = service().answerStreaming("未知问题", List.of(), ignored -> {}, deltas::add);

        assertTrue(answer.refused());
        assertEquals(List.of(AnswerQuestion.NO_EVIDENCE_MESSAGE), deltas);
        verify(answerGenerator, never()).generateStreaming(anyString(), anyList(), anyList(), any());
    }

    private AnswerQuestion service() {
        return new AnswerQuestion(
                searchKnowledge, questionJudge, answerGenerator, 5, 2000);
    }

    private SearchResult result(
            Long documentId,
            int chunkIndex,
            String text,
            double score
    ) {
        return new SearchResult(
                new KnowledgeChunk(
                        documentId, "项目说明", "note", null, chunkIndex, text),
                score
        );
    }
}
