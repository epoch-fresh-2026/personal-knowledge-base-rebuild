package com.ithwx.personalknowledgebase.practice.infrastructure;

import com.ithwx.personalknowledgebase.practice.domain.PracticeRepository;
import com.ithwx.personalknowledgebase.practice.domain.PracticeSession;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Repository
public class JpaPracticeRepositoryAdapter implements PracticeRepository {

    private final SpringDataPracticeRepository jpaRepository;

    public JpaPracticeRepositoryAdapter(SpringDataPracticeRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public PracticeSession save(PracticeSession session) {
        PracticeEntity entity = session.id() == null
                ? new PracticeEntity()
                : jpaRepository.findById(session.id())
                .orElseThrow(() -> new NoSuchElementException(
                        "练习不存在：" + session.id()));
        copy(session, entity);
        return toDomain(jpaRepository.saveAndFlush(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PracticeSession> findById(Long id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PracticeSession> findNeedsReview() {
        return jpaRepository.findAllByNeedsReviewTrueAndRetryOfIdIsNullOrderByCreatedAtDesc()
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private void copy(PracticeSession session, PracticeEntity entity) {
        entity.setTopic(session.topic());
        entity.setQuestion(session.question());
        entity.setReferenceAnswer(session.referenceAnswer());
        entity.setEvidenceSnapshot(session.evidenceSnapshot());
        entity.setUserAnswer(session.userAnswer());
        entity.setScore(session.score());
        entity.setFeedback(session.feedback());
        entity.setNeedsReview(session.needsReview());
        entity.setRetryOfId(session.retryOfId());
        entity.setStatus(session.status());
        entity.setCreatedAt(session.createdAt());
        entity.setSources(new ArrayList<>(session.sources().stream()
                .map(PracticeSourceValue::from)
                .toList()));
    }

    private PracticeSession toDomain(PracticeEntity entity) {
        return new PracticeSession(
                entity.getId(),
                entity.getTopic(),
                entity.getQuestion(),
                entity.getReferenceAnswer(),
                entity.getEvidenceSnapshot(),
                entity.getSources().stream().map(PracticeSourceValue::toDomain).toList(),
                entity.getUserAnswer(),
                entity.getScore(),
                entity.getFeedback(),
                entity.isNeedsReview(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getRetryOfId()
        );
    }
}
