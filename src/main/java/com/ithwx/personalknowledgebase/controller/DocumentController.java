package com.ithwx.personalknowledgebase.controller;

import com.ithwx.personalknowledgebase.library.application.DocumentService;
import com.ithwx.personalknowledgebase.controller.dto.DocumentSummaryResponse;
import com.ithwx.personalknowledgebase.controller.dto.DocumentDetailResponse;
import com.ithwx.personalknowledgebase.controller.dto.DocumentResponse;
import com.ithwx.personalknowledgebase.controller.dto.DocumentUpdateRequest;
import com.ithwx.personalknowledgebase.controller.dto.LinkCreateRequest;
import com.ithwx.personalknowledgebase.controller.dto.NoteCreateRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentResponse upload(@RequestPart("file") MultipartFile file) throws IOException {
        return DocumentResponse.from(service.submitFile(
                file.getOriginalFilename(), file.getBytes()));
    }

    @PostMapping("/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentResponse createNote(@Valid @RequestBody NoteCreateRequest request) {
        return DocumentResponse.from(service.createNote(
                request.title(), request.content()));
    }

    @PostMapping("/links")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentResponse createLink(@Valid @RequestBody LinkCreateRequest request) {
        return DocumentResponse.from(service.collectWebPage(
                request.url(), request.title()));
    }

    @GetMapping
    public List<DocumentResponse> list() {
        return service.list().stream().map(DocumentResponse::from).toList();
    }

    @GetMapping("/{id}")
    public DocumentDetailResponse detail(@PathVariable Long id) {
        return DocumentDetailResponse.from(service.get(id));
    }

    @GetMapping("/{id}/summary")
    public DocumentSummaryResponse summary(@PathVariable Long id) {
        return DocumentSummaryResponse.from(service.get(id));
    }

    @PostMapping("/{id}/summary")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentSummaryResponse retrySummary(@PathVariable Long id) {
        return DocumentSummaryResponse.from(service.retrySummary(id));
    }

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DocumentResponse update(
            @PathVariable Long id,
            @Valid @RequestBody DocumentUpdateRequest request
    ) {
        return DocumentResponse.from(service.update(id, request.name(), request.content()));
    }

    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentResponse replaceFile(
            @PathVariable Long id,
            @RequestPart("file") MultipartFile file
    ) throws IOException {
        return DocumentResponse.from(service.replaceFile(
                id, file.getOriginalFilename(), file.getBytes()));
    }

    @PostMapping("/{id}/retry")
    public DocumentResponse retry(@PathVariable Long id) {
        return DocumentResponse.from(service.retry(id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
