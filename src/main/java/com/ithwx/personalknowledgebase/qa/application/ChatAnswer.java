package com.ithwx.personalknowledgebase.qa.application;

import com.ithwx.personalknowledgebase.qa.domain.AnswerSource;

import java.util.List;

public record ChatAnswer(
        Long conversationId,
        String question,
        String answer,
        boolean refused,
        List<String> searchTasks,
        List<AnswerSource> sources
) {
    public ChatAnswer withConversationId(Long conversationId) {
        return new ChatAnswer(
                conversationId,
                question,
                answer,
                refused,
                searchTasks,
                sources
        );
    }
}
