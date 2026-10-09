package com.ithwx.personalknowledgebase.qa.domain;

import java.util.List;
import java.util.function.Consumer;

public interface AnswerGenerator {

    String generate(
            String question,
            List<ChatMessage> history,
            List<Evidence> evidence
    );

    String generateStreaming(
            String question,
            List<ChatMessage> history,
            List<Evidence> evidence,
            Consumer<String> onDelta
    );
}
