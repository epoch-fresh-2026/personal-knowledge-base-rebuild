package com.ithwx.personalknowledgebase.practice.application;

import com.ithwx.personalknowledgebase.index.application.SearchKnowledge;
import com.ithwx.personalknowledgebase.index.domain.KnowledgeChunk;
import com.ithwx.personalknowledgebase.index.domain.SearchResult;
import com.ithwx.personalknowledgebase.practice.domain.AnswerEvaluator;
import com.ithwx.personalknowledgebase.practice.domain.GeneratedQuestion;
import com.ithwx.personalknowledgebase.practice.domain.PracticeEvaluation;
import com.ithwx.personalknowledgebase.practice.domain.PracticeRepository;
import com.ithwx.personalknowledgebase.practice.domain.PracticeSession;
import com.ithwx.personalknowledgebase.practice.domain.PracticeSource;
import com.ithwx.personalknowledgebase.practice.domain.PracticeStatus;
import com.ithwx.personalknowledgebase.practice.domain.QuestionGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PracticeServiceTest {

    @Mock
    private SearchKnowledge searchKnowledge;
    @Mock
    private QuestionGenerator questionGenerator;
    @Mock
    private AnswerEvaluator answerEvaluator;
    @Mock
    private PracticeRepository practiceRepository;

    private PracticeService service;

    @BeforeEach
    void setUp() {
        service = new PracticeService(searchKnowledge, questionGenerator, answerEvaluator, practiceRepository);
    }

    @Test
    void shouldCreateQuestionFromKnowledgeBaseEvidence() {
        KnowledgeChunk chunk = new KnowledgeChunk(11L, "数据库复习资料", "FILE", null, 3, "事务具有 ACID 特性。");
        when(searchKnowledge.search("数据库事务")).thenReturn(List.of(new SearchResult(chunk, 0.92)));
        when(questionGenerator.generate(any(), any())).thenReturn(
                new GeneratedQuestion("事务的 ACID 分别是什么？", "原子性、一致性、隔离性和持久性。")
        );
        when(practiceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PracticeSession session = service.create("  数据库事务  ");

        assertThat(session.topic()).isEqualTo("数据库事务");
        assertThat(session.question()).contains("ACID");
        assertThat(session.sources()).singleElement().satisfies(source -> {
            assertThat(source.documentId()).isEqualTo(11L);
            assertThat(source.chunkIndex()).isEqualTo(3);
        });
        assertThat(session.evidenceSnapshot()).contains("数据库复习资料", "事务具有 ACID 特性");
        verify(practiceRepository).save(any(PracticeSession.class));
    }

    @Test
    void shouldRejectCreatingQuestionWithoutEvidence() {
        when(searchKnowledge.search("不存在的知识点")).thenReturn(List.of());

        assertThatThrownBy(() -> service.create("不存在的知识点"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有找到");
        verify(questionGenerator, never()).generate(any(), any());
    }

    @Test
    void shouldEvaluateAndSaveAnswer() {
        PracticeSession session = waitingSession();
        when(practiceRepository.findById(9L)).thenReturn(Optional.of(session));
        when(answerEvaluator.evaluate(session, "原子性和一致性")).thenReturn(
                new PracticeEvaluation(50, "还需要补充隔离性和持久性。")
        );
        when(practiceRepository.save(session)).thenReturn(session);

        PracticeSession completed = service.answer(9L, "  原子性和一致性  ");

        assertThat(completed.status()).isEqualTo(PracticeStatus.COMPLETED);
        assertThat(completed.needsReview()).isTrue();
        assertThat(completed.score()).isEqualTo(50);
        verify(practiceRepository).save(session);
    }

    @Test
    void shouldReportMissingPractice() {
        when(practiceRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.answer(99L, "回答"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void shouldCreateRetryFromOriginalMistake() {
        PracticeSession original = completedMistake(9L, null);
        when(practiceRepository.findById(9L)).thenReturn(Optional.of(original));
        when(questionGenerator.generateRetry(
                original.topic(), original.question(), original.sources()))
                .thenReturn(new GeneratedQuestion(
                        "事务为什么需要隔离性？",
                        "隔离性避免并发事务相互干扰。"));
        when(practiceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PracticeSession retry = service.retry(9L);

        assertThat(retry.retryOfId()).isEqualTo(9L);
        assertThat(retry.status()).isEqualTo(PracticeStatus.WAITING_FOR_ANSWER);
        assertThat(retry.question()).isEqualTo("事务为什么需要隔离性？");
    }

    @Test
    void shouldResolveOriginalMistakeWhenRetryPasses() {
        PracticeSession original = completedMistake(9L, null);
        PracticeSession retry = waitingSession(10L, 9L);
        when(practiceRepository.findById(10L)).thenReturn(Optional.of(retry));
        when(practiceRepository.findById(9L)).thenReturn(Optional.of(original));
        when(answerEvaluator.evaluate(retry, "完整回答")).thenReturn(
                new PracticeEvaluation(80, "本次已经掌握。")
        );
        when(practiceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PracticeSession completed = service.answer(10L, "完整回答");

        assertThat(completed.needsReview()).isFalse();
        assertThat(original.needsReview()).isFalse();
        verify(practiceRepository).save(original);
    }

    private PracticeSession waitingSession() {
        return waitingSession(9L, null);
    }

    private PracticeSession waitingSession(Long id, Long retryOfId) {
        return new PracticeSession(
                id,
                "数据库事务",
                "事务的 ACID 分别是什么？",
                "原子性、一致性、隔离性和持久性。",
                "[资料 1] 数据库复习资料\n事务具有 ACID 特性。",
                List.of(new PracticeSource(11L, "数据库复习资料", "FILE", null, 3, "事务具有 ACID 特性。")),
                null,
                null,
                null,
                false,
                PracticeStatus.WAITING_FOR_ANSWER,
                LocalDateTime.now(),
                retryOfId
        );
    }

    private PracticeSession completedMistake(Long id, Long retryOfId) {
        PracticeSession session = waitingSession(id, retryOfId);
        session.complete("不完整回答", new PracticeEvaluation(40, "需要继续复习。"));
        return session;
    }
}
