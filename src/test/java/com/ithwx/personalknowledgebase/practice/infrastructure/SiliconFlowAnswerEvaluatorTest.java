package com.ithwx.personalknowledgebase.practice.infrastructure;

import com.ithwx.personalknowledgebase.practice.domain.PracticeEvaluation;
import com.ithwx.personalknowledgebase.practice.domain.PracticeSession;
import com.ithwx.personalknowledgebase.practice.domain.PracticeSource;
import com.ithwx.personalknowledgebase.practice.domain.PracticeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SiliconFlowAnswerEvaluatorTest {

    @Mock
    private ChatModel chatModel;

    @Test
    void shouldParseScoreAndFeedback() {
        when(chatModel.call(anyString())).thenReturn("回答如下：{\"score\":45,\"feedback\":\"答出了原子性，还需要补充其他三项。\"}");
        SiliconFlowAnswerEvaluator evaluator = new SiliconFlowAnswerEvaluator(chatModel);
        PracticeSession session = waitingSession();

        PracticeEvaluation evaluation = evaluator.evaluate(session, "事务是原子性的");

        assertThat(evaluation.score()).isEqualTo(45);
        assertThat(evaluation.feedback()).contains("其他三项");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue()).contains("事务是原子性的", "原子性、一致性、隔离性和持久性", "严格的复习答案评价器");
    }

    private PracticeSession waitingSession() {
        return new PracticeSession(
                1L,
                "数据库事务",
                "事务的 ACID 分别是什么？",
                "原子性、一致性、隔离性和持久性。",
                "[资料 1] 数据库笔记\n事务具有 ACID 特性。",
                List.of(new PracticeSource(1L, "数据库笔记", "FILE", null, 0, "事务具有 ACID 特性。")),
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
