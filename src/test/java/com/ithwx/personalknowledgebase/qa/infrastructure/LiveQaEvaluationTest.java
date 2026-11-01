package com.ithwx.personalknowledgebase.qa.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ithwx.personalknowledgebase.library.domain.DocumentRepository;
import com.ithwx.personalknowledgebase.qa.domain.ChatMessage;
import com.ithwx.personalknowledgebase.qa.domain.Evidence;
import com.ithwx.personalknowledgebase.qa.domain.MessageRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 手动开启才会调用真实模型；使用固定原文，不连接业务数据库或测试检索算法。
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_QA_EVAL", matches = "true")
class LiveQaEvaluationTest {

    @Test
    void evaluatePlanningJudgmentAndAnswersWithFixedEvidence() throws Exception {
        var model = OpenAiChatModel.builder().options(OpenAiChatOptions.builder()
                .apiKey(System.getenv("SILICONFLOW_API_KEY"))
                .baseUrl(System.getenv().getOrDefault("SILICONFLOW_BASE_URL", "https://api.siliconflow.cn/v1"))
                .model(System.getenv().getOrDefault("CHAT_MODEL", "Qwen/Qwen2.5-7B-Instruct"))
                .temperature(0.0).maxTokens(768).timeout(Duration.ofSeconds(60)).maxRetries(0).build()).build();
        DocumentRepository documents = mock(DocumentRepository.class);
        when(documents.findByIds(anyList())).thenReturn(List.of());
        var judge = new SiliconFlowQuestionJudge(model);
        var generator = new SiliconFlowAnswerGenerator(model, documents);
        var json = new ObjectMapper();
        EvaluationCase[] cases;
        try (var input = getClass().getResourceAsStream("/qa/evaluation-cases.json")) {
            cases = json.readValue(input, EvaluationCase[].class);
        }

        int limit = Integer.getInteger("qa.eval.limit", cases.length);
        int repeats = Integer.getInteger("qa.eval.repeat", 1);
        List<EvaluationResult> results = new ArrayList<>();
        Path report = Path.of("target/qa-evaluation.json");
        Files.createDirectories(report.getParent());
        for (EvaluationCase item : List.of(cases).stream().limit(limit).toList()) {
            List<ChatMessage> history = item.history().stream().map(message -> new ChatMessage(
                    MessageRole.valueOf(message.role().toUpperCase(Locale.ROOT)), message.content(),
                    message.refused(), List.of(), LocalDateTime.now())).toList();
            List<Evidence> evidence = IntStream.range(0, item.evidence().size()).mapToObj(index ->
                    new Evidence((long) index + 1, "评测原文" + (index + 1), "note", null,
                            0, item.evidence().get(index), 0.9)).toList();
            for (int repeat = 1; repeat <= repeats; repeat++) {
                long started = System.nanoTime();
                var plan = judge.planSearch(item.question(), history);
                List<String> tasks = plan.searchTasks();
                boolean enough = judge.isSufficient(item.question(), tasks, evidence);
                String answer = enough ? generator.generateStreaming(item.question(), tasks, evidence, ignored -> {}) : "拒答";
                results.add(new EvaluationResult(item.id(), item.type(), repeat, item.question(), tasks,
                        item.expectedEnough(), enough, answer, item.expectedPoints(),
                        Duration.ofNanos(System.nanoTime() - started).toMillis()));
                json.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), results);
                System.out.printf("QA eval %d / repeat %d: expected=%s, actual=%s%n",
                        item.id(), repeat, item.expectedEnough(), enough);
            }
        }
        // 自动检查判答；答案事实、引用和缺口说明需对照参考要点人工核对。
        assertTrue(results.stream().allMatch(item -> item.expectedEnough() == item.actualEnough()),
                "有判答结果不符合预期，请查看 target/qa-evaluation.json");
    }

    record EvaluationCase(int id, String type, String question, List<HistoryMessage> history,
                          List<String> evidence, boolean expectedEnough, List<String> expectedPoints) {}

    record HistoryMessage(String role, String content, boolean refused) {}

    record EvaluationResult(int id, String type, int repeat, String question, List<String> searchTasks,
                            boolean expectedEnough, boolean actualEnough, String answer,
                            List<String> expectedPoints, long elapsedMillis) {}
}
