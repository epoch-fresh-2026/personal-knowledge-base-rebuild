package com.ithwx.personalknowledgebase.index.domain;

import java.util.List;

public interface KnowledgeIndex {

    List<KnowledgeChunk> search(SearchQuery query);

    PreparedIndex prepare(Long documentId, List<KnowledgeChunk> chunks);

    void replace(PreparedIndex prepared);

    void delete(Long documentId);
}
