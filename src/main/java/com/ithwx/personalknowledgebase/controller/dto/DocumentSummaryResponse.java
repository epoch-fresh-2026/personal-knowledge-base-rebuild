package com.ithwx.personalknowledgebase.controller.dto;

import com.ithwx.personalknowledgebase.library.domain.Document;

public record DocumentSummaryResponse(String summary, String status, String failureReason, String documentStatus) {
    public static DocumentSummaryResponse from(Document document) {
        return new DocumentSummaryResponse(document.getSummary(), document.getSummaryStatus(),
                "FAILED".equals(document.getStatus()) ? document.getFailureReason() : document.getSummaryFailureReason(),
                document.getStatus());
    }
}
