package com.ithwx.personalknowledgebase.controller.dto;

import com.ithwx.personalknowledgebase.library.domain.Document;

import java.time.LocalDateTime;
import java.util.Set;

public record DocumentDetailResponse(
        Long id,
        Long version,
        String name,
        String fileType,
        String sourceUrl,
        String status,
        Integer chunkCount,
        String failureReason,
        LocalDateTime uploadTime,
        String content,
        Set<String> concepts
) {
    public static DocumentDetailResponse from(Document document) {
        return new DocumentDetailResponse(
                document.getId(),
                document.getVersion(),
                document.getName(),
                document.getFileType(),
                document.getSourceUrl(),
                document.getStatus(),
                document.getChunkCount(),
                document.getFailureReason(),
                document.getUploadTime(),
                document.getContent(),
                document.getConcepts()
        );
    }
}
