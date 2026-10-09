package com.ithwx.personalknowledgebase.qa.infrastructure;

import com.ithwx.personalknowledgebase.qa.domain.AnswerGenerator;
import com.ithwx.personalknowledgebase.qa.domain.ChatMessage;
import com.ithwx.personalknowledgebase.qa.domain.Evidence;
import com.ithwx.personalknowledgebase.qa.domain.MessageRole;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

@Component
public class SiliconFlowAnswerGenerator implements AnswerGenerator {

    private static final int MAX_HISTORY_MESSAGES = 6;

    private final ChatModel chatModel;

    public SiliconFlowAnswerGenerator(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String generate(
            String question,
            List<ChatMessage> history,
            List<Evidence> evidence
    ) {
        return requireAnswer(chatModel.call(prompt(question, history, evidence)));
    }

    @Override
    public String generateStreaming(String question, List<ChatMessage> history,
                                    List<Evidence> evidence, Consumer<String> onDelta) {
        StringBuilder answer = new StringBuilder();
        // 使用模型的真实流式接口；退出时关闭订阅，避免继续消费已取消的回答。
        try (Stream<String> chunks = chatModel.stream(prompt(question, history, evidence)).toStream(1)) {
            chunks.forEachOrdered(text -> {
                if (!text.isEmpty()) {
                    onDelta.accept(text);
                    answer.append(text);
                }
            });
        }
        return requireAnswer(answer.toString());
    }

    private String prompt(String question, List<ChatMessage> history, List<Evidence> evidence) {
        return """
                只能依据下面的知识库证据回答问题，不得编造信息。
                使用 [证据 1] 这样的编号标明依据。

                会话历史：
                %s

                用户问题：%s

                知识库证据：
                %s
                """.formatted(formatHistory(history), question, formatEvidence(evidence));
    }

    private String requireAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            throw new IllegalStateException("回答模型没有返回内容");
        }
        return answer.strip();
    }

    private String formatHistory(List<ChatMessage> history) {
        if (history.isEmpty()) {
            return "无";
        }
        StringBuilder text = new StringBuilder();
        int start = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
        for (int index = start; index < history.size(); index++) {
            ChatMessage message = history.get(index);
            String role = message.role() == MessageRole.USER ? "用户" : "助手";
            text.append(role).append("：").append(message.content()).append('\n');
        }
        return text.toString();
    }

    private String formatEvidence(List<Evidence> evidence) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < evidence.size(); index++) {
            text.append("[证据 ").append(index + 1).append("] ")
                    .append(evidence.get(index).text()).append('\n');
        }
        return text.toString();
    }
}
