package com.ithwx.personalknowledgebase.controller.dto;

import com.ithwx.personalknowledgebase.library.domain.Document;

import java.time.LocalDateTime;

public record DocumentResponse(
        Long id,
        Long version,
        String name,
        String fileType,
        String sourceUrl,
        String status,
        Integer chunkCount,
        String failureReason,
        String summaryStatus,
        LocalDateTime uploadTime
) {
    public static DocumentResponse from(Document document) {
        return new DocumentResponse(
                document.getId(),
                document.getVersion(),
                document.getName(),
                document.getFileType(),
                document.getSourceUrl(),
                document.getStatus(),
                document.getChunkCount(),
                document.getFailureReason(),
                document.getSummaryStatus(),
                document.getUploadTime()
        );
    }
}
