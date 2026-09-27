package com.ithwx.personalknowledgebase.controller.dto;

import com.ithwx.personalknowledgebase.practice.domain.PracticeSession;
import com.ithwx.personalknowledgebase.practice.domain.PracticeSource;

import java.time.LocalDateTime;
import java.util.List;

public record PracticeResultResponse(
        Long practiceId,
        String topic,
        String question,
        String userAnswer,
        String referenceAnswer,
        Integer score,
        String feedback,
        Long retryOfId,
        boolean needsReview,
        String status,
        List<PracticeSource> sources,
        LocalDateTime createdAt
) {
    public static PracticeResultResponse from(PracticeSession session) {
        return new PracticeResultResponse(
                session.id(),
                session.topic(),
                session.question(),
                session.userAnswer(),
                session.referenceAnswer(),
                session.score(),
                session.feedback(),
                session.retryOfId(),
                session.needsReview(),
                session.status().name(),
                session.sources(),
                session.createdAt()
        );
    }
}
