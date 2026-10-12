package com.ithwx.personalknowledgebase.qa.infrastructure;

import com.ithwx.personalknowledgebase.qa.domain.ChatMessage;
import com.ithwx.personalknowledgebase.qa.domain.Evidence;
import com.ithwx.personalknowledgebase.qa.domain.MessageRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SiliconFlowQuestionJudgeTest {
    @Mock private ChatModel chatModel;

    @Test
    void shouldPlanOneStandaloneQueryUsingHistoryAndPreserveModelOptions() {
        modelOptions();
        List<ChatMessage> history = List.of(new ChatMessage(MessageRole.USER,
                "请介绍 Spring Boot", false, List.of(), LocalDateTime.now()));
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            String text = prompt.getInstructions().get(0).getText();
            assertTrue(text.contains("请介绍 Spring Boot"));
            assertTrue(text.contains("它怎么连接"));
            assertInstanceOf(OpenAiChatOptions.class, prompt.getOptions());
            assertEquals("test-model", prompt.getOptions().getModel());
            assertEquals(256, prompt.getOptions().getMaxTokens());
            return response("  Spring Boot 如何连接数据库？  \n\n");
        });

        List<String> tasks = judge().planSearch("它怎么连接？", history);

        assertEquals(List.of("Spring Boot 如何连接数据库？"), tasks);
    }

    @Test
    void shouldPlanTwoIndependentQueriesBeforeSeeingAnyEvidence() {
        modelOptions();
        when(chatModel.call(any(Prompt.class))).thenReturn(
                response(" 如何避免并发重复处理？ \n 重启后如何恢复任务？"));

        assertEquals(List.of("如何避免并发重复处理？", "重启后如何恢复任务？"),
                judge().planSearch("并发与重启", List.of()));
    }

    @Test
    void shouldRejectEmptyPlansAndPlansOverTwoTasks() {
        modelOptions();
        when(chatModel.call(any(Prompt.class))).thenReturn(
                response(" \n "), response("任务一\n任务二\n任务三"));

        assertThrows(IllegalStateException.class, () -> judge().planSearch("问题", List.of()));
        assertThrows(IllegalStateException.class, () -> judge().planSearch("问题", List.of()));
    }

    @Test
    void shouldAcceptEnoughEvidenceAndIncludeSourceInformation() {
        when(chatModel.call(contains("支持 TXT"))).thenReturn("ENOUGH");

        assertTrue(judge().isSufficient("支持什么格式？", List.of(),
                List.of(new Evidence(1L, "资料", "note", null, 0, "支持 TXT。", 0.9))));
        verify(chatModel).call(argThat((String prompt) ->
                prompt.contains("来源：资料")
                        && prompt.contains("相关度：0.90")
                        && prompt.contains("内容：支持 TXT。")));
    }

    @Test
    void shouldReturnFalseForInsufficientEvidence() {
        when(chatModel.call(anyString())).thenReturn("INSUFFICIENT");

        assertFalse(judge().isSufficient("问题", List.of(), List.of()));
    }

    @Test
    void shouldRejectOldRewriteDecisionDuringEvidenceJudgment() {
        when(chatModel.call(anyString())).thenReturn("REWRITE:另一个问题");

        assertThrows(IllegalStateException.class,
                () -> judge().isSufficient("问题", List.of(), List.of()));
    }

    private SiliconFlowQuestionJudge judge() {
        return new SiliconFlowQuestionJudge(chatModel);
    }

    private void modelOptions() {
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder()
                .model("test-model").maxTokens(1024).build());
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
