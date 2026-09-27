package com.ithwx.personalknowledgebase.practice.domain;

import java.util.List;

public interface QuestionGenerator {

    GeneratedQuestion generate(String topic, List<PracticeSource> sources);

    default GeneratedQuestion generateRetry(
            String topic,
            String previousQuestion,
            List<PracticeSource> sources
    ) {
        return generate(topic, sources);
    }
}
