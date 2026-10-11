package com.ithwx.personalknowledgebase.library.infrastructure;

import com.ithwx.personalknowledgebase.library.domain.Document;
import com.ithwx.personalknowledgebase.library.domain.DocumentRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JpaDocumentRepositoryAdapter implements DocumentRepository {

    private final JpaDocumentRepository jpaRepository;

    public JpaDocumentRepositoryAdapter(JpaDocumentRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public List<Document> listNewestFirst() {
        return jpaRepository.findAllByOrderByUploadTimeDesc().stream()
                .map(this::toDocument)
                .toList();
    }

    @Override
    public List<Document> findByStatuses(List<String> statuses) {
        return jpaRepository.findAllByStatusIn(statuses).stream()
                .map(this::toDocument)
                .toList();
    }

    @Override
    public Optional<Document> findById(Long id) {
        return jpaRepository.findById(id).map(this::toDocument);
    }

    @Override
    public List<Document> findByIds(List<Long> ids) {
        return jpaRepository.findAllById(ids).stream().map(this::toDocument).toList();
    }

    @Override
    public boolean existsByContentHash(String hash) {
        return jpaRepository.existsByContentHash(hash);
    }

    @Override
    public boolean existsOtherWithHash(String hash, Long documentId) {
        return jpaRepository.existsByContentHashAndIdNot(hash, documentId);
    }

    @Override
    public Document save(Document document) {
        return toDocument(jpaRepository.saveAndFlush(toEntity(document)));
    }

    @Override
    public void delete(Document document) {
        jpaRepository.delete(toEntity(document));
    }

    private Document toDocument(DocumentEntity entity) {
        Document document = new Document();
        document.setId(entity.getId());
        document.setVersion(entity.getVersion());
        document.setName(entity.getName());
        document.setFilePath(entity.getFilePath());
        document.setFileType(entity.getFileType());
        document.setSourceUrl(entity.getSourceUrl());
        document.setContentHash(entity.getContentHash());
        document.setContent(entity.getContent());
        document.setSummary(entity.getSummary());
        document.setSummaryStatus(entity.getSummaryStatus());
        document.setSummaryFailureReason(entity.getSummaryFailureReason());
        document.setUploadTime(entity.getUploadTime());
        document.setStatus(entity.getStatus());
        document.setFailureReason(entity.getFailureReason());
        document.setChunkCount(entity.getChunkCount());
        return document;
    }

    private DocumentEntity toEntity(Document document) {
        DocumentEntity entity = new DocumentEntity();
        entity.setId(document.getId());
        entity.setVersion(document.getVersion());
        entity.setName(document.getName());
        entity.setFilePath(document.getFilePath());
        entity.setFileType(document.getFileType());
        entity.setSourceUrl(document.getSourceUrl());
        entity.setContentHash(document.getContentHash());
        entity.setContent(document.getContent());
        entity.setSummary(document.getSummary());
        entity.setSummaryStatus(document.getSummaryStatus());
        entity.setSummaryFailureReason(document.getSummaryFailureReason());
        entity.setUploadTime(document.getUploadTime());
        entity.setStatus(document.getStatus());
        entity.setFailureReason(document.getFailureReason());
        entity.setChunkCount(document.getChunkCount());
        return entity;
    }
}
