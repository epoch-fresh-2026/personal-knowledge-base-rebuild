package com.ithwx.personalknowledgebase.qa.application;

import com.ithwx.personalknowledgebase.qa.domain.Conversation;
import com.ithwx.personalknowledgebase.qa.domain.ConversationRepository;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

@Service
public class ChatService {

    private final ConversationRepository conversationRepository;
    private final AnswerQuestion answerQuestion;

    public ChatService(
            ConversationRepository conversationRepository,
            AnswerQuestion answerQuestion
    ) {
        this.conversationRepository = conversationRepository;
        this.answerQuestion = answerQuestion;
    }

    public ChatAnswer ask(Long conversationId, String question) {
        Conversation conversation = conversationId == null
                ? Conversation.start()
                : getConversation(conversationId);
        String normalizedQuestion = question.strip();
        ChatAnswer result = answerQuestion.answer(
                normalizedQuestion, conversation.messages());
        return saveAnswer(conversation, normalizedQuestion, result);
    }

    public ChatAnswer askStreaming(Long conversationId, String question,
                                   Consumer<String> onStatus, Consumer<String> onDelta) {
        Conversation conversation = conversationId == null
                ? Conversation.start()
                : getConversation(conversationId);
        String normalizedQuestion = question.strip();
        ChatAnswer result = answerQuestion.answerStreaming(
                normalizedQuestion, conversation.messages(), onStatus, onDelta);
        // 只有完整生成成功且尚未观察到取消时，才保存本轮对话。
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("回答已取消");
        }
        return saveAnswer(conversation, normalizedQuestion, result);
    }

    private ChatAnswer saveAnswer(Conversation conversation, String normalizedQuestion, ChatAnswer result) {
        conversation.addUserMessage(normalizedQuestion);
        conversation.addAssistantMessage(
                result.answer(), result.refused(), result.sources());
        Conversation saved = conversationRepository.save(conversation);

        return result.withConversationId(saved.id());
    }

    public Conversation getConversation(Long conversationId) {
        return conversationRepository.findById(conversationId)
                .orElseThrow(() -> new NoSuchElementException(
                        "会话不存在：" + conversationId));
    }

}
