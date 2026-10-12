package com.ithwx.personalknowledgebase.qa.domain;

import java.util.List;

public interface QuestionJudge {

    List<String> planSearch(String question, List<ChatMessage> history);

    boolean isSufficient(
            String question,
            List<ChatMessage> history,
            List<Evidence> evidence
    );
}
