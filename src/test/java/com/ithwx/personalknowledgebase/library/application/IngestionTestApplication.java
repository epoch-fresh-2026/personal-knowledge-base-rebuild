package com.ithwx.personalknowledgebase.library.application;

import com.ithwx.personalknowledgebase.index.application.IndexDocument;
import com.ithwx.personalknowledgebase.index.infrastructure.PgVectorKnowledgeIndex;
import com.ithwx.personalknowledgebase.index.infrastructure.TextChunker;
import com.ithwx.personalknowledgebase.library.infrastructure.DocumentEntity;
import com.ithwx.personalknowledgebase.library.infrastructure.JpaDocumentRepositoryAdapter;
import com.ithwx.personalknowledgebase.library.infrastructure.JpaIngestionJobRepositoryAdapter;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/** 测试专用上下文：真实持久化和生产处理流程，模型使用确定性本地替身。 */
public final class IngestionTestApplication {
    private IngestionTestApplication() {}

    static ConfigurableApplicationContext open(String url, String username, String password, boolean blockEmbedding) {
        return new SpringApplicationBuilder(Config.class).web(WebApplicationType.NONE).logStartupInfo(false)
                .run("--spring.config.location=classpath:ingestion-test.yaml",
                        "--spring.datasource.url=" + url, "--spring.datasource.username=" + username,
                        "--spring.datasource.password=" + password, "--test.block-embedding=" + blockEmbedding,
                        "--app.ingestion.lease-duration=" + (blockEmbedding ? "2s" : "1m"));
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, TransactionAutoConfiguration.class})
    @EntityScan(basePackageClasses = DocumentEntity.class)
    @EnableJpaRepositories(basePackages = "com.ithwx.personalknowledgebase.library.infrastructure")
    @Import({JpaDocumentRepositoryAdapter.class, JpaIngestionJobRepositoryAdapter.class,
            PgVectorKnowledgeIndex.class, TextChunker.class, IndexDocument.class,
            IngestionCommitter.class, IngestionHeartbeat.class, ProcessDocument.class, DocumentService.class})
    static class Config {
        @Bean
        EmbeddingModel embeddingModel(Environment environment) {
            boolean blocking = environment.getProperty("test.block-embedding", Boolean.class, false);
            return new EmbeddingModel() {
                @Override public EmbeddingResponse call(EmbeddingRequest request) {
                    throw new UnsupportedOperationException("测试不调用外部模型");
                }
                @Override public float[] embed(org.springframework.ai.document.Document document) { return vector(); }
                @Override public float[] embed(String text) { return vector(); }
                @Override public int dimensions() { return 3; }
                @Override public List<float[]> embed(List<String> texts) {
                    if (blocking) {
                        System.out.println("INDEXING_CHECKPOINT");
                        System.out.flush();
                        try {
                            new CountDownLatch(1).await();
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }
                    }
                    return texts.stream().map(text -> vector()).toList();
                }
                private float[] vector() { return new float[]{1, 0, 0}; }
            };
        }

        @Bean
        VectorStore vectorStore(JdbcTemplate jdbc, EmbeddingModel model) {
            return PgVectorStore.builder(jdbc, model).dimensions(3).initializeSchema(true)
                    .indexType(PgVectorStore.PgIndexType.NONE).build();
        }

        @Bean(name = "ingestionTaskExecutor")
        TaskExecutor executor() {
            // 不自动消费；测试显式调用生产 Worker，精确控制并发和故障时机。
            return runnable -> {};
        }

        @Bean(name = "ingestionHeartbeatScheduler", destroyMethod = "shutdownNow")
        ScheduledExecutorService scheduler() {
            ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(2, runnable -> {
                Thread thread = new Thread(runnable, "test-ingestion-heartbeat");
                thread.setDaemon(true);
                return thread;
            });
            scheduler.setRemoveOnCancelPolicy(true);
            return scheduler;
        }

        @Bean
        DocumentExtractor extractor() {
            return document -> new DocumentExtractor.Result(document.getName(), document.getContent(), document.getSourceUrl());
        }

        @Bean
        FileStorage fileStorage() {
            return new FileStorage() {
                @Override public String save(String filename, byte[] content) throws IOException { throw new IOException("测试文件存储失败"); }
                @Override public byte[] read(String path) { throw new UnsupportedOperationException(); }
            };
        }
    }
}
