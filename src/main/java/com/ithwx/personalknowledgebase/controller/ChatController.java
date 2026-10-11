package com.ithwx.personalknowledgebase.controller;

import com.ithwx.personalknowledgebase.qa.application.ChatService;
import com.ithwx.personalknowledgebase.controller.dto.ConversationResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/{conversationId}")
    public ConversationResponse getConversation(@PathVariable Long conversationId) {
        return ConversationResponse.from(
                chatService.getConversation(conversationId));
    }
}
