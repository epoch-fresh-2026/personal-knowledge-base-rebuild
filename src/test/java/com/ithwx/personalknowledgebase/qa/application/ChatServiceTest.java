package com.ithwx.personalknowledgebase.qa.application;

import com.ithwx.personalknowledgebase.library.domain.Document;
import com.ithwx.personalknowledgebase.library.domain.DocumentRepository;
import com.ithwx.personalknowledgebase.qa.domain.AnswerSource;
import com.ithwx.personalknowledgebase.qa.domain.Conversation;
import com.ithwx.personalknowledgebase.qa.domain.ConversationRepository;
import com.ithwx.personalknowledgebase.qa.domain.MessageRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private AnswerQuestion answerQuestion;
    @Mock
    private DocumentRepository documents;

    @Test
    void shouldCreateConversationAndSaveQuestionAndAnswer() {
        ChatService service = new ChatService(conversationRepository, answerQuestion, documents);
        ChatAnswer result = result();
        when(answerQuestion.answerStreaming(eq("支持什么格式？"), anyList(), any(), any())).thenReturn(result);
        when(conversationRepository.save(any(Conversation.class)))
                .thenAnswer(invocation -> {
                    Conversation conversation = invocation.getArgument(0);
                    return new Conversation(
                            10L, conversation.createdAt(), conversation.messages());
                });

        ChatAnswer answer = service.askStreaming(null, "  支持什么格式？  ", null, ignored -> {}, ignored -> {});

        assertEquals(10L, answer.conversationId());
        ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationRepository).save(captor.capture());
        assertEquals(2, captor.getValue().messages().size());
        assertEquals(MessageRole.USER, captor.getValue().messages().get(0).role());
        assertEquals("支持什么格式？", captor.getValue().messages().get(0).content());
        assertEquals(MessageRole.ASSISTANT, captor.getValue().messages().get(1).role());
        assertEquals(1, captor.getValue().messages().get(1).sources().size());
    }

    @Test
    void shouldContinueExistingConversation() {
        ChatService service = new ChatService(conversationRepository, answerQuestion, documents);
        Conversation conversation = new Conversation(
                5L, java.time.LocalDateTime.now(), List.of());
        when(conversationRepository.findById(5L)).thenReturn(Optional.of(conversation));
        when(answerQuestion.answerStreaming(eq("继续提问"), anyList(), any(), any())).thenReturn(result());
        when(conversationRepository.save(conversation)).thenReturn(conversation);

        ChatAnswer answer = service.askStreaming(5L, "继续提问", null, ignored -> {}, ignored -> {});

        assertEquals(5L, answer.conversationId());
        assertEquals(2, conversation.messages().size());
    }

    @Test
    void shouldRejectUnknownConversation() {
        ChatService service = new ChatService(conversationRepository, answerQuestion, documents);
        when(conversationRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(java.util.NoSuchElementException.class,
                () -> service.getConversation(99L));
    }

    @Test
    void shouldSaveStreamingAnswerOnlyAfterGenerationCompletes() {
        ChatService service = new ChatService(conversationRepository, answerQuestion, documents);
        when(answerQuestion.answerStreaming(eq("问题"), anyList(), any(), any()))
                .thenReturn(result());
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(invocation -> {
            Conversation conversation = invocation.getArgument(0);
            return new Conversation(10L, conversation.createdAt(), conversation.messages());
        });

        ChatAnswer answer = service.askStreaming(null, " 问题 ", null, ignored -> {}, ignored -> {});

        assertEquals(10L, answer.conversationId());
        ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationRepository).save(captor.capture());
        assertEquals(2, captor.getValue().messages().size());
        assertEquals(result().answer(), captor.getValue().messages().get(1).content());
    }

    @Test
    void shouldNotSavePartialAnswerWhenGenerationFails() {
        when(answerQuestion.answerStreaming(eq("问题"), anyList(), any(), any()))
                .thenAnswer(invocation -> {
                    Consumer<String> delta = invocation.getArgument(3);
                    delta.accept("半截回答");
                    throw new IllegalStateException("上游失败");
                });
        ChatService service = new ChatService(conversationRepository, answerQuestion, documents);

        assertThrows(IllegalStateException.class,
                () -> service.askStreaming(null, "问题", null, ignored -> {}, ignored -> {}));
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void shouldNotSaveWhenCancellationWasObservedBeforeCommit() {
        when(answerQuestion.answerStreaming(eq("问题"), anyList(), any(), any()))
                .thenAnswer(invocation -> {
                    Thread.currentThread().interrupt();
                    return result();
                });
        try {
            assertThrows(CancellationException.class, () -> new ChatService(conversationRepository, answerQuestion, documents)
                    .askStreaming(null, "问题", null, ignored -> {}, ignored -> {}));
            verify(conversationRepository, never()).save(any());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void shouldReadSelectedSummaryAndSaveItsDocumentSourceWithoutRetrieval() {
        Document document = summaryDocument();
        when(documents.findById(38L)).thenReturn(Optional.of(document));
        when(conversationRepository.save(any())).thenAnswer(invocation -> {
            Conversation conversation = invocation.getArgument(0);
            return new Conversation(10L, conversation.createdAt(), conversation.messages());
        });

        ChatAnswer answer = new ChatService(conversationRepository, answerQuestion, documents)
                .askStreaming(null, "请总结资料《数据库.pdf》", 38L, ignored -> {}, ignored -> {});

        assertEquals(10L, answer.conversationId());
        assertFalse(answer.refused());
        assertTrue(answer.searchTasks().isEmpty());
        assertTrue(answer.answer().contains("已保存的文档摘要（模型生成"));
        assertTrue(answer.answer().contains(document.getSummary()));
        assertEquals(List.of(new AnswerSource(38L, "数据库.pdf", "pdf", null, List.of())), answer.sources());
        ArgumentCaptor<Conversation> saved = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationRepository).save(saved.capture());
        assertEquals(answer.answer(), saved.getValue().messages().get(1).content());
        assertEquals(answer.sources(), saved.getValue().messages().get(1).sources());
        verifyNoInteractions(answerQuestion);
    }

    @Test
    void shouldStreamSavedSummaryAndContinueExistingConversation() {
        Conversation conversation = new Conversation(5L, java.time.LocalDateTime.now(), List.of());
        when(conversationRepository.findById(5L)).thenReturn(Optional.of(conversation));
        when(conversationRepository.save(conversation)).thenReturn(conversation);
        when(documents.findById(38L)).thenReturn(Optional.of(summaryDocument()));
        List<String> statuses = new java.util.ArrayList<>();
        List<String> deltas = new java.util.ArrayList<>();

        ChatAnswer answer = new ChatService(conversationRepository, answerQuestion, documents)
                .askStreaming(5L, "请总结资料《数据库.pdf》", 38L, statuses::add, deltas::add);

        assertEquals(List.of("正在读取已保存的文档摘要"), statuses);
        assertEquals(List.of(answer.answer()), deltas);
        assertEquals(5L, answer.conversationId());
        assertEquals(2, conversation.messages().size());
        verifyNoInteractions(answerQuestion);
    }

    @Test
    void shouldExplainUnavailableSummaryWithoutReturningOldTextOrSources() {
        Document document = summaryDocument();
        when(documents.findById(38L)).thenReturn(Optional.of(document));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ChatService service = new ChatService(conversationRepository, answerQuestion, documents);

        for (String summaryStatus : List.of("PENDING", "PROCESSING", "FAILED", "missing")) {
            document.setSummaryStatus(summaryStatus.equals("missing") ? null : summaryStatus);
            ChatAnswer answer = service.askStreaming(null, "总结资料", 38L, ignored -> {}, ignored -> {});
            assertTrue(answer.refused());
            assertEquals(List.of(), answer.sources());
            assertFalse(answer.answer().contains(document.getSummary()));
            assertTrue(answer.answer().contains("摘要"));
        }
        document.setStatus("PROCESSING");
        document.setSummaryStatus("READY");
        assertTrue(service.askStreaming(null, "总结资料", 38L, ignored -> {}, ignored -> {}).answer().contains("尚未完成解析"));
        when(documents.findById(38L)).thenReturn(Optional.empty());
        assertTrue(service.askStreaming(null, "总结资料", 38L, ignored -> {}, ignored -> {}).answer().contains("已删除或不存在"));
        verifyNoInteractions(answerQuestion);
    }

    @Test
    void shouldNotSaveSummaryWhenStreamWasCancelled() {
        when(documents.findById(38L)).thenReturn(Optional.of(summaryDocument()));
        ChatService service = new ChatService(conversationRepository, answerQuestion, documents);
        assertThrows(CancellationException.class, () -> service.askStreaming(null, "总结资料", 38L,
                ignored -> {}, ignored -> { throw new CancellationException(); }));
        verify(conversationRepository, never()).save(any());
    }

    private Document summaryDocument() {
        Document document = new Document();
        document.setId(38L);
        document.setName("数据库.pdf");
        document.setFileType("pdf");
        document.setStatus("READY");
        document.completeSummary("## 事务与锁\n\n事务保证一致性，行锁控制并发。");
        return document;
    }

    private ChatAnswer result() {
        return new ChatAnswer(
                null,
                "支持什么格式？",
                "支持 TXT。",
                false,
                List.of("支持什么格式？"),
                List.of(new AnswerSource(
                        1L, "项目说明", "note", null, List.of(0)))
        );
    }
}
