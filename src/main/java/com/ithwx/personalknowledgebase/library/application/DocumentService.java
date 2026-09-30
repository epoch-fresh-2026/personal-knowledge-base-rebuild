package com.ithwx.personalknowledgebase.library.application;

import com.ithwx.personalknowledgebase.library.domain.Document;
import com.ithwx.personalknowledgebase.library.domain.DocumentDeleted;
import com.ithwx.personalknowledgebase.library.domain.DocumentRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
public class DocumentService {

    private static final Set<String> SUPPORTED_FILE_TYPES =
            Set.of("txt", "md", "markdown", "pdf", "docx");

    private final DocumentRepository repository;
    private final FileStorage fileStorage;
    private final ProcessDocument processDocument;
    private final ApplicationEventPublisher eventPublisher;

    public DocumentService(
            DocumentRepository repository,
            FileStorage fileStorage,
            ProcessDocument processDocument,
            ApplicationEventPublisher eventPublisher
    ) {
        this.repository = repository;
        this.fileStorage = fileStorage;
        this.processDocument = processDocument;
        this.eventPublisher = eventPublisher;
    }

    public Document submitFile(
            String filename,
            byte[] content
    ) throws IOException {
        requireContent(content);
        Document document = pending(filename, fileType(filename));
        document.setFilePath(fileStorage.save(filename, content));
        return saveAndProcess(document);
    }

    public Document createNote(
            String title,
            String content
    ) {
        Document document = pending(title, "note");
        document.setContent(content);
        return saveAndProcess(document);
    }

    public Document collectWebPage(
            String url,
            String title
    ) {
        String name = title == null || title.isBlank() ? url.strip() : title.strip();
        Document document = pending(name, "web");
        document.setSourceUrl(url.strip());
        return saveAndProcess(document);
    }

    public List<Document> list() {
        return repository.listNewestFirst();
    }

    public Document get(Long id) {
        return requiredDocument(id);
    }

    public Document update(Long id, String name, String content) {
        Document document = requiredDocument(id);
        document.setName(name.strip());
        document.setContent(content);
        resetProcessingState(document);
        return saveAndProcess(document);
    }

    public Document replaceFile(Long id, String filename, byte[] content) throws IOException {
        requireContent(content);
        Document document = requiredDocument(id);
        document.setName(filename);
        document.setFileType(fileType(filename));
        document.setFilePath(fileStorage.save(filename, content));
        document.setSourceUrl(null);
        document.setContent(null);
        resetProcessingState(document);
        return saveAndProcess(document);
    }

    public void delete(Long id) {
        Document document = requiredDocument(id);
        processDocument.cancel(id);
        repository.delete(document);
        eventPublisher.publishEvent(new DocumentDeleted(id));
    }

    public Document retry(Long id) {
        return processDocument.retry(id);
    }

    private Document pending(String name, String type) {
        Document document = new Document();
        document.setName(name.strip());
        document.setFileType(type);
        document.prepareForProcessing();
        return document;
    }

    private Document saveAndProcess(Document document) {
        Document saved = repository.save(document);
        processDocument.processAsync(saved.getId());
        return saved;
    }

    private Document requiredDocument(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("资料不存在：" + id));
    }

    private void resetProcessingState(Document document) {
        document.prepareForProcessing();
    }

    private void requireContent(byte[] content) {
        if (content.length == 0) {
            throw new IllegalArgumentException("文件内容不能为空");
        }
    }

    private String fileType(String filename) {
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            throw new IllegalArgumentException("无法识别文件格式");
        }
        String type = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!SUPPORTED_FILE_TYPES.contains(type)) {
            throw new IllegalArgumentException("不支持的文件格式：" + type);
        }
        return type;
    }
}
