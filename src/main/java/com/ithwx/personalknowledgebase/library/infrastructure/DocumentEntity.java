package com.ithwx.personalknowledgebase.library.infrastructure;

import com.ithwx.personalknowledgebase.library.domain.DocumentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "document", uniqueConstraints = @UniqueConstraint(name = "uk_document_content_hash", columnNames = "content_hash"))
public class DocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(nullable = false)
    private String name;

    @Column(name = "file_path", length = 1024)
    private String filePath;

    @Column(name = "file_type", length = 32)
    private String fileType;

    @Column(name = "source_url", length = 2048)
    private String sourceUrl;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "summary_status", length = 32)
    private String summaryStatus;

    @Column(name = "summary_failure_reason", length = 1000)
    private String summaryFailureReason;

    @Column(name = "upload_time", nullable = false)
    private LocalDateTime uploadTime;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "chunk_count", nullable = false)
    private Integer chunkCount;

    @PrePersist
    void initializeDefaults() {
        if (uploadTime == null) {
            uploadTime = LocalDateTime.now();
        }
        if (status == null) {
            status = DocumentStatus.PENDING.name();
        }
        if (chunkCount == null) {
            chunkCount = 0;
        }
    }
}
