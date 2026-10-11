package com.ithwx.personalknowledgebase.controller;

import com.ithwx.personalknowledgebase.common.GlobalExceptionHandler;
import com.ithwx.personalknowledgebase.library.application.DocumentService;
import com.ithwx.personalknowledgebase.library.domain.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class DocumentControllerTest {

    @Mock
    private DocumentService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new DocumentController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void shouldReadSavedSummaryAndQueueRetry() throws Exception {
        Document saved = document(1L, "事务笔记", "note");
        saved.completeSummary("事务保证操作作为一个整体完成。");
        when(service.get(1L)).thenReturn(saved);
        mockMvc.perform(get("/api/documents/1/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("事务保证操作作为一个整体完成。"))
                .andExpect(jsonPath("$.status").value("READY"));

        saved.prepareSummary();
        when(service.retrySummary(1L)).thenReturn(saved);
        mockMvc.perform(post("/api/documents/1/summary"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));

        when(service.retrySummary(2L)).thenThrow(new IllegalArgumentException("请等待资料完成解析与索引后再总结"));
        mockMvc.perform(post("/api/documents/2/summary"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请等待资料完成解析与索引后再总结"));
    }

    @Test
    void shouldUploadFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "资料.txt", "text/plain",
                "正文".getBytes(StandardCharsets.UTF_8)
        );
        when(service.submitFile(
                org.mockito.ArgumentMatchers.eq("资料.txt"),
                any(byte[].class)
        )).thenReturn(document(1L, "资料.txt", "txt"));

        mockMvc.perform(multipart("/api/documents").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.fileType").value("txt"));
    }

    @Test
    void shouldCreateNoteAndLink() throws Exception {
        when(service.createNote("学习笔记", "笔记正文"))
                .thenReturn(document(1L, "学习笔记", "note"));
        when(service.collectWebPage("https://example.com", null))
                .thenReturn(document(2L, "网页标题", "web"));

        mockMvc.perform(post("/api/documents/notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"学习笔记","content":"笔记正文"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("学习笔记"));

        mockMvc.perform(post("/api/documents/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileType").value("web"));
    }

    @Test
    void shouldRejectBlankNote() throws Exception {
        mockMvc.perform(post("/api/documents/notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":" ","content":" "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(service);
    }

    @Test
    void shouldListAndReadDetail() throws Exception {
        Document document = document(1L, "学习笔记", "note");
        document.setContent("完整正文");
        when(service.list()).thenReturn(List.of(document));
        when(service.get(1L)).thenReturn(document);

        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("学习笔记"))
                .andExpect(jsonPath("$[0].content").doesNotExist());

        mockMvc.perform(get("/api/documents/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("完整正文"));
    }

    @Test
    void shouldUpdateDocument() throws Exception {
        Document updated = document(1L, "新名称", "note");
        when(service.update(1L, "新名称", "新正文")).thenReturn(updated);

        mockMvc.perform(put("/api/documents/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"新名称","content":"新正文"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("新名称"));
    }

    @Test
    void shouldReplaceRetryAndDeleteDocument() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "新文件.md", "text/markdown",
                "# 新正文".getBytes(StandardCharsets.UTF_8)
        );
        when(service.replaceFile(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("新文件.md"),
                any(byte[].class)
        )).thenReturn(document(1L, "新文件.md", "md"));
        Document retried = document(1L, "失败资料", "txt");
        retried.setStatus("PENDING");
        when(service.retry(1L)).thenReturn(retried);

        mockMvc.perform(multipart("/api/documents/1")
                        .file(file)
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileType").value("md"));

        mockMvc.perform(post("/api/documents/1/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(delete("/api/documents/1"))
                .andExpect(status().isNoContent());
        verify(service).delete(1L);
    }

    private Document document(Long id, String name, String fileType) {
        Document document = new Document();
        document.setId(id);
        document.setName(name);
        document.setFileType(fileType);
        document.setStatus("READY");
        document.setChunkCount(1);
        document.setUploadTime(LocalDateTime.of(2026, 9, 12, 12, 0));
        return document;
    }
}
