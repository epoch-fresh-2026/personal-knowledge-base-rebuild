package com.ithwx.personalknowledgebase.qa.application;

import com.ithwx.personalknowledgebase.index.application.SearchKnowledge;
import com.ithwx.personalknowledgebase.index.domain.KnowledgeChunk;
import com.ithwx.personalknowledgebase.index.domain.SearchResult;
import com.ithwx.personalknowledgebase.qa.domain.AnswerGenerator;
import com.ithwx.personalknowledgebase.qa.domain.ChatMessage;
import com.ithwx.personalknowledgebase.qa.domain.MessageRole;
import com.ithwx.personalknowledgebase.qa.domain.QuestionJudge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnswerQuestionTest {
    @Mock private SearchKnowledge searchKnowledge;
    @Mock private QuestionJudge questionJudge;
    @Mock private AnswerGenerator answerGenerator;
    @Mock private SearchSubAgent subAgent;

    @Test
    void shouldPlanBeforeSingleSearchAndStreamAnswerWithSources() {
        String question = "支持什么格式？";
        SearchResult result = result(1L, 0, "支持 TXT。", 0.9);
        when(questionJudge.planSearch(question, List.of())).thenReturn(List.of(question));
        when(searchKnowledge.candidates(question)).thenReturn(List.of(result.chunk()));
        when(searchKnowledge.rerank(question, List.of(result.chunk()))).thenReturn(List.of(result));
        when(questionJudge.isSufficient(eq(question), anyList(), anyList())).thenReturn(true);
        doAnswer(invocation -> {
            Consumer<String> delta = invocation.getArgument(3);
            delta.accept("支持");
            delta.accept(" TXT。[证据 1]");
            return "支持 TXT。[证据 1]";
        }).when(answerGenerator).generateStreaming(eq(question), anyList(), anyList(), any());
        List<String> stages = new ArrayList<>();
        List<String> deltas = new ArrayList<>();

        ChatAnswer answer = service().answerStreaming(question, List.of(), stages::add, deltas::add);

        var order = inOrder(questionJudge, searchKnowledge, answerGenerator);
        order.verify(questionJudge).planSearch(question, List.of());
        order.verify(searchKnowledge).candidates(question);
        order.verify(searchKnowledge).rerank(question, List.of(result.chunk()));
        order.verify(questionJudge).isSufficient(eq(question), anyList(), anyList());
        order.verify(answerGenerator).generateStreaming(eq(question), anyList(), anyList(), any());
        order.verifyNoMoreInteractions();
        verifyNoInteractions(subAgent);
        assertFalse(answer.refused());
        assertEquals(List.of(question), answer.searchTasks());
        assertEquals(List.of("支持", " TXT。[证据 1]"), deltas);
        assertEquals("支持 TXT。[证据 1]", answer.answer());
        assertEquals(List.of(0), answer.sources().get(0).chunkIndexes());
        assertEquals("正在分析查询任务", stages.get(0));
        assertTrue(stages.contains("正在检索资料"));
        assertEquals("正在生成回答", stages.get(stages.size() - 1));
    }

    @Test
    void shouldSearchResolvedQuestionFromHistoryButAnswerOriginalQuestion() {
        String question = "它怎么连接？";
        String query = "Spring Boot 如何连接数据库？";
        List<ChatMessage> history = List.of(new ChatMessage(MessageRole.USER,
                "请介绍 Spring Boot", false, List.of(), LocalDateTime.now()));
        SearchResult result = result(1L, 0, "使用 JDBC。", 0.9);
        when(questionJudge.planSearch(question, history)).thenReturn(List.of(query));
        when(searchKnowledge.candidates(query)).thenReturn(List.of(result.chunk()));
        when(searchKnowledge.rerank(question, List.of(result.chunk()))).thenReturn(List.of(result));
        when(questionJudge.isSufficient(eq(question), eq(history), anyList())).thenReturn(true);
        when(answerGenerator.generateStreaming(eq(question), eq(history), anyList(), any()))
                .thenReturn("使用 JDBC。[证据 1]");

        ChatAnswer answer = service().answerStreaming(question, history, ignored -> {}, ignored -> {});

        assertEquals(List.of(query), answer.searchTasks());
        assertEquals(question, answer.question());
        verify(searchKnowledge).candidates(query);
        verify(answerGenerator).generateStreaming(eq(question), eq(history), anyList(), any());
        verifyNoInteractions(subAgent);
    }

    @Test
    void shouldStartSubagentsBeforeRetrievalThenRankAndAnswerOnce() {
        String question = "怎样避免任务重复处理并在重启后恢复？";
        List<String> tasks = List.of("查锁与租约", "查任务恢复");
        SearchResult lock = result(2L, 0, "锁保护任务领取", 0.9);
        SearchResult recovery = result(3L, 0, "从已保存阶段恢复", 0.95);
        List<KnowledgeChunk> candidates = List.of(lock.chunk(), recovery.chunk());
        when(questionJudge.planSearch(question, List.of())).thenReturn(tasks);
        when(subAgent.search(eq(tasks), any())).thenReturn(candidates);
        when(searchKnowledge.rerank(question, candidates)).thenReturn(List.of(recovery, lock));
        when(questionJudge.isSufficient(eq(question), anyList(), anyList())).thenReturn(true);
        when(answerGenerator.generateStreaming(eq(question), anyList(), anyList(), any()))
                .thenReturn("用锁领取，用租约恢复。");
        List<String> stages = new ArrayList<>();

        ChatAnswer answer = service().answerStreaming(question, List.of(), stages::add, ignored -> {});

        var order = inOrder(questionJudge, subAgent, searchKnowledge, answerGenerator);
        order.verify(questionJudge).planSearch(question, List.of());
        order.verify(subAgent).search(eq(tasks), any());
        order.verify(searchKnowledge).rerank(question, candidates);
        order.verify(questionJudge).isSufficient(eq(question), anyList(), argThat(evidence ->
                evidence.size() == 2 && evidence.get(0).text().equals("从已保存阶段恢复")));
        order.verify(answerGenerator).generateStreaming(eq(question), anyList(), anyList(), any());
        order.verifyNoMoreInteractions();
        assertFalse(answer.refused());
        assertEquals(tasks, answer.searchTasks());
        assertEquals(List.of(3L, 2L), answer.sources().stream().map(source -> source.documentId()).toList());
        assertTrue(stages.contains("正在并行查询两个子任务"));
    }

    @Test
    void shouldRefuseInsufficientEvidenceWithoutLaunchingAnotherSearch() {
        String question = "复杂问题";
        List<String> tasks = List.of("查询方面一", "查询方面二");
        when(questionJudge.planSearch(question, List.of())).thenReturn(tasks);
        when(subAgent.search(eq(tasks), any())).thenReturn(List.of());
        when(searchKnowledge.rerank(question, List.of())).thenReturn(List.of());
        when(questionJudge.isSufficient(question, List.of(), List.of())).thenReturn(false);
        List<String> deltas = new ArrayList<>();

        ChatAnswer answer = service().answerStreaming(question, List.of(), ignored -> {}, deltas::add);

        assertTrue(answer.refused());
        assertEquals(List.of(AnswerQuestion.NO_EVIDENCE_MESSAGE), deltas);
        assertTrue(answer.sources().isEmpty());
        verify(subAgent).search(eq(tasks), any());
        verify(questionJudge).planSearch(question, List.of());
        verify(questionJudge).isSufficient(question, List.of(), List.of());
        verify(searchKnowledge).rerank(question, List.of());
        verifyNoMoreInteractions(questionJudge, subAgent, searchKnowledge);
        verifyNoInteractions(answerGenerator);
    }

    @Test
    void shouldNotGenerateAnswerWhenSubagentFails() {
        List<String> tasks = List.of("查询方面一", "查询方面二");
        when(questionJudge.planSearch("复杂问题", List.of())).thenReturn(tasks);
        when(subAgent.search(eq(tasks), any())).thenThrow(new IllegalStateException("子任务失败"));

        assertThrows(IllegalStateException.class, () -> service()
                .answerStreaming("复杂问题", List.of(), ignored -> {}, ignored -> {}));

        verifyNoInteractions(searchKnowledge, answerGenerator);
        verify(questionJudge, never()).isSufficient(anyString(), anyList(), anyList());
    }

    private AnswerQuestion service() {
        return new AnswerQuestion(searchKnowledge, questionJudge, answerGenerator, subAgent);
    }

    private SearchResult result(Long documentId, int chunkIndex, String text, double score) {
        return new SearchResult(new KnowledgeChunk(documentId, "资料" + documentId,
                "note", null, chunkIndex, text), score);
    }
}
