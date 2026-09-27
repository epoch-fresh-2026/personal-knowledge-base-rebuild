package com.ithwx.personalknowledgebase.practice.application;

import com.ithwx.personalknowledgebase.index.application.SearchKnowledge;
import com.ithwx.personalknowledgebase.index.domain.SearchResult;
import com.ithwx.personalknowledgebase.practice.domain.AnswerEvaluator;
import com.ithwx.personalknowledgebase.practice.domain.GeneratedQuestion;
import com.ithwx.personalknowledgebase.practice.domain.PracticeEvaluation;
import com.ithwx.personalknowledgebase.practice.domain.PracticeRepository;
import com.ithwx.personalknowledgebase.practice.domain.PracticeSession;
import com.ithwx.personalknowledgebase.practice.domain.PracticeSource;
import com.ithwx.personalknowledgebase.practice.domain.QuestionGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class PracticeService {

    private final SearchKnowledge searchKnowledge;
    private final QuestionGenerator questionGenerator;
    private final AnswerEvaluator answerEvaluator;
    private final PracticeRepository practiceRepository;

    public PracticeService(
            SearchKnowledge searchKnowledge,
            QuestionGenerator questionGenerator,
            AnswerEvaluator answerEvaluator,
            PracticeRepository practiceRepository
    ) {
        this.searchKnowledge = searchKnowledge;
        this.questionGenerator = questionGenerator;
        this.answerEvaluator = answerEvaluator;
        this.practiceRepository = practiceRepository;
    }

    public PracticeSession create(String topic) {
        String normalizedTopic = topic.strip();
        List<PracticeSource> sources = toSources(searchKnowledge.search(normalizedTopic));
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("知识库中没有找到与该主题相关的资料");
        }

        GeneratedQuestion generated = questionGenerator.generate(normalizedTopic, sources);
        PracticeSession session = PracticeSession.start(
                normalizedTopic,
                generated,
                evidenceSnapshot(sources),
                sources
        );
        return practiceRepository.save(session);
    }

    @Transactional
    public PracticeSession answer(Long id, String userAnswer) {
        PracticeSession session = requiredSession(id);
        String normalizedAnswer = userAnswer.strip();
        PracticeEvaluation evaluation = answerEvaluator.evaluate(session, normalizedAnswer);
        session.complete(normalizedAnswer, evaluation);
        PracticeSession saved = practiceRepository.save(session);
        if (saved.retryOfId() != null && !saved.needsReview()) {
            PracticeSession original = requiredSession(saved.retryOfId());
            original.markReviewResolved();
            practiceRepository.save(original);
        }
        return saved;
    }

    @Transactional
    public PracticeSession retry(Long id) {
        PracticeSession selected = requiredSession(id);
        PracticeSession original = selected.retryOfId() == null
                ? selected
                : requiredSession(selected.retryOfId());
        if (!original.needsReview()) {
            throw new IllegalArgumentException("该错题已经掌握");
        }
        GeneratedQuestion generated = questionGenerator.generateRetry(
                original.topic(),
                selected.question(),
                original.sources()
        );
        return practiceRepository.save(
                PracticeSession.retryOf(original, generated));
    }

    public List<PracticeSession> mistakes() {
        return practiceRepository.findNeedsReview();
    }

    private PracticeSession requiredSession(Long id) {
        return practiceRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("练习不存在：" + id));
    }

    private List<PracticeSource> toSources(List<SearchResult> results) {
        List<PracticeSource> sources = new ArrayList<>();
        for (SearchResult result : results) {
            var chunk = result.chunk();
            sources.add(new PracticeSource(
                    chunk.documentId(),
                    chunk.documentName(),
                    chunk.sourceType(),
                    chunk.sourceUrl(),
                    chunk.chunkIndex(),
                    chunk.text()
            ));
        }
        return List.copyOf(sources);
    }

    private String evidenceSnapshot(List<PracticeSource> sources) {
        StringBuilder snapshot = new StringBuilder();
        for (int index = 0; index < sources.size(); index++) {
            PracticeSource source = sources.get(index);
            snapshot.append("[资料 ").append(index + 1).append("] ")
                    .append(source.documentName())
                    .append("（片段 ").append(source.chunkIndex()).append("）\n")
                    .append(source.excerpt()).append("\n\n");
        }
        return snapshot.toString().strip();
    }
}
