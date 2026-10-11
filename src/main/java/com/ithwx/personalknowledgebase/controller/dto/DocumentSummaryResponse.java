package com.ithwx.personalknowledgebase.controller.dto;

import com.ithwx.personalknowledgebase.library.domain.Document;

public record DocumentSummaryResponse(String summary, String status, String failureReason) {
    public static DocumentSummaryResponse from(Document document) {
        return new DocumentSummaryResponse(document.getSummary(), document.getSummaryStatus(),
                document.getSummaryFailureReason());
    }
}
