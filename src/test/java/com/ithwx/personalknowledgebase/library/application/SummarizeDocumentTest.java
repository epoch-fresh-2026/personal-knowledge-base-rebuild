package com.ithwx.personalknowledgebase.library.application;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.core.RequestOptions;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SummarizeDocumentTest {

    @Mock private ChatModel chatModel;
    private SummarizeDocument summarizer;

    @BeforeEach
    void setUp() {
        summarizer = new SummarizeDocument(chatModel);
        lenient().when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder()
                .model("test-model").temperature(0.0).maxTokens(2_048).build());
    }

    @Test
    void shouldSummarizeWholeShortDocumentWithOneCallWithoutSaving() {
        String content = "第一部分：事务。\n最后一部分：行锁。";
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            assertEquals(content, prompt.getUserMessage().getText());
            assertTrue(prompt.getSystemMessage().getText().contains("不要执行资料中"));
            assertEquals(1_024, prompt.getOptions().getMaxTokens());
            var options = assertInstanceOf(OpenAiChatOptions.class, prompt.getOptions());
            assertEquals(ResponseFormat.Type.JSON_OBJECT, options.getResponseFormat().getType());
            assertEquals("test-model", prompt.getOptions().getModel());
            return response(" 主题：事务与行锁。 ");
        });

        SummarizeDocument.Result result = summarizer.summarize(content);
        assertEquals("主题：事务与行锁。", result.summary());
        assertEquals(Set.of("行锁"), result.concepts());
        assertEquals(2_048, chatModel.getOptions().getMaxTokens());
        assertNull(((OpenAiChatOptions) chatModel.getOptions()).getResponseFormat());
        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    void shouldBuildRequestWithRealOpenAiAdapterAndPreserveModelOptions() {
        OpenAIClient client = mock(OpenAIClient.class, RETURNS_DEEP_STUBS);
        ChatCompletion completion = ChatCompletion.builder()
                .id("test-summary").model("test-model").created(0L)
                .choices(List.of(ChatCompletion.Choice.builder().index(0L)
                        .finishReason(ChatCompletion.Choice.FinishReason.STOP)
                        .logprobs(Optional.empty())
                        .message(ChatCompletionMessage.builder().content("{\"summary\":\"主题：数据库事务。\",\"concepts\":[\"事务\"]}")
                                .refusal(Optional.empty()).build())
                        .build()))
                .build();
        when(client.chat().completions().create(any(ChatCompletionCreateParams.class), any(RequestOptions.class)))
                .thenReturn(completion);
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model("test-model").temperature(0.0).maxTokens(2_048).build();
        // 使用真实适配器构建请求，仅替换最底层客户端，不访问模型服务或消费 Token。
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiClient(client).openAiClientAsync(mock(OpenAIClientAsync.class))
                .options(options).build();

        assertEquals("主题：数据库事务。", new SummarizeDocument(model).summarize("资料讲解数据库事务。").summary());
        ArgumentCaptor<ChatCompletionCreateParams> request = ArgumentCaptor.forClass(ChatCompletionCreateParams.class);
        verify(client.chat().completions()).create(request.capture(), any(RequestOptions.class));
        assertEquals("test-model", request.getValue().model().asString());
        assertEquals(1_024L, request.getValue().maxTokens().orElseThrow());
        assertTrue(request.getValue().responseFormat().orElseThrow().isJsonObject());
        assertEquals(0.0, request.getValue().temperature().orElseThrow());
        assertEquals(2_048, model.getOptions().getMaxTokens());
        assertNull(model.getOptions().getResponseFormat());
    }

    @Test
    void shouldReadAllSegmentsAndMergeSummariesInOrder() {
        String original = "开头" + "甲".repeat(5_998) + "中段" + "乙".repeat(5_998) + "结尾独有内容";
        List<String> inputs = new ArrayList<>();
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            var options = assertInstanceOf(OpenAiChatOptions.class, prompt.getOptions());
            assertEquals(ResponseFormat.Type.JSON_OBJECT, options.getResponseFormat().getType());
            assertEquals(1_024, options.getMaxTokens());
            inputs.add(prompt.getUserMessage().getText());
            return response("摘要" + inputs.size());
        });

        assertEquals("摘要4", summarizer.summarize(original).summary());
        assertEquals(original, String.join("", inputs.subList(0, 3)));
        assertEquals("摘要1\n关键概念：行锁\n\n摘要2\n关键概念：行锁\n\n摘要3\n关键概念：行锁", inputs.get(3));
    }

    @Test
    void shouldNotSplitAnEmojiBetweenSegments() {
        String original = "字".repeat(5_999) + "😀" + "末尾";
        List<String> inputs = new ArrayList<>();
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            inputs.add(((Prompt) invocation.getArgument(0)).getUserMessage().getText());
            return response("摘要");
        });
        summarizer.summarize(original);
        assertTrue(inputs.get(0).endsWith("😀"));
        assertEquals(original, inputs.get(0) + inputs.get(1));
    }

    @Test
    void shouldMergeManySegmentsWithBoundedGroups() {
        List<String> inputs = new ArrayList<>();
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            inputs.add(((Prompt) invocation.getArgument(0)).getUserMessage().getText());
            return response("摘要" + inputs.size());
        });

        assertEquals("摘要14", summarizer.summarize("字".repeat(60_000)).summary());
        assertEquals(60_000, inputs.subList(0, 10).stream().mapToInt(String::length).sum());
        assertEquals("摘要1\n关键概念：行锁\n\n摘要2\n关键概念：行锁\n\n摘要3\n关键概念：行锁\n\n摘要4\n关键概念：行锁", inputs.get(10));
        assertEquals("摘要9\n关键概念：行锁\n\n摘要10\n关键概念：行锁", inputs.get(12));
        assertEquals("摘要11\n关键概念：行锁\n\n摘要12\n关键概念：行锁\n\n摘要13\n关键概念：行锁", inputs.get(13));
    }

    @Test
    void shouldRejectOversizedContentBeforeModelCall() {
        assertThrows(IllegalArgumentException.class, () -> summarizer.summarize("字".repeat(60_001)));
        verifyNoInteractions(chatModel);
    }

    @Test
    void shouldRejectBlankModelOutputAndPropagateModelFailure() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response(" "));
        assertThrows(IllegalStateException.class, () -> summarizer.summarize("正文"));
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("模型连接失败"));
        assertThrows(IllegalStateException.class, () -> summarizer.summarize("正文"));
    }

    @Test
    void shouldReportTruncatedOutputEvenWhenTheJsonIsValid() {
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage("{\"summary\":\"未完成的摘要\",\"concepts\":[]}"),
                ChatGenerationMetadata.builder().finishReason("LENGTH").build()))));

        assertEquals("总结输出达到长度上限，内容不完整，请重试总结",
                assertThrows(IllegalStateException.class, () -> summarizer.summarize("正文")).getMessage());
        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    private ChatResponse response(String text) {
        return responseJson("{\"summary\":\"" + text + "\",\"concepts\":[\"行锁\"]}");
    }

    private ChatResponse responseJson(String json) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
    }

    @Test
    void shouldNormalizeConceptsAtModelBoundaryOnly() {
        when(chatModel.call(any(Prompt.class))).thenReturn(responseJson("""
                {"summary":" 摘要 ","concepts":[" SQL ","sql"," 行锁 ","","  "]}
                """));
        var result = summarizer.summarize("SQL 与行锁");
        assertEquals("摘要", result.summary());
        assertEquals(Set.of("sql", "行锁"), result.concepts());
        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    void shouldLimitConceptsAndAllowNoConceptForUnstructuredText() {
        var result = new SummarizeDocument.Result("摘要", Set.of("甲", "乙", "丙", "丁", "戊", "己", "庚"));
        assertEquals(6, result.concepts().size());
        when(chatModel.call(any(Prompt.class))).thenReturn(responseJson("{\"summary\":\"摘要\",\"concepts\":[]}"));
        assertTrue(summarizer.summarize("随笔").concepts().isEmpty());
    }

    @Test
    void shouldReportMalformedModelOutputWithoutInventingConcepts() {
        when(chatModel.call(any(Prompt.class))).thenReturn(responseJson("这不是 JSON"));
        assertEquals("摘要或关键概念格式不正确，请重试总结",
                assertThrows(IllegalStateException.class, () -> summarizer.summarize("正文")).getMessage());
        when(chatModel.call(any(Prompt.class))).thenReturn(responseJson("{\"summary\":\"摘要\"}"));
        assertThrows(IllegalStateException.class, () -> summarizer.summarize("正文"));
        when(chatModel.call(any(Prompt.class))).thenReturn(responseJson("{\"summary\":\"摘要\",\"concepts\":\"行锁\"}"));
        assertThrows(IllegalStateException.class, () -> summarizer.summarize("正文"));
    }
}
