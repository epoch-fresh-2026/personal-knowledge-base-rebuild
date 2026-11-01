package com.ithwx.personalknowledgebase.qa.infrastructure;

import com.ithwx.personalknowledgebase.qa.domain.ChatMessage;
import com.ithwx.personalknowledgebase.qa.domain.Evidence;
import com.ithwx.personalknowledgebase.qa.domain.MessageRole;
import com.ithwx.personalknowledgebase.qa.domain.SearchPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;

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
        List<ChatMessage> history = List.of(new ChatMessage(MessageRole.USER,
                "请介绍 Spring Boot", false, List.of(), LocalDateTime.now()));
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            String text = prompt.getUserMessage().getText();
            assertTrue(text.contains("请介绍 Spring Boot"));
            assertTrue(text.contains("它怎么连接"));
            assertTrue(prompt.getSystemMessage().getText().contains("原样返回"));
            assertInstanceOf(OpenAiChatOptions.class, prompt.getOptions());
            assertEquals("test-model", prompt.getOptions().getModel());
            assertEquals(512, prompt.getOptions().getMaxTokens());
            var options = assertInstanceOf(OpenAiChatOptions.class, prompt.getOptions());
            assertEquals(ResponseFormat.Type.JSON_OBJECT, options.getResponseFormat().getType());
            return response("""
                    {"resolvedQuestion":"  Spring Boot 如何连接数据库？  ",
                     "searchTasks":[" Spring Boot 如何连接数据库？ "]}
                    """);
        });

        SearchPlan plan = judge().planSearch("它怎么连接？", history);

        assertEquals("Spring Boot 如何连接数据库？", plan.resolvedQuestion());
        assertEquals(List.of("Spring Boot 如何连接数据库？"), plan.searchTasks());
    }

    @Test
    void shouldPlanTwoIndependentQueriesBeforeSeeingAnyEvidence() {
        when(chatModel.call(any(Prompt.class))).thenReturn(
                response("""
                        {"resolvedQuestion":"模型改写后的并发与恢复问题",
                         "searchTasks":[" 如何避免并发重复处理？ "," 重启后如何恢复任务？ "]}
                        """));

        SearchPlan plan = judge().planSearch("并发与重启", List.of());
        assertEquals("并发与重启", plan.resolvedQuestion());
        assertEquals(List.of("如何避免并发重复处理？", "重启后如何恢复任务？"),
                plan.searchTasks());
    }

    @Test
    void shouldResolveBothAspectsOfFollowUpBeforeSplittingSearchTasks() {
        List<ChatMessage> history = List.of(new ChatMessage(MessageRole.USER,
                "请介绍多个 worker 如何处理入库任务", false, List.of(), LocalDateTime.now()));
        when(chatModel.call(any(Prompt.class))).thenReturn(response("""
                {"resolvedQuestion":"多个 worker 如何避免重复处理入库任务，服务重启后如何恢复未完成任务？",
                 "searchTasks":["多个 worker 如何避免重复处理入库任务？","服务重启后如何恢复未完成任务？"]}
                """));

        SearchPlan plan = judge().planSearch("它们怎么避免重复处理，重启后又怎么恢复？", history);

        assertEquals("多个 worker 如何避免重复处理入库任务，服务重启后如何恢复未完成任务？", plan.resolvedQuestion());
        assertEquals(List.of("多个 worker 如何避免重复处理入库任务？", "服务重启后如何恢复未完成任务？"),
                plan.searchTasks());
    }

    @Test
    void shouldKeepOriginalSingleQuestionWhenThereIsNoHistoryToResolve() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("""
                {"resolvedQuestion":"模型改写后的问题","searchTasks":["模型改写后的问题"]}
                """));
        SearchPlan plan = judge().planSearch("CPU 和 GPU 有什么区别？", List.of());
        assertEquals("CPU 和 GPU 有什么区别？", plan.resolvedQuestion());
        assertEquals(List.of("CPU 和 GPU 有什么区别？"),
                plan.searchTasks());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            " ", "null", "[]", "{}", "旧格式的查询问题",
            "{\"resolvedQuestion\":\"问题\",\"searchTasks\":[]}",
            "{\"resolvedQuestion\":\"问题\",\"searchTasks\":[\"任务一\",\"任务二\",\"任务三\"]}",
            "{\"resolvedQuestion\":\" \",\"searchTasks\":[\"任务\"]}",
            "{\"resolvedQuestion\":42,\"searchTasks\":[\"任务\"]}",
            "{\"searchTasks\":[\"任务\"]}",
            "{\"resolvedQuestion\":\"问题\"}",
            "{\"resolvedQuestion\":\"问题\",\"searchTasks\":\"任务\"}",
            "{\"resolvedQuestion\":\"问题\",\"searchTasks\":[\" \"]}",
            "{\"resolvedQuestion\":\"问题\",\"searchTasks\":[42]}",
            "{\"resolvedQuestion\":null,\"searchTasks\":[\"任务\"]}",
            "{\"resolvedQuestion\":\"问题\",\"searchTasks\":[null]}"
    })
    void shouldRejectMalformedSearchPlans(String output) {
        when(chatModel.call(any(Prompt.class))).thenReturn(response(output));
        assertThrows(IllegalStateException.class, () -> judge().planSearch("问题", List.of()));
    }

    @Test
    void shouldAcceptEnoughEvidenceAndIncludeSourceInformation() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("{\"enough\":true}"));

        assertTrue(judge().isSufficient("支持什么格式？", List.of(),
                List.of(new Evidence(1L, "资料", "note", null, 0, "支持 TXT。", 0.9))));
        verify(chatModel).call(argThat((Prompt prompt) ->
                prompt.getUserMessage().getText().contains("来源：资料")
                        && prompt.getUserMessage().getText().contains("文档 ID：1，片段编号：0")
                        && prompt.getUserMessage().getText().contains("相关度：0.90")
                        && prompt.getUserMessage().getText().contains("内容：支持 TXT。")));
    }

    @ParameterizedTest
    @CsvSource({
            "CPU 和 GPU 有什么区别？, CPU 是运算和控制核心。GPU 用于图形处理和大模型并行计算。",
            "HTTP 和 HTTPS 有什么区别？, HTTPS 在 HTTP 基础上增加 TLS 加密，防止窃听或篡改。"
    })
    void shouldIncludeBasicComparisonRulesAndActualEvidence(String question, String content) {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("{\"enough\":true}"));

        assertTrue(judge().isSufficient(question, List.of(),
                List.of(new Evidence(41L, "计算机常识速成.md", "md", null, 6, content, 0.94))));

        verify(chatModel).call(argThat((Prompt prompt) ->
                prompt.getSystemMessage().getText().contains("比较问题是一个主题")
                        && prompt.getSystemMessage().getText().contains("就足以回答基本区别")
                        && prompt.getSystemMessage().getText().contains("不同片段可以共同提供依据")
                        && prompt.getUserMessage().getText().contains("当前问题：\n" + question)
                        && prompt.getUserMessage().getText().contains("内容：" + content)));
    }

    @Test
    void shouldKeepInsufficientDecisionForKeywordOnlyEvidenceEvenWithHighScore() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("{\"enough\":false}"));

        assertFalse(judge().isSufficient("HTTP 与 HTTPS 有什么区别？", List.of(),
                List.of(new Evidence(41L, "目录", "md", null, 0, "目录：HTTP、HTTPS", 0.99))));

        verify(chatModel).call(argThat((Prompt prompt) ->
                prompt.getSystemMessage().getText().contains("只列名词没有解释")
                        && prompt.getSystemMessage().getText().contains("分数不能代替内容判断")
                        && prompt.getUserMessage().getText().contains("内容：目录：HTTP、HTTPS")));
    }

    @Test
    void shouldReturnFalseForInsufficientEvidence() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("{\"enough\":false}"));

        assertFalse(judge().isSufficient("问题", List.of(), List.of()));
    }

    @Test
    void shouldRejectOldRewriteDecisionDuringEvidenceJudgment() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("REWRITE:另一个问题"));

        assertThrows(IllegalStateException.class,
                () -> judge().isSufficient("问题", List.of(), List.of()));
    }

    private SiliconFlowQuestionJudge judge() {
        return new SiliconFlowQuestionJudge(chatModel);
    }

    @Test
    void shouldExcludeRefusedAnswersFromPlanningHistory() {
        var history = List.of(
                new ChatMessage(MessageRole.USER, "请介绍事务", false, List.of(), LocalDateTime.now()),
                new ChatMessage(MessageRole.ASSISTANT, "历史拒答内容", true, List.of(), LocalDateTime.now()));
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            assertTrue(prompt.getUserMessage().getText().contains("请介绍事务"));
            assertFalse(prompt.getUserMessage().getText().contains("历史拒答内容"));
            return response("""
                    {"resolvedQuestion":"事务如何保证一致性？","searchTasks":["事务如何保证一致性？"]}
                    """);
        });
        SearchPlan plan = judge().planSearch("它如何保证一致性？", history);
        assertEquals("事务如何保证一致性？", plan.resolvedQuestion());
        assertEquals(List.of("事务如何保证一致性？"), plan.searchTasks());
    }

    @Test
    void shouldJudgeResolvedTasksWithoutConversationHistoryAndAllowPartialAnswers() {
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            assertTrue(prompt.getUserMessage().getText().contains("HTTP 与 HTTPS 的传输安全有什么区别？"));
            assertFalse(prompt.getUserMessage().getText().contains("会话历史"));
            assertTrue(prompt.getSystemMessage().getText().contains("允许回答有依据的部分"));
            assertTrue(prompt.getSystemMessage().getText().contains("原文彼此冲突时"));
            var options = assertInstanceOf(OpenAiChatOptions.class, prompt.getOptions());
            assertEquals(32, options.getMaxTokens());
            assertEquals("test-model", options.getModel());
            assertEquals(ResponseFormat.Type.JSON_OBJECT, options.getResponseFormat().getType());
            return response("{\"enough\":true}");
        });
        assertTrue(judge().isSufficient("它们在安全上有什么区别？",
                List.of("HTTP 与 HTTPS 的传输安全有什么区别？"),
                List.of(new Evidence(1L, "网络资料", "md", null, 0, "HTTPS 增加 TLS 加密。", 0.9))));
    }

    @BeforeEach
    void modelOptions() {
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder()
                .model("test-model").maxTokens(1024).build());
    }

    @Test
    void shouldRejectMissingOrNonBooleanDecisionWithoutGuessing() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("{}"), response("{\"enough\":\"true\"}"));
        assertThrows(IllegalStateException.class, () -> judge().isSufficient("问题", List.of(), List.of()));
        assertThrows(IllegalStateException.class, () -> judge().isSufficient("问题", List.of(), List.of()));
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
