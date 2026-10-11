package com.ithwx.personalknowledgebase.library.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentTest {

    @Test
    void shouldPrepareAndStartProcessing() {
        Document document = new Document();
        document.setContentHash("old-hash");
        document.setChunkCount(3);
        document.setFailureReason("旧错误");
        document.completeSummary("旧摘要");

        document.prepareForProcessing();

        assertEquals("PENDING", document.getStatus());
        assertEquals(0, document.getChunkCount());
        assertNull(document.getContentHash());
        assertNull(document.getFailureReason());
        assertNull(document.getSummary());
        assertEquals("PENDING", document.getSummaryStatus());

        document.startProcessing();
        assertEquals("PROCESSING", document.getStatus());
    }

    @Test
    void shouldCompleteOrFailProcessing() {
        Document document = new Document();

        document.markReady(4);

        assertEquals("READY", document.getStatus());
        assertEquals(4, document.getChunkCount());
        assertNull(document.getFailureReason());

        document.markFailed("向量服务失败");
        assertEquals("FAILED", document.getStatus());
        assertEquals("向量服务失败", document.getFailureReason());
    }

    @Test
    void shouldOnlyRetryFailedDocument() {
        Document document = new Document();
        document.prepareForProcessing();
        assertFalse(document.canRetry());

        document.markFailed("处理失败");
        assertTrue(document.canRetry());
    }
}
