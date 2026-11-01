package com.ithwx.personalknowledgebase.qa.application;

import com.ithwx.personalknowledgebase.index.application.SearchKnowledge;
import com.ithwx.personalknowledgebase.index.domain.KnowledgeChunk;
import com.ithwx.personalknowledgebase.index.domain.SearchResult;
import com.ithwx.personalknowledgebase.qa.domain.AnswerGenerator;
import com.ithwx.personalknowledgebase.qa.domain.AnswerSource;
import com.ithwx.personalknowledgebase.qa.domain.ChatMessage;
import com.ithwx.personalknowledgebase.qa.domain.Evidence;
import com.ithwx.personalknowledgebase.qa.domain.QuestionJudge;
import com.ithwx.personalknowledgebase.qa.domain.SearchPlan;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class AnswerQuestion {

    static final String NO_EVIDENCE_MESSAGE = "根据当前知识库资料，暂时无法回答这个问题。";

    private final SearchKnowledge searchKnowledge;
    private final QuestionJudge questionJudge;
    private final AnswerGenerator answerGenerator;
    private final SearchSubAgent subAgent;

    public AnswerQuestion(
            SearchKnowledge searchKnowledge,
            QuestionJudge questionJudge,
            AnswerGenerator answerGenerator,
            SearchSubAgent subAgent
    ) {
        this.searchKnowledge = searchKnowledge;
        this.questionJudge = questionJudge;
        this.answerGenerator = answerGenerator;
        this.subAgent = subAgent;
    }

    public ChatAnswer answerStreaming(String question, List<ChatMessage> history,
                                     Consumer<String> onStatus, Consumer<String> onDelta) {
        onStatus.accept("正在分析查询任务");
        SearchPlan plan = questionJudge.planSearch(question, history);
        List<String> searchTasks = plan.searchTasks();
        List<KnowledgeChunk> candidates;
        if (searchTasks.size() == 2) {
            onStatus.accept("正在并行查询两个子任务");
            candidates = subAgent.search(searchTasks, onStatus);
        } else {
            onStatus.accept("正在检索资料");
            candidates = searchKnowledge.candidates(searchTasks.get(0));
        }

        // 所有候选按补全指代后的完整问题统一重排，覆盖全部查询意图。
        onStatus.accept("正在筛选原文证据");
        List<Evidence> evidence = toEvidence(searchKnowledge.rerank(plan.resolvedQuestion(), candidates));
        onStatus.accept("找到 " + evidence.size() + " 个片段，正在判断证据");
        // 没有原文就直接拒答；有原文时只传本轮查询，不把历史拒答带入判断。
        if (evidence.isEmpty() || !questionJudge.isSufficient(question, searchTasks, evidence)) {
            onStatus.accept("未找到足够依据，将明确拒答");
            onDelta.accept(NO_EVIDENCE_MESSAGE);
            return new ChatAnswer(
                    null, question, NO_EVIDENCE_MESSAGE, true,
                    searchTasks, List.of());
        }

        onStatus.accept("正在生成回答");
        String answer = answerGenerator.generateStreaming(question, searchTasks, evidence, onDelta);
        return new ChatAnswer(
                null, question, answer, false,
                searchTasks, collectSources(evidence));
    }

    private List<Evidence> toEvidence(List<SearchResult> results) {
        List<Evidence> evidence = new ArrayList<>();
        for (SearchResult result : results) {
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

    private List<AnswerSource> collectSources(List<Evidence> evidence) {
        Map<Long, List<Evidence>> byDocument = new LinkedHashMap<>();
        for (Evidence item : evidence) {
            byDocument.computeIfAbsent(item.documentId(), id -> new ArrayList<>()).add(item);
        }

        List<AnswerSource> sources = new ArrayList<>();
        for (List<Evidence> items : byDocument.values()) {
            Evidence first = items.get(0);
            sources.add(new AnswerSource(first.documentId(), first.documentName(),
                    first.sourceType(), first.sourceUrl(), items.stream().map(Evidence::chunkIndex).toList()));
        }
        return sources;
    }
}
