package com.ithwx.personalknowledgebase.practice.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PracticeSessionTest {

    @Test
    void shouldCompletePracticeAndMarkLowScoreForReview() {
        PracticeSession session = waitingSession();

        session.complete("事务具有原子性和一致性", new PracticeEvaluation(55, "还需要补充隔离性和持久性。"));

        assertThat(session.status()).isEqualTo(PracticeStatus.COMPLETED);
        assertThat(session.userAnswer()).isEqualTo("事务具有原子性和一致性");
        assertThat(session.score()).isEqualTo(55);
        assertThat(session.needsReview()).isTrue();
    }

    @Test
    void shouldRejectRepeatedSubmission() {
        PracticeSession session = waitingSession();
        session.complete("第一次回答", new PracticeEvaluation(80, "回答基本正确。"));

        assertThatThrownBy(() -> session.complete("第二次回答", new PracticeEvaluation(90, "正确。")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已经提交");
    }

    @Test
    void shouldCreateRetryLinkedToOriginalMistake() {
        PracticeSession original = waitingSession();
        original.complete("不完整回答", new PracticeEvaluation(40, "需要继续复习。"));

        PracticeSession retry = PracticeSession.retryOf(
                original,
                new GeneratedQuestion("事务为什么需要隔离性？", "隔离性避免并发事务相互干扰。")
        );

        assertThat(retry.retryOfId()).isEqualTo(1L);
        assertThat(retry.status()).isEqualTo(PracticeStatus.WAITING_FOR_ANSWER);
        assertThat(retry.question()).isEqualTo("事务为什么需要隔离性？");
        assertThat(retry.sources()).isEqualTo(original.sources());
    }

    @Test
    void shouldMarkMistakeAsResolved() {
        PracticeSession original = waitingSession();
        original.complete("不完整回答", new PracticeEvaluation(40, "需要继续复习。"));

        original.markReviewResolved();

        assertThat(original.needsReview()).isFalse();
    }

    private PracticeSession waitingSession() {
        return new PracticeSession(
                1L,
                "数据库事务",
                "事务的 ACID 分别是什么？",
                "原子性、一致性、隔离性和持久性。",
                "[资料 1] 数据库复习资料\n事务具有 ACID 特性。",
                List.of(new PracticeSource(1L, "数据库复习资料", "FILE", null, 0, "事务具有 ACID 特性。")),
                null,
                null,
                null,
                false,
                PracticeStatus.WAITING_FOR_ANSWER,
                LocalDateTime.now(),
                null
        );
    }
}
