package com.ithwx.personalknowledgebase.practice.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ithwx.personalknowledgebase.practice.domain.AnswerEvaluator;
import com.ithwx.personalknowledgebase.practice.domain.PracticeEvaluation;
import com.ithwx.personalknowledgebase.practice.domain.PracticeSession;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

@Component
public class SiliconFlowAnswerEvaluator implements AnswerEvaluator {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public SiliconFlowAnswerEvaluator(ChatModel chatModel) {
        this.chatModel = chatModel;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public PracticeEvaluation evaluate(PracticeSession session, String userAnswer) {
        String prompt = """
                你是严格的复习答案评价器。
                只能根据题目、参考答案和知识库证据评价用户答案。
                给出 0 到 100 的整数分数，并用简洁中文说明答对内容、遗漏和改进建议。
                只输出 JSON：{"score":分数,"feedback":"反馈"}

                题目：%s

                参考答案：%s

                用户答案：%s

                知识库证据：
                %s
                """.formatted(
                session.question(),
                session.referenceAnswer(),
                userAnswer,
                session.evidenceSnapshot()
        );

        EvaluationResponse response = parse(chatModel.call(prompt));
        return new PracticeEvaluation(response.score(), response.feedback());
    }

    private EvaluationResponse parse(String output) {
        if (output == null || output.isBlank()) {
            throw new IllegalStateException("评分模型没有返回内容");
        }
        String value = output.strip();
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalStateException("评分模型返回内容中没有 JSON 对象");
        }
        try {
            return objectMapper.readValue(
                    value.substring(start, end + 1), EvaluationResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("评分模型未按要求返回 JSON", exception);
        }
    }

    private record EvaluationResponse(int score, String feedback) {
    }
}
