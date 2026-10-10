package com.ithwx.personalknowledgebase.index.infrastructure;

import com.ithwx.personalknowledgebase.index.domain.KnowledgeChunk;
import com.ithwx.personalknowledgebase.index.domain.KnowledgeIndex;
import com.ithwx.personalknowledgebase.index.domain.PreparedIndex;
import com.ithwx.personalknowledgebase.index.domain.SearchQuery;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.StringJoiner;

@Repository
public class PgVectorKnowledgeIndex implements KnowledgeIndex {

    private static final int RRF_K = 60;

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;
    private final int embeddingBatchSize;

    public PgVectorKnowledgeIndex(VectorStore vectorStore, JdbcTemplate jdbcTemplate,
                                  EmbeddingModel embeddingModel,
                                  @Value("${app.ingestion.embedding-batch-size:32}") int embeddingBatchSize) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
        if (embeddingBatchSize < 1) {
            throw new IllegalArgumentException("向量批大小必须为正数");
        }
        this.embeddingBatchSize = embeddingBatchSize;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeKeywordIndex() {
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_vector_store_content_trgm
                ON vector_store USING gin (content gin_trgm_ops)
                """);
    }

    @Override
    public List<KnowledgeChunk> search(SearchQuery query) {
        return fuse(vectorSearch(query), keywordSearch(query), query.candidateLimit());
    }

    @Override
    public PreparedIndex prepare(Long documentId, List<KnowledgeChunk> chunks) {
        List<PreparedIndex.EmbeddedChunk> embedded = new ArrayList<>();
        for (int start = 0; start < chunks.size(); start += embeddingBatchSize) {
            List<KnowledgeChunk> batch = chunks.subList(start, Math.min(start + embeddingBatchSize, chunks.size()));
            List<float[]> vectors = embeddingModel.embed(batch.stream().map(KnowledgeChunk::text).toList());
            if (vectors.size() != batch.size()) {
                throw new IllegalStateException("向量模型返回的数量与分块数量不一致");
            }
            for (int index = 0; index < batch.size(); index++) {
                embedded.add(new PreparedIndex.EmbeddedChunk(batch.get(index), vectors.get(index)));
            }
        }
        return new PreparedIndex(documentId, embedded);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void replace(PreparedIndex prepared) {
        delete(prepared.documentId());
        // 只写已计算的向量，与任务/资料状态共享同一个 PostgreSQL 事务。
        for (PreparedIndex.EmbeddedChunk embedded : prepared.chunks()) {
            KnowledgeChunk chunk = embedded.chunk();
            UUID id = UUID.nameUUIDFromBytes((chunk.documentId() + ":" + chunk.chunkIndex())
                    .getBytes(StandardCharsets.UTF_8));
            jdbcTemplate.update("""
                    INSERT INTO vector_store (id, content, metadata, embedding)
                    VALUES (?, ?, jsonb_strip_nulls(jsonb_build_object(
                        'documentId', CAST(? AS text), 'documentName', CAST(? AS text),
                        'sourceType', CAST(? AS text), 'sourceUrl', CAST(? AS text),
                        'chunkIndex', CAST(? AS integer))), CAST(? AS vector))
                    """, id, chunk.text(), String.valueOf(chunk.documentId()), chunk.documentName(),
                    chunk.sourceType(), chunk.sourceUrl(), chunk.chunkIndex(), vectorLiteral(embedded.embedding()));
        }
    }

    @Override
    @Transactional
    public void delete(Long documentId) {
        jdbcTemplate.update("DELETE FROM vector_store WHERE metadata ->> 'documentId' = ?", String.valueOf(documentId));
    }

    private List<KnowledgeChunk> vectorSearch(SearchQuery query) {
        SearchRequest request = SearchRequest.builder()
                .query(query.text())
                .topK(query.candidateLimit())
                .similarityThreshold(query.similarityThreshold())
                .build();

        return vectorStore.similaritySearch(request).stream()
                .map(this::toKnowledgeChunk)
                .toList();
    }

    private List<KnowledgeChunk> keywordSearch(SearchQuery query) {
        return jdbcTemplate.query("""
                SELECT content,
                       metadata ->> 'documentId' AS document_id,
                       metadata ->> 'documentName' AS document_name,
                       metadata ->> 'sourceType' AS source_type,
                       metadata ->> 'sourceUrl' AS source_url,
                       (metadata ->> 'chunkIndex')::integer AS chunk_index
                FROM vector_store
                WHERE content % ? OR content ILIKE '%' || ? || '%'
                ORDER BY similarity(content, ?) DESC
                LIMIT ?
                """, (resultSet, rowNumber) -> new KnowledgeChunk(
                resultSet.getLong("document_id"),
                resultSet.getString("document_name"),
                resultSet.getString("source_type"),
                resultSet.getString("source_url"),
                resultSet.getInt("chunk_index"),
                resultSet.getString("content")
        ), query.text(), query.text(), query.text(), query.candidateLimit());
    }

    private KnowledgeChunk toKnowledgeChunk(org.springframework.ai.document.Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return new KnowledgeChunk(
                Long.valueOf(metadata.get("documentId").toString()),
                metadata.get("documentName").toString(),
                metadata.get("sourceType").toString(),
                metadata.get("sourceUrl") == null ? null : metadata.get("sourceUrl").toString(),
                Integer.parseInt(metadata.get("chunkIndex").toString()),
                document.getText()
        );
    }

    List<KnowledgeChunk> fuse(
            List<KnowledgeChunk> vectorResults,
            List<KnowledgeChunk> keywordResults,
            int candidateLimit
    ) {
        Map<String, FusedChunk> fused = new LinkedHashMap<>();
        for (List<KnowledgeChunk> results : List.of(vectorResults, keywordResults)) {
            Set<String> seen = new HashSet<>();
            int rank = 0;
            for (KnowledgeChunk chunk : results) {
                String key = chunk.documentId() + ":" + chunk.chunkIndex();
                // 同一路重复片段只计一次，去重后从第 1 名开始排名。
                if (!seen.add(key)) {
                    continue;
                }
                rank++;
                // 按排名计分，不直接相加两路含义不同的原始相似度。
                double score = 1.0 / (RRF_K + rank);
                FusedChunk previous = fused.get(key);
                if (previous != null) {
                    score += previous.rrfScore();
                    chunk = previous.chunk();
                }
                fused.put(key, new FusedChunk(chunk, score));
            }
        }
        // 同分时固定顺序；截断后才交给现有模型重排序，控制候选数量。
        return fused.values().stream()
                .sorted(Comparator.comparingDouble(FusedChunk::rrfScore).reversed()
                        .thenComparing(result -> result.chunk().documentId())
                        .thenComparingInt(result -> result.chunk().chunkIndex()))
                .limit(candidateLimit)
                .map(FusedChunk::chunk)
                .toList();
    }

    private record FusedChunk(KnowledgeChunk chunk, double rrfScore) {
    }

    private String vectorLiteral(float[] vector) {
        StringJoiner values = new StringJoiner(",", "[", "]");
        for (float value : vector) {
            values.add(Float.toString(value));
        }
        return values.toString();
    }

}
