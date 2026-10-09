package com.ithwx.personalknowledgebase.library.application;

import com.ithwx.personalknowledgebase.index.application.IndexDocument;
import com.ithwx.personalknowledgebase.index.domain.KnowledgeChunk;
import com.ithwx.personalknowledgebase.index.domain.KnowledgeIndex;
import com.ithwx.personalknowledgebase.index.domain.PreparedIndex;
import com.ithwx.personalknowledgebase.index.domain.SearchQuery;
import com.ithwx.personalknowledgebase.library.domain.Document;
import com.ithwx.personalknowledgebase.library.domain.DocumentRepository;
import com.ithwx.personalknowledgebase.library.domain.DocumentTextReady;
import com.ithwx.personalknowledgebase.library.domain.IngestionJob;
import com.ithwx.personalknowledgebase.library.domain.IngestionJobRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(120)
class IngestionSafetyIT {
    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg16")
            .withDatabaseName("ingestion_safety_test");
    private static ConfigurableApplicationContext context;
    private JdbcTemplate jdbc;
    private DocumentService service;
    private DocumentRepository documents;
    private IngestionJobRepository jobs;
    private IngestionCommitter committer;
    private IndexDocument index;
    private TransactionTemplate transaction;
    @TempDir Path processFiles;

    @BeforeAll
    static void startDatabase() {
        postgres.start();
        context = IngestionTestApplication.open(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), false);
    }

    @AfterAll
    static void stopDatabase() {
        if (context != null) {
            context.close();
        }
        postgres.stop();
    }

    @BeforeEach
    void resetTestDatabase() {
        jdbc = context.getBean(JdbcTemplate.class);
        assertEquals(postgres.getJdbcUrl(), context.getEnvironment().getProperty("spring.datasource.url"));
        // 只清理本测试创建的临时数据库，永不读取 .env 或业务数据库地址。
        jdbc.execute("TRUNCATE TABLE ingestion_job, document, vector_store RESTART IDENTITY");
        service = context.getBean(DocumentService.class);
        documents = context.getBean(DocumentRepository.class);
        jobs = context.getBean(IngestionJobRepository.class);
        committer = context.getBean(IngestionCommitter.class);
        index = context.getBean(IndexDocument.class);
        transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    @Test
    void shouldAllowOnlyOneConcurrentClaimForSameJob() throws Exception {
        note("并发任务");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            var first = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return claim("first"); });
            var second = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return claim("second"); });
            long claimed = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
                    .stream().filter(Optional::isPresent).count();
            assertEquals(1, claimed);
            assertEquals(1, jdbc.queryForObject("SELECT attempt_count FROM ingestion_job", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void shouldRequireTransactionForProtectedReadsAndWrites() {
        assertThrows(IllegalTransactionStateException.class, () -> jobs.findOwnedForUpdate(1L, "worker"));
        assertThrows(IllegalTransactionStateException.class, () -> jobs.save(IngestionJob.pending(1L)));
        assertThrows(IllegalTransactionStateException.class, () -> index.commit(new PreparedIndex(1L, List.of())));
    }

    @Test
    void shouldSkipLockedJobAndClaimAnother() throws Exception {
        Document first = note("被锁住");
        Document second = note("可领取");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        var holder = pool.submit(() -> transaction.execute(status -> {
            jdbc.queryForObject("SELECT id FROM ingestion_job WHERE document_id = ? FOR UPDATE", Long.class, first.getId());
            locked.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("测试行锁未及时释放");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return null;
        }));
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            assertEquals(second.getId(), claim("other").orElseThrow().getDocumentId());
        } finally {
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            pool.shutdownNow();
        }
    }

    @Test
    void shouldRejectExpiredWorkerEvenWithoutNewOwner() {
        Document document = note("过期任务");
        IngestionJob job = claim("old").orElseThrow();
        expire(job);
        assertFalse(jobs.renew(job.getId(), "old", Duration.ofSeconds(2)));
        assertTrue(committer.extracted(job.getId(), "old", new DocumentExtractor.Result("迟到", "迟到正文", null)).isEmpty());
        assertFalse(committer.indexed(job.getId(), "old", prepared(document, "迟到索引")));
        committer.fail(job.getId(), "old", "迟到错误");
        assertEquals(document.getContent(), documents.findById(document.getId()).orElseThrow().getContent());
        assertEquals(0, vectorCount());
        assertEquals("RUNNING", status(job));
    }

    @Test
    void shouldPreventStaleWorkerFromOverwritingNewOwner() {
        Document document = note("接手任务");
        IngestionJob old = indexingJob(document, "old");
        expire(old);
        IngestionJob current = claim("new").orElseThrow();
        assertFalse(jobs.renew(old.getId(), "old", Duration.ofSeconds(2)));
        assertFalse(committer.indexed(old.getId(), "old", prepared(document, "旧结果")));
        assertTrue(committer.indexed(current.getId(), "new", prepared(document, "新结果")));
        assertEquals("新结果", jdbc.queryForObject("SELECT content FROM vector_store", String.class));
        assertEquals("READY", documents.findById(document.getId()).orElseThrow().getStatus());
        assertEquals("COMPLETED", status(current));
    }

    @Test
    void shouldRenewLongTaskBeyondOriginalLease() {
        note("长任务");
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime originalDeadline = now.plusSeconds(2);
        IngestionJob job = jobs.claimNext("long", now, originalDeadline).orElseThrow();
        try (var lease = context.getBean(IngestionHeartbeat.class).watch(job.getId(), "long")) {
            await().atMost(Duration.ofSeconds(6)).until(() -> LocalDateTime.now().isAfter(originalDeadline.plusNanos(100_000_000)));
            assertTrue(lease.isValid());
            assertTrue(claim("intruder").isEmpty());
            assertEquals("long", jdbc.queryForObject("SELECT lease_owner FROM ingestion_job", String.class));
        }
    }

    @Test
    void shouldRollbackIndexReplacementAndBothStatusesOnFailure() {
        Document document = note("回滚任务");
        IngestionJob job = indexingJob(document, "worker");
        transaction.executeWithoutResult(status -> index.commit(prepared(document, "原索引")));
        PreparedIndex invalid = new PreparedIndex(document.getId(), List.of(new PreparedIndex.EmbeddedChunk(
                new KnowledgeChunk(document.getId(), "回滚任务", "note", null, 0, "错误维度"), new float[]{1, 0})));
        assertThrows(RuntimeException.class, () -> committer.indexed(job.getId(), "worker", invalid));
        assertEquals("原索引", jdbc.queryForObject("SELECT content FROM vector_store", String.class));
        assertEquals("PROCESSING", documents.findById(document.getId()).orElseThrow().getStatus());
        assertEquals("RUNNING", status(job));
        assertEquals("INDEXING", jdbc.queryForObject("SELECT stage FROM ingestion_job", String.class));
    }

    @Test
    void shouldNotResurrectDeletedDocumentWithLateResult() {
        Document document = note("删除任务");
        IngestionJob job = indexingJob(document, "old");
        transaction.executeWithoutResult(status -> index.commit(prepared(document, "旧索引")));
        service.delete(document.getId());
        assertFalse(committer.indexed(job.getId(), "old", prepared(document, "迟到索引")));
        assertTrue(documents.findById(document.getId()).isEmpty());
        assertEquals(0, vectorCount());
        assertEquals("CANCELLED", status(job));
    }

    @Test
    void shouldSerializeDeletionWithInFlightIndexCommit() throws Exception {
        Document document = note("并发删除");
        IngestionJob job = indexingJob(document, "worker");
        PreparedIndex prepared = prepared(document, "提交中的索引");
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        var commit = pool.submit(() -> transaction.execute(status -> {
            assertTrue(committer.indexed(job.getId(), "worker", prepared));
            written.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("提交未及时释放"); }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return null;
        }));
        try {
            assertTrue(written.await(5, TimeUnit.SECONDS));
            var deletion = pool.submit(() -> service.delete(document.getId()));
            await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock'
                    AND query ILIKE '%ingestion_job%'
                    """, Integer.class) > 0);
            assertFalse(deletion.isDone());
            release.countDown();
            commit.get(5, TimeUnit.SECONDS);
            deletion.get(5, TimeUnit.SECONDS);
            assertTrue(documents.findById(document.getId()).isEmpty());
            assertEquals(0, vectorCount());
            assertEquals("CANCELLED", status(job));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void shouldRollbackInvalidationWhenFileReplacementFails() {
        Document document = note("文件替换失败");
        IngestionJob job = indexingJob(document, "worker");
        assertThrows(IOException.class, () -> service.replaceFile(document.getId(), "new.txt", new byte[]{1}));
        assertEquals("RUNNING", status(job));
        assertEquals("worker", jdbc.queryForObject("SELECT lease_owner FROM ingestion_job", String.class));
        assertEquals(document.getContent(), documents.findById(document.getId()).orElseThrow().getContent());
        assertTrue(committer.indexed(job.getId(), "worker", prepared(document, "原任务结果")));
    }

    @Test
    void shouldInvalidateOldAttemptWhenEditingDocument() {
        Document document = note("编辑任务");
        IngestionJob old = claim("old").orElseThrow();
        committer.start(old.getId(), "old");
        service.update(document.getId(), "新名称", "新正文");
        assertTrue(committer.extracted(old.getId(), "old", new DocumentExtractor.Result("旧名称", "旧正文", null)).isEmpty());
        assertFalse(committer.indexed(old.getId(), "old", prepared(document, "旧索引")));
        context.getBean(ProcessDocument.class).processNext();
        assertEquals("新正文", documents.findById(document.getId()).orElseThrow().getContent());
        assertEquals("新正文", jdbc.queryForObject("SELECT content FROM vector_store", String.class));
        assertEquals("COMPLETED", status(old));
    }

    @Test
    void shouldKeepReplacementIdempotentAndSearchable() {
        Document document = note("检索任务");
        context.getBean(ProcessDocument.class).processNext();
        service.update(document.getId(), "检索任务", document.getContent());
        context.getBean(ProcessDocument.class).processNext();
        assertEquals(1, vectorCount());
        List<KnowledgeChunk> found = context.getBean(KnowledgeIndex.class).search(new SearchQuery("检索任务", 5, 0.0));
        assertEquals(1, found.size());
        assertEquals(document.getId(), found.get(0).documentId());
    }

    @Test
    void shouldRecoverPersistedIndexingCheckpointAfterForcedProcessExit() throws Exception {
        Document document = note("进程恢复");
        try (Child worker = child("crash")) {
            assertTrue(worker.checkpoint.await(45, TimeUnit.SECONDS), worker.output.toString());
            assertEquals("INDEXING", jdbc.queryForObject("SELECT stage FROM ingestion_job", String.class));
            assertEquals(0, vectorCount());
            worker.process.destroyForcibly();
            assertTrue(worker.process.waitFor(10, TimeUnit.SECONDS));
        }
        try (Child restarted = child("recover")) {
            assertTrue(restarted.process.waitFor(45, TimeUnit.SECONDS), restarted.output.toString());
            restarted.reader.get(5, TimeUnit.SECONDS);
            assertEquals(0, restarted.process.exitValue(), restarted.output.toString());
            assertTrue(restarted.output.toString().contains("RECOVERY_COMPLETE"));
        }
        assertEquals("READY", documents.findById(document.getId()).orElseThrow().getStatus());
        assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM ingestion_job", String.class));
        assertEquals(2, jdbc.queryForObject("SELECT attempt_count FROM ingestion_job", Integer.class));
        assertEquals(1, vectorCount());
    }

    private Document note(String name) { return service.createNote(name, "正文：" + name); }
    private Optional<IngestionJob> claim(String owner) {
        LocalDateTime now = LocalDateTime.now();
        return jobs.claimNext(owner, now, now.plusMinutes(1));
    }
    private IngestionJob indexingJob(Document document, String owner) {
        IngestionJob job = claim(owner).orElseThrow();
        committer.start(job.getId(), owner).orElseThrow();
        committer.extracted(job.getId(), owner, new DocumentExtractor.Result(document.getName(), document.getContent(), null)).orElseThrow();
        return job;
    }
    private void expire(IngestionJob job) {
        jdbc.update("UPDATE ingestion_job SET lease_until = TIMESTAMP '2000-01-01 00:00:00' WHERE id = ?", job.getId());
    }
    private String status(IngestionJob job) {
        return jdbc.queryForObject("SELECT status FROM ingestion_job WHERE id = ?", String.class, job.getId());
    }
    private int vectorCount() { return jdbc.queryForObject("SELECT count(*) FROM vector_store", Integer.class); }
    private PreparedIndex prepared(Document document, String text) {
        return index.prepare(new DocumentTextReady(document.getId(), document.getName(), "note", null, text));
    }
    private Child child(String mode) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path args = processFiles.resolve(mode + ".args");
        // 参数文件避免 Windows 的命令行长度限制；内容只有测试容器凭据。
        Files.writeString(args, "-cp\n\"" + classpath.replace('\\', '/') + "\"\n"
                + IngestionRecoveryProcess.class.getName() + "\n" + postgres.getJdbcUrl() + "\n"
                + postgres.getUsername() + "\n" + postgres.getPassword() + "\n" + mode, StandardCharsets.UTF_8);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new Child(new ProcessBuilder(java, "@" + args).redirectErrorStream(true).start());
    }
    private static final class Child implements AutoCloseable {
        final Process process;
        final CountDownLatch checkpoint = new CountDownLatch(1);
        final StringBuffer output = new StringBuffer();
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final java.util.concurrent.Future<?> reader;
        Child(Process process) {
            this.process = process;
            reader = executor.submit(() -> {
                try (BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null) {
                        output.append(line).append('\n');
                        if (line.contains("INDEXING_CHECKPOINT")) { checkpoint.countDown(); }
                    }
                } catch (Exception exception) { output.append(exception.toString()); }
            });
        }
        @Override public void close() throws Exception {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            executor.shutdownNow();
        }
    }
}
