package com.ithwx.personalknowledgebase.qa.infrastructure;

import com.ithwx.personalknowledgebase.qa.domain.Evidence;
import com.ithwx.personalknowledgebase.qa.domain.RetrievalDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SiliconFlowQuestionJudgeTest {

    @Mock
    private ChatModel chatModel;

    @Test
    void shouldAcceptEnoughEvidence() {
        SiliconFlowQuestionJudge judge = new SiliconFlowQuestionJudge(chatModel);
        when(chatModel.call(contains("支持 TXT"))).thenReturn("ENOUGH");

        RetrievalDecision decision = judge.decide(
                "支持什么格式？", List.of(), List.of(evidence("支持 TXT。")));

        assertTrue(decision.sufficient());
        verify(chatModel).call(argThat((String prompt) ->
                prompt.contains("来源：资料")
                        && prompt.contains("相关度：0.90")
                        && prompt.contains("内容：支持 TXT。")));
    }

    @Test
    void shouldReadRewrittenQuestion() {
        SiliconFlowQuestionJudge judge = new SiliconFlowQuestionJudge(chatModel);
        when(chatModel.call(contains("它怎么连接")))
                .thenReturn("REWRITE: Spring Boot 如何连接数据库？");

        RetrievalDecision decision = judge.decide(
                "它怎么连接？", List.of(), List.of());

        assertFalse(decision.sufficient());
        assertEquals("Spring Boot 如何连接数据库？", decision.rewrittenQuestion());
    }

    private Evidence evidence(String text) {
        return new Evidence(1L, "资料", "note", null, 0, text, 0.9);
    }
}
