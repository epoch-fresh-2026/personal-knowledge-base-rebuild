package com.ithwx.personalknowledgebase.practice.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ithwx.personalknowledgebase.practice.domain.GeneratedQuestion;
import com.ithwx.personalknowledgebase.practice.domain.PracticeSource;
import com.ithwx.personalknowledgebase.practice.domain.QuestionGenerator;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SiliconFlowQuestionGenerator implements QuestionGenerator {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public SiliconFlowQuestionGenerator(ChatModel chatModel) {
        this.chatModel = chatModel;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public GeneratedQuestion generate(String topic, List<PracticeSource> sources) {
        return generateQuestion(topic, sources, "");
    }

    @Override
    public GeneratedQuestion generateRetry(
            String topic,
            String previousQuestion,
            List<PracticeSource> sources
    ) {
        String retryInstruction = """

                上一道题：%s
                请换一个角度重新出题，不要生成与上一道完全相同的问题。
                """.formatted(previousQuestion);
        return generateQuestion(topic, sources, retryInstruction);
    }

    private GeneratedQuestion generateQuestion(
            String topic,
            List<PracticeSource> sources,
            String retryInstruction
    ) {
        String prompt = """
                你是基于个人知识库出题的复习助手。
                只能依据给定资料，围绕复习主题生成一道答案明确的简答题。
                题目不要泄露答案，参考答案必须完整且能从资料中找到依据。
                只输出 JSON：{"question":"题目","referenceAnswer":"参考答案"}

                复习主题：%s
                %s

                资料：
                %s
                """.formatted(topic, retryInstruction, formatSources(sources));

        QuestionResponse response = parse(chatModel.call(prompt), QuestionResponse.class);
        return new GeneratedQuestion(response.question(), response.referenceAnswer());
    }

    private String formatSources(List<PracticeSource> sources) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < sources.size(); index++) {
            PracticeSource source = sources.get(index);
            text.append("[资料 ").append(index + 1).append("] ")
                    .append(source.documentName()).append('\n')
                    .append(source.excerpt()).append("\n\n");
        }
        return text.toString();
    }

    private <T> T parse(String output, Class<T> type) {
        if (output == null || output.isBlank()) {
            throw new IllegalStateException("出题模型没有返回内容");
        }
        String json = jsonObject(output);
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception exception) {
            throw new IllegalStateException("出题模型未按要求返回 JSON", exception);
        }
    }

    private String jsonObject(String output) {
        String value = output.strip();
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalStateException("模型返回内容中没有 JSON 对象");
        }
        return value.substring(start, end + 1);
    }

    private record QuestionResponse(String question, String referenceAnswer) {
    }
}
