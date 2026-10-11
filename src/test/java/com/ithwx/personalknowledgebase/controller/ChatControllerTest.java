package com.ithwx.personalknowledgebase.controller;

import com.ithwx.personalknowledgebase.qa.application.ChatService;
import com.ithwx.personalknowledgebase.qa.domain.Conversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock
    private ChatService chatService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ChatController(chatService))
                .build();
    }

    @Test
    void shouldReturnConversationHistory() throws Exception {
        Conversation conversation = new Conversation(
                7L, LocalDateTime.now(), List.of());
        conversation.addUserMessage("历史问题");
        conversation.addAssistantMessage("历史回答", false, List.of());
        when(chatService.getConversation(7L)).thenReturn(conversation);

        mockMvc.perform(get("/api/chat/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value("历史回答"));
    }

}
