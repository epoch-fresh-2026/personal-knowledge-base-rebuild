package com.ithwx.personalknowledgebase.controller;

import com.ithwx.personalknowledgebase.practice.application.PracticeService;
import com.ithwx.personalknowledgebase.practice.domain.PracticeEvaluation;
import com.ithwx.personalknowledgebase.practice.domain.PracticeSession;
import com.ithwx.personalknowledgebase.practice.domain.PracticeSource;
import com.ithwx.personalknowledgebase.practice.domain.PracticeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PracticeControllerTest {

    @Mock
    private PracticeService practiceService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PracticeController(practiceService)).build();
    }

    @Test
    void shouldCreateQuestionWithoutLeakingReferenceAnswer() throws Exception {
        when(practiceService.create("数据库事务")).thenReturn(waitingSession());

        mockMvc.perform(post("/api/practices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"数据库事务\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.practiceId").value(7))
                .andExpect(jsonPath("$.question").value("事务的 ACID 分别是什么？"))
                .andExpect(jsonPath("$.referenceAnswer").doesNotExist());
    }

    @Test
    void shouldReturnResultAfterAnswering() throws Exception {
        PracticeSession completed = waitingSession();
        completed.complete("原子性和一致性", new PracticeEvaluation(50, "还需要补充隔离性和持久性。"));
        when(practiceService.answer(7L, "原子性和一致性")).thenReturn(completed);

        mockMvc.perform(post("/api/practices/7/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"原子性和一致性\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(50))
                .andExpect(jsonPath("$.needsReview").value(true))
                .andExpect(jsonPath("$.referenceAnswer").exists())
                .andExpect(jsonPath("$.sources[0].documentName").value("数据库笔记"));
    }

    @Test
    void shouldValidateBlankTopic() throws Exception {
        mockMvc.perform(post("/api/practices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldListMistakes() throws Exception {
        PracticeSession completed = waitingSession();
        completed.complete("不完整回答", new PracticeEvaluation(40, "需要继续复习。"));
        when(practiceService.mistakes()).thenReturn(List.of(completed));

        mockMvc.perform(get("/api/practices/mistakes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].topic").value("数据库事务"))
                .andExpect(jsonPath("$[0].score").value(40));
    }

    @Test
    void shouldCreateRetryQuestion() throws Exception {
        PracticeSession retry = retrySession();
        when(practiceService.retry(7L)).thenReturn(retry);

        mockMvc.perform(post("/api/practices/7/retry"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.practiceId").value(8))
                .andExpect(jsonPath("$.question").value("事务为什么需要隔离性？"))
                .andExpect(jsonPath("$.referenceAnswer").doesNotExist());
    }

    private PracticeSession waitingSession() {
        return new PracticeSession(
                7L,
                "数据库事务",
                "事务的 ACID 分别是什么？",
                "原子性、一致性、隔离性和持久性。",
                "[资料 1] 数据库笔记\n事务具有 ACID 特性。",
                List.of(new PracticeSource(1L, "数据库笔记", "FILE", null, 0, "事务具有 ACID 特性。")),
                null,
                null,
                null,
                false,
                PracticeStatus.WAITING_FOR_ANSWER,
                LocalDateTime.of(2026, 9, 26, 10, 0),
                null
        );
    }

    private PracticeSession retrySession() {
        return new PracticeSession(
                8L,
                "数据库事务",
                "事务为什么需要隔离性？",
                "隔离性避免并发事务相互干扰。",
                "[资料 1] 数据库笔记\n事务具有 ACID 特性。",
                List.of(new PracticeSource(1L, "数据库笔记", "FILE", null, 0, "事务具有 ACID 特性。")),
                null,
                null,
                null,
                false,
                PracticeStatus.WAITING_FOR_ANSWER,
                LocalDateTime.of(2026, 9, 26, 11, 0),
                7L
        );
    }
}
