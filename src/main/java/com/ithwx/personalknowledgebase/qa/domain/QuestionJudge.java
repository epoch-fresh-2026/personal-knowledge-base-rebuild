package com.ithwx.personalknowledgebase.qa.domain;

import java.util.List;

public interface QuestionJudge {

    SearchPlan planSearch(String question, List<ChatMessage> history);

    boolean isSufficient(
            String question,
            List<String> searchTasks,
            List<Evidence> evidence
    );
}
