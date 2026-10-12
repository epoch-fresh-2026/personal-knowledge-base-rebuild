package com.ithwx.personalknowledgebase.qa.application;

import com.ithwx.personalknowledgebase.library.domain.DocumentRepository;
import com.ithwx.personalknowledgebase.qa.domain.AnswerSource;
import com.ithwx.personalknowledgebase.qa.domain.Conversation;
import com.ithwx.personalknowledgebase.qa.domain.ConversationRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

@Service
public class ChatService {

    private final ConversationRepository conversationRepository;
    private final AnswerQuestion answerQuestion;
    private final DocumentRepository documents;

    public ChatService(
            ConversationRepository conversationRepository,
            AnswerQuestion answerQuestion,
            DocumentRepository documents
    ) {
        this.conversationRepository = conversationRepository;
        this.answerQuestion = answerQuestion;
        this.documents = documents;
    }

    public ChatAnswer askStreaming(Long conversationId, String question, Long summaryDocumentId,
                                   Consumer<String> onStatus, Consumer<String> onDelta) {
        Conversation conversation = conversationId == null
                ? Conversation.start()
                : getConversation(conversationId);
        String normalizedQuestion = question.strip();
        ChatAnswer result;
        if (summaryDocumentId == null) {
            result = answerQuestion.answerStreaming(normalizedQuestion, conversation.messages(), onStatus, onDelta);
        } else {
            onStatus.accept("正在读取已保存的文档摘要");
            result = readSummary(summaryDocumentId, normalizedQuestion);
            onDelta.accept(result.answer());
        }
        // 只有完整生成成功且尚未观察到取消时，才保存本轮对话。
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("回答已取消");
        }
        return saveAnswer(conversation, normalizedQuestion, result);
    }

    // 明确选择资料时直接读已保存摘要，不检索片段、不再次调用模型。
    private ChatAnswer readSummary(Long documentId, String question) {
        var document = documents.findById(documentId).orElse(null);
        String text;
        List<AnswerSource> sources = List.of();
        if (document == null) {
            text = "这份资料已删除或不存在，请重新选择资料。";
        } else if (!"READY".equals(document.getStatus())) {
            text = "这份资料尚未完成解析与索引，请稍后重试。";
        } else if ("READY".equals(document.getSummaryStatus())) {
            text = "以下是《" + document.getName() + "》已保存的文档摘要（模型生成，请结合原文核对）：\n\n"
                    + document.getSummary();
            sources = List.of(new AnswerSource(document.getId(), document.getName(),
                    document.getFileType(), document.getSourceUrl(), List.of()));
        } else if ("PENDING".equals(document.getSummaryStatus()) || "PROCESSING".equals(document.getSummaryStatus())) {
            text = "这份资料的摘要正在生成，请稍后重试。";
        } else if ("FAILED".equals(document.getSummaryStatus())) {
            text = "这份资料的摘要生成失败，请在资料库中重试总结。";
        } else {
            text = "这份资料还没有摘要，请在资料库中生成。";
        }
        // 空片段编号表示来源是文档摘要，不伪装成原文证据。
        return new ChatAnswer(null, question, text, sources.isEmpty(), List.of(), sources);
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
