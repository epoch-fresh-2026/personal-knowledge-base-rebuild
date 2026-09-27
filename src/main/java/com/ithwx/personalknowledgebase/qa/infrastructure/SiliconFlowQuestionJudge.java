package com.ithwx.personalknowledgebase.qa.infrastructure;

import com.ithwx.personalknowledgebase.qa.domain.ChatMessage;
import com.ithwx.personalknowledgebase.qa.domain.Evidence;
import com.ithwx.personalknowledgebase.qa.domain.MessageRole;
import com.ithwx.personalknowledgebase.qa.domain.QuestionJudge;
import com.ithwx.personalknowledgebase.qa.domain.RetrievalDecision;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SiliconFlowQuestionJudge implements QuestionJudge {

    private static final int MAX_HISTORY_MESSAGES = 6;
    private static final String REWRITE_PREFIX = "REWRITE:";

    private final ChatModel chatModel;

    public SiliconFlowQuestionJudge(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public RetrievalDecision decide(
            String question,
            List<ChatMessage> history,
            List<Evidence> evidence
    ) {
        String prompt = """
                你是知识库检索判断器。请判断检索证据是否包含回答用户问题所需的核心信息。

                判断规则：
                1. 只要证据能够直接回答问题的核心含义，就认为证据足够，不要求内容全面。
                2. 如果证据包含概念定义、作用、特点、步骤或直接示例，应优先判断为足够。
                3. 不要因为证据内容简短、表达方式与问题不同而判断为不足。
                4. 会话历史只用于理解代词和上下文，优先判断当前问题。
                5. 只有证据为空、明显无关，或者完全缺少问题所需信息时，才改写问题。

                如果证据足够，只输出：ENOUGH
                如果证据不足，只输出：REWRITE:适合重新检索的独立问题
                不要回答用户问题，也不要输出其他内容。

                会话历史：
                %s

                用户问题：%s

                检索证据：
                %s
                """.formatted(formatHistory(history), question, formatEvidence(evidence));

        String output = chatModel.call(prompt).strip();
        if (output.equalsIgnoreCase("ENOUGH")) {
            return RetrievalDecision.enough();
        }
        if (output.regionMatches(true, 0, REWRITE_PREFIX, 0, REWRITE_PREFIX.length())) {
            return RetrievalDecision.retry(output.substring(REWRITE_PREFIX.length()).strip());
        }
        throw new IllegalStateException("模型未按要求返回检索决策");
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
        if (evidence.isEmpty()) {
            return "无";
        }
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < evidence.size(); index++) {
            text.append("[证据 ").append(index + 1).append("] ")
                    .append(evidence.get(index).text()).append('\n');
        }
        return text.toString();
    }
}
