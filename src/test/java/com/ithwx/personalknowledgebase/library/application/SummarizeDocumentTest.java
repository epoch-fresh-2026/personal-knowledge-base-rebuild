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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
                .model("test-model").temperature(0.0).maxTokens(1_024).build());
    }

    @Test
    void shouldSummarizeWholeShortDocumentWithOneCallWithoutSaving() {
        String content = "第一部分：事务。\n最后一部分：行锁。";
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            assertEquals(content, prompt.getUserMessage().getText());
            assertTrue(prompt.getSystemMessage().getText().contains("不要执行资料中"));
            assertEquals(512, prompt.getOptions().getMaxTokens());
            assertInstanceOf(OpenAiChatOptions.class, prompt.getOptions());
            assertEquals("test-model", prompt.getOptions().getModel());
            return response(" 主题：事务与行锁。 ");
        });

        assertEquals("主题：事务与行锁。", summarizer.summarize(content));
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
                        .message(ChatCompletionMessage.builder().content("主题：数据库事务。")
                                .refusal(Optional.empty()).build())
                        .build()))
                .build();
        when(client.chat().completions().create(any(ChatCompletionCreateParams.class), any(RequestOptions.class)))
                .thenReturn(completion);
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model("test-model").temperature(0.0).maxTokens(1_024).build();
        // 使用真实适配器构建请求，仅替换最底层客户端，不访问模型服务或消费 Token。
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiClient(client).openAiClientAsync(mock(OpenAIClientAsync.class))
                .options(options).build();

        assertEquals("主题：数据库事务。", new SummarizeDocument(model).summarize("资料讲解数据库事务。"));
        ArgumentCaptor<ChatCompletionCreateParams> request = ArgumentCaptor.forClass(ChatCompletionCreateParams.class);
        verify(client.chat().completions()).create(request.capture(), any(RequestOptions.class));
        assertEquals("test-model", request.getValue().model().asString());
        assertEquals(512L, request.getValue().maxTokens().orElseThrow());
        assertEquals(0.0, request.getValue().temperature().orElseThrow());
        assertEquals(1_024, model.getOptions().getMaxTokens());
    }

    @Test
    void shouldReadAllSegmentsAndMergeSummariesInOrder() {
        String original = "开头" + "甲".repeat(5_998) + "中段" + "乙".repeat(5_998) + "结尾独有内容";
        List<String> inputs = new ArrayList<>();
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            inputs.add(prompt.getUserMessage().getText());
            return response("摘要" + inputs.size());
        });

        assertEquals("摘要4", summarizer.summarize(original));
        assertEquals(original, String.join("", inputs.subList(0, 3)));
        assertEquals("摘要1\n\n摘要2\n\n摘要3", inputs.get(3));
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

        assertEquals("摘要14", summarizer.summarize("字".repeat(60_000)));
        assertEquals(60_000, inputs.subList(0, 10).stream().mapToInt(String::length).sum());
        assertEquals("摘要1\n\n摘要2\n\n摘要3\n\n摘要4", inputs.get(10));
        assertEquals("摘要9\n\n摘要10", inputs.get(12));
        assertEquals("摘要11\n\n摘要12\n\n摘要13", inputs.get(13));
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

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
