package com.ithwx.personalknowledgebase.qa.application;

import com.ithwx.personalknowledgebase.index.application.SearchKnowledge;
import com.ithwx.personalknowledgebase.index.domain.SearchResult;
import com.ithwx.personalknowledgebase.qa.domain.AnswerGenerator;
import com.ithwx.personalknowledgebase.qa.domain.AnswerSource;
import com.ithwx.personalknowledgebase.qa.domain.ChatMessage;
import com.ithwx.personalknowledgebase.qa.domain.Evidence;
import com.ithwx.personalknowledgebase.qa.domain.QuestionJudge;
import com.ithwx.personalknowledgebase.qa.domain.RetrievalDecision;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

@Service
public class AnswerQuestion {

    static final String NO_EVIDENCE_MESSAGE = "根据当前知识库资料，暂时无法回答这个问题。";

    private final SearchKnowledge searchKnowledge;
    private final QuestionJudge questionJudge;
    private final AnswerGenerator answerGenerator;
    private final int maxResults;
    private final int maxContextChars;

    public AnswerQuestion(
            SearchKnowledge searchKnowledge,
            QuestionJudge questionJudge,
            AnswerGenerator answerGenerator,
            @Value("${app.rag.top-k}") int maxResults,
            @Value("${app.rag.max-context-chars}") int maxContextChars
    ) {
        this.searchKnowledge = searchKnowledge;
        this.questionJudge = questionJudge;
        this.answerGenerator = answerGenerator;
        this.maxResults = maxResults;
        this.maxContextChars = maxContextChars;
    }

    public ChatAnswer answer(String question, List<ChatMessage> history) {
        return answer(question, history, ignored -> {},
                evidence -> answerGenerator.generate(question, history, evidence));
    }

    public ChatAnswer answerStreaming(String question, List<ChatMessage> history,
                                     Consumer<String> onStatus, Consumer<String> onDelta) {
        ChatAnswer result = answer(question, history, onStatus,
                evidence -> answerGenerator.generateStreaming(question, history, evidence, onDelta));
        if (result.refused()) {
            onDelta.accept(result.answer());
        }
        return result;
    }

    // 普通回答和流式回答共用检索、二次检索与拒答规则。
    private ChatAnswer answer(String question, List<ChatMessage> history,
                              Consumer<String> onStatus, Function<List<Evidence>, String> generate) {
        onStatus.accept("正在检索资料");
        List<Evidence> evidence = search(question);
        onStatus.accept("找到 " + evidence.size() + " 个片段，正在判断证据");
        RetrievalDecision decision = questionJudge.decide(question, history, evidence);
        String rewrittenQuestion = null;
        boolean secondSearchExecuted = false;

        if (!decision.sufficient() && canRetry(question, decision.rewrittenQuestion())) {
            rewrittenQuestion = decision.rewrittenQuestion().strip();
            secondSearchExecuted = true;
            onStatus.accept("证据不足，正在二次检索");
            evidence = merge(evidence, search(rewrittenQuestion));
            onStatus.accept("二次检索完成，正在重新判断证据");
            decision = questionJudge.decide(question, history, evidence);
        }

        if (!decision.sufficient()) {
            onStatus.accept("未找到足够依据，将明确拒答");
            return new ChatAnswer(
                    null, question, NO_EVIDENCE_MESSAGE, true,
                    rewrittenQuestion, secondSearchExecuted, List.of());
        }

        onStatus.accept("正在生成回答");
        String answer = generate.apply(evidence);
        return new ChatAnswer(
                null, question, answer, false,
                rewrittenQuestion, secondSearchExecuted, collectSources(evidence));
    }

    private boolean canRetry(String question, String rewrittenQuestion) {
        return rewrittenQuestion != null
                && !rewrittenQuestion.isBlank()
                && !rewrittenQuestion.strip().equalsIgnoreCase(question);
    }

    private List<Evidence> search(String question) {
        List<Evidence> evidence = new ArrayList<>();
        for (SearchResult result : searchKnowledge.search(question)) {
            var chunk = result.chunk();
            evidence.add(new Evidence(
                    chunk.documentId(),
                    chunk.documentName(),
                    chunk.sourceType(),
                    chunk.sourceUrl(),
                    chunk.chunkIndex(),
                    chunk.text(),
                    result.score()
            ));
        }
        return evidence;
    }

    private List<Evidence> merge(List<Evidence> first, List<Evidence> second) {
        Map<String, Evidence> unique = new LinkedHashMap<>();
        for (Evidence item : first) {
            unique.put(identity(item), item);
        }
        for (Evidence item : second) {
            Evidence existing = unique.get(identity(item));
            if (existing == null || item.score() > existing.score()) {
                unique.put(identity(item), item);
            }
        }

        List<Evidence> sorted = new ArrayList<>(unique.values());
        sorted.sort(Comparator.comparingDouble(Evidence::score).reversed());

        List<Evidence> selected = new ArrayList<>();
        int contextChars = 0;
        for (Evidence item : sorted) {
            if (selected.size() == maxResults
                    || contextChars + item.text().length() > maxContextChars) {
                break;
            }
            selected.add(item);
            contextChars += item.text().length();
        }
        return List.copyOf(selected);
    }

    private String identity(Evidence evidence) {
        return evidence.documentId() + ":" + evidence.chunkIndex();
    }

    private List<AnswerSource> collectSources(List<Evidence> evidence) {
        Map<Long, SourceAccumulator> sources = new LinkedHashMap<>();
        for (Evidence item : evidence) {
            SourceAccumulator source = sources.get(item.documentId());
            if (source == null) {
                source = new SourceAccumulator(item);
                sources.put(item.documentId(), source);
            }
            source.chunkIndexes.add(item.chunkIndex());
        }

        List<AnswerSource> results = new ArrayList<>();
        for (SourceAccumulator source : sources.values()) {
            results.add(source.toAnswerSource());
        }
        return results;
    }

    private static class SourceAccumulator {

        private final Evidence firstEvidence;
        private final List<Integer> chunkIndexes = new ArrayList<>();

        private SourceAccumulator(Evidence firstEvidence) {
            this.firstEvidence = firstEvidence;
        }

        private AnswerSource toAnswerSource() {
            return new AnswerSource(
                    firstEvidence.documentId(),
                    firstEvidence.documentName(),
                    firstEvidence.sourceType(),
                    firstEvidence.sourceUrl(),
                    chunkIndexes
            );
        }
    }
}
