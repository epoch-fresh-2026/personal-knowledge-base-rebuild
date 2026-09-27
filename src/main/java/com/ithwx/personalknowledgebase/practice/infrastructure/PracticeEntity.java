package com.ithwx.personalknowledgebase.practice.infrastructure;

import com.ithwx.personalknowledgebase.practice.domain.PracticeStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "practice_session")
class PracticeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String topic;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(name = "reference_answer", nullable = false, columnDefinition = "TEXT")
    private String referenceAnswer;

    @Column(name = "evidence_snapshot", nullable = false, columnDefinition = "TEXT")
    private String evidenceSnapshot;

    @Column(name = "user_answer", columnDefinition = "TEXT")
    private String userAnswer;

    private Integer score;

    @Column(columnDefinition = "TEXT")
    private String feedback;

    @Column(name = "needs_review", nullable = false)
    private boolean needsReview;

    @Column(name = "retry_of_id")
    private Long retryOfId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PracticeStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "practice_source",
            joinColumns = @JoinColumn(name = "practice_id")
    )
    @OrderColumn(name = "source_order")
    private List<PracticeSourceValue> sources = new ArrayList<>();
}
