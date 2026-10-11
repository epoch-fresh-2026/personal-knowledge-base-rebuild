package com.ithwx.personalknowledgebase.library.domain;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface DocumentRepository {

    List<Document> listNewestFirst();

    List<Document> findByStatuses(List<String> statuses);

    Optional<Document> findById(Long id);

    List<Document> findByIds(List<Long> ids);

    List<RelatedDocument> findRelated(Long documentId, Set<String> concepts, int limit);

    boolean existsByContentHash(String hash);

    boolean existsOtherWithHash(String hash, Long documentId);

    Document save(Document document);

    void delete(Document document);
}
