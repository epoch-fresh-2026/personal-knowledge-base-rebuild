package com.ithwx.personalknowledgebase.qa.infrastructure;

import com.ithwx.personalknowledgebase.qa.domain.ChatMessage;
import com.ithwx.personalknowledgebase.qa.domain.Evidence;
import com.ithwx.personalknowledgebase.qa.domain.MessageRole;
import com.ithwx.personalknowledgebase.qa.domain.QuestionJudge;
import com.ithwx.personalknowledgebase.qa.domain.RetrievalDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class SiliconFlowQuestionJudge implements QuestionJudge {

    private static final Logger log = LoggerFactory.getLogger(SiliconFlowQuestionJudge.class);
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
                你是知识库问答系统的证据判断器。

                  你的任务是判断“知识库证据是否能够支持回答当前问题的核心内容”，
                  不是判断证据能否提供完整、全面的答案。

                  判断规则：

                  1. 只要证据与问题主题直接相关，并且包含概念、步骤、方法、原因、
                     示例或学习路线中的任意一种有效信息，就判断为 ENOUGH。

                  2. 对“如何学习”“怎么复习”“有哪些步骤”“为我讲解”等宽泛问题，
                     如果证据中存在相关的知识点列表、学习顺序、推荐资料或操作步骤，
                     就判断为 ENOUGH，不要求证据覆盖所有细节。

                  3. 如果证据只能回答问题的一部分，但这部分内容真实且有用，
                     仍然判断为 ENOUGH，回答模型会说明回答范围。

                  4. 不要因为证据被拆分成多个片段、表达方式与问题不同，
                     或内容不够详细而判断为不足。

                  5. 只有以下情况才允许判断为证据不足：
                     - 没有任何证据；
                     - 证据与当前问题主题无关；
                     - 证据虽然提到相同名词，但没有提供回答问题所需的信息。

                  6. 优先理解用户当前的问题。历史对话只用于理解代词或省略内容，
                     不得用历史问题替代当前问题。

                  判断示例：
                  - 问题询问“有哪些内容”或“如何学习”，证据列出了相关知识点、步骤或学习路线，
                    应判断为 ENOUGH。
                  - 证据只出现相同名词，却没有提供问题需要的定义、方法、步骤或事实，
                    应返回 REWRITE:改写后的独立问题。

                   会话历史：
                   %s

                   当前问题：
                   %s

                   知识库证据：
                   %s

                  输出要求：

                  - 证据足够时，只输出：ENOUGH
                  - 证据不足但可以通过改写问题重新检索时，只输出：
                    REWRITE:改写后的独立问题

                  禁止输出解释、标点、Markdown 或其他内容。
                """.formatted(formatHistory(history), question, formatEvidence(evidence));

        String output = chatModel.call(prompt).strip();
        if (output.equalsIgnoreCase("ENOUGH")) {
            logDecision(evidence, "ENOUGH");
            return RetrievalDecision.enough();
        }
        if (output.regionMatches(true, 0, REWRITE_PREFIX, 0, REWRITE_PREFIX.length())) {
            logDecision(evidence, "REWRITE");
            return RetrievalDecision.retry(output.substring(REWRITE_PREFIX.length()).strip());
        }
        logDecision(evidence, "INVALID");
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
            Evidence item = evidence.get(index);
            text.append("[证据 ").append(index + 1).append("]\n")
                    .append("来源：").append(item.documentName()).append('\n')
                    .append("相关度：")
                    .append(String.format(Locale.ROOT, "%.2f", item.score())).append('\n')
                    .append("内容：").append(item.text()).append('\n');
        }
        return text.toString();
    }

    private void logDecision(List<Evidence> evidence, String decision) {
        double topScore = evidence.stream()
                .mapToDouble(Evidence::score)
                .max()
                .orElse(0.0);
        log.info("Question judge completed: evidenceCount={}, topScore={}, decision={}",
                evidence.size(), String.format(Locale.ROOT, "%.2f", topScore), decision);
    }
}
