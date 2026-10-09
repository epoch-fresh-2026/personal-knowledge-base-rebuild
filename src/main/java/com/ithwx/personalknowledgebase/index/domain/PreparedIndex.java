package com.ithwx.personalknowledgebase.index.domain;

import java.util.List;
import java.util.Objects;

/** 已计算的向量；准备过程不写数据库，提交过程不调用模型。 */
public record PreparedIndex(Long documentId, List<EmbeddedChunk> chunks) {

    public PreparedIndex {
        Objects.requireNonNull(documentId);
        chunks = List.copyOf(chunks);
        if (chunks.stream().anyMatch(chunk -> !documentId.equals(chunk.chunk().documentId()))) {
            throw new IllegalArgumentException("索引分块必须属于同一份资料");
        }
    }

    public record EmbeddedChunk(KnowledgeChunk chunk, float[] embedding) {
        public EmbeddedChunk {
            Objects.requireNonNull(chunk);
            embedding = embedding.clone();
            if (embedding.length == 0) {
                throw new IllegalArgumentException("向量不能为空");
            }
            for (float value : embedding) {
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException("向量必须包含有限数值");
                }
            }
        }

        @Override
        public float[] embedding() {
            return embedding.clone();
        }
    }
}
