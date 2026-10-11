package com.ithwx.personalknowledgebase.library.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

interface JpaDocumentRepository extends JpaRepository<DocumentEntity, Long> {
    List<DocumentEntity> findAllByOrderByUploadTimeDesc();
    boolean existsByContentHash(String hash);
    List<DocumentEntity> findAllByStatusIn(List<String> statuses);
    boolean existsByContentHashAndIdNot(String hash, Long id);

    @Query(value = """
            SELECT d.* FROM document d
            JOIN document_concept c ON c.document_id = d.id
            WHERE c.concept IN (:concepts) AND d.id <> :documentId
              AND d.status = 'READY' AND d.summary_status = 'READY'
            GROUP BY d.id
            ORDER BY count(*) DESC, d.id
            LIMIT :limit
            """, nativeQuery = true)
    List<DocumentEntity> findRelated(@Param("documentId") Long documentId,
                                    @Param("concepts") Set<String> concepts, @Param("limit") int limit);
}
