package com.ithwx.personalknowledgebase.practice.domain;

import java.time.LocalDateTime;
import java.util.List;

public class PracticeSession {

    private static final int PASSING_SCORE = 60;

    private final Long id;
    private final String topic;
    private final String question;
    private final String referenceAnswer;
    private final String evidenceSnapshot;
    private final List<PracticeSource> sources;
    private final LocalDateTime createdAt;
    private String userAnswer;
    private Integer score;
    private String feedback;
    private boolean needsReview;
    private PracticeStatus status;
    private final Long retryOfId;

    public PracticeSession(
            Long id,
            String topic,
            String question,
            String referenceAnswer,
            String evidenceSnapshot,
            List<PracticeSource> sources,
            String userAnswer,
            Integer score,
            String feedback,
            boolean needsReview,
            PracticeStatus status,
            LocalDateTime createdAt,
            Long retryOfId
    ) {
        this.id = id;
        this.topic = topic;
        this.question = question;
        this.referenceAnswer = referenceAnswer;
        this.evidenceSnapshot = evidenceSnapshot;
        this.sources = List.copyOf(sources);
        this.userAnswer = userAnswer;
        this.score = score;
        this.feedback = feedback;
        this.needsReview = needsReview;
        this.status = status;
        this.createdAt = createdAt;
        this.retryOfId = retryOfId;
    }

    public static PracticeSession start(
            String topic,
            GeneratedQuestion generatedQuestion,
            String evidenceSnapshot,
            List<PracticeSource> sources
    ) {
        return new PracticeSession(
                null,
                topic.strip(),
                generatedQuestion.question(),
                generatedQuestion.referenceAnswer(),
                evidenceSnapshot,
                sources,
                null,
                null,
                null,
                false,
                PracticeStatus.WAITING_FOR_ANSWER,
                LocalDateTime.now(),
                null
        );
    }

    public static PracticeSession retryOf(
            PracticeSession original,
            GeneratedQuestion generatedQuestion
    ) {
        if (original.id == null) {
            throw new IllegalArgumentException("原错题必须已经保存");
        }
        if (original.status != PracticeStatus.COMPLETED || !original.needsReview) {
            throw new IllegalArgumentException("只有未掌握的错题可以重新练习");
        }
        Long rootPracticeId = original.retryOfId == null
                ? original.id
                : original.retryOfId;
        return new PracticeSession(
                null,
                original.topic,
                generatedQuestion.question(),
                generatedQuestion.referenceAnswer(),
                original.evidenceSnapshot,
                original.sources,
                null,
                null,
                null,
                false,
                PracticeStatus.WAITING_FOR_ANSWER,
                LocalDateTime.now(),
                rootPracticeId
        );
    }

    public void complete(String answer, PracticeEvaluation evaluation) {
        if (status != PracticeStatus.WAITING_FOR_ANSWER) {
            throw new IllegalArgumentException("这道练习已经提交过答案");
        }
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("练习答案不能为空");
        }
        userAnswer = answer.strip();
        score = evaluation.score();
        feedback = evaluation.feedback();
        needsReview = score < PASSING_SCORE;
        status = PracticeStatus.COMPLETED;
    }

    public void markReviewResolved() {
        if (status != PracticeStatus.COMPLETED) {
            throw new IllegalArgumentException("未完成的练习不能标记为已掌握");
        }
        needsReview = false;
    }

    public Long id() {
        return id;
    }

    public String topic() {
        return topic;
    }

    public String question() {
        return question;
    }

    public String referenceAnswer() {
        return referenceAnswer;
    }

    public String evidenceSnapshot() {
        return evidenceSnapshot;
    }

    public List<PracticeSource> sources() {
        return sources;
    }

    public String userAnswer() {
        return userAnswer;
    }

    public Integer score() {
        return score;
    }

    public String feedback() {
        return feedback;
    }

    public boolean needsReview() {
        return needsReview;
    }

    public PracticeStatus status() {
        return status;
    }

    public LocalDateTime createdAt() {
        return createdAt;
    }

    public Long retryOfId() {
        return retryOfId;
    }
}
