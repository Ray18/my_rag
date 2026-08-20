package com.rag.my_rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 切块与检索参数，绑定自 application.properties 中的 rag.* 配置。
 */
@ConfigurationProperties(prefix = "rag")
public record RagProperties(Chunk chunk, Retrieval retrieval) {

    /** 切块参数：size 单块目标字符数，overlap 相邻块重叠字符数，batchSize 向量写入每批条数 */
    public record Chunk(int size, int overlap, int batchSize) {}

    /** 检索参数：topK 每次问答召回的参考文档块数 */
    public record Retrieval(int topK) {}
}
