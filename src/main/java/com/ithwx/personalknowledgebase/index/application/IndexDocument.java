package com.ithwx.personalknowledgebase.index.application;

import com.ithwx.personalknowledgebase.index.domain.KnowledgeChunk;
import com.ithwx.personalknowledgebase.index.domain.KnowledgeIndex;
import com.ithwx.personalknowledgebase.index.domain.PreparedIndex;
import com.ithwx.personalknowledgebase.index.infrastructure.TextChunker;
import com.ithwx.personalknowledgebase.library.domain.DocumentDeleted;
import com.ithwx.personalknowledgebase.library.domain.DocumentTextReady;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class IndexDocument {

    private final TextChunker textChunker;
    private final KnowledgeIndex knowledgeIndex;

    public IndexDocument(
            TextChunker textChunker,
            KnowledgeIndex knowledgeIndex
    ) {
        this.textChunker = textChunker;
        this.knowledgeIndex = knowledgeIndex;
    }

    public PreparedIndex prepare(DocumentTextReady document) {
        List<String> texts = textChunker.split(document.content());
        return knowledgeIndex.prepare(document.documentId(), toChunks(document, texts));
    }

    public void commit(PreparedIndex prepared) {
        knowledgeIndex.replace(prepared);
    }

    @EventListener
    public void onDocumentDeleted(DocumentDeleted event) {
        knowledgeIndex.delete(event.documentId());
    }

    private List<KnowledgeChunk> toChunks(
            DocumentTextReady event,
            List<String> texts
    ) {
        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (int index = 0; index < texts.size(); index++) {
            chunks.add(new KnowledgeChunk(
                    event.documentId(),
                    event.name(),
                    event.sourceType(),
                    event.sourceUrl(),
                    index,
                    texts.get(index)
            ));
        }
        return chunks;
    }
}
