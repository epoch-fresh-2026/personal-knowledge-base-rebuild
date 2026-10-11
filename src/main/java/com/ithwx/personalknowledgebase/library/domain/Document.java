package com.ithwx.personalknowledgebase.library.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Set;

@Getter
@Setter
@NoArgsConstructor
public class Document {

    private Long id;
    private Long version;
    private String name;
    private String filePath;
    private String fileType;
    private String sourceUrl;
    private String contentHash;
    private String content;
    private String summary;
    private Set<String> concepts = Set.of();
    private String summaryStatus;
    private String summaryFailureReason;
    private String failureReason;
    private LocalDateTime uploadTime;
    private String status;
    private Integer chunkCount;

    public void prepareForProcessing() {
        contentHash = null;
        status = DocumentStatus.PENDING.name();
        chunkCount = 0;
        failureReason = null;
        prepareSummary();
    }

    public void prepareSummary() {
        summary = null;
        concepts = Set.of();
        summaryStatus = "PENDING";
        summaryFailureReason = null;
    }

    public void startSummary() {
        summaryStatus = "PROCESSING";
        summaryFailureReason = null;
    }

    public void completeSummary(String text) {
        summary = text;
        summaryStatus = "READY";
        summaryFailureReason = null;
    }

    public void failSummary(String reason) {
        summary = null;
        concepts = Set.of();
        summaryStatus = "FAILED";
        summaryFailureReason = reason;
    }

    public void startProcessing() {
        status = DocumentStatus.PROCESSING.name();
        failureReason = null;
    }

    public boolean canRetry() {
        return DocumentStatus.FAILED.name().equals(status);
    }

    public void markReady(int indexedChunkCount) {
        status = DocumentStatus.READY.name();
        chunkCount = indexedChunkCount;
        failureReason = null;
    }

    public void markFailed(String reason) {
        status = DocumentStatus.FAILED.name();
        failureReason = reason;
    }
}
