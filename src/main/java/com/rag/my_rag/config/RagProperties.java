package com.rag.my_rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 切块与检索参数,绑定自 application.properties 中的 rag.* 配置。
 */
@ConfigurationProperties(prefix = "rag")
public record RagProperties(Chunk chunk, Retrieval retrieval) {

    /**
     * 切块参数:strategy 切块策略 Bean 名(rule / structural / semantic),
     * size 单块目标字符数,overlap 相邻块重叠字符数,batchSize 向量写入每批条数,semantic 语义切块参数。
     */
    public record Chunk(String strategy, int size, int overlap, int batchSize, Semantic semantic) {}

    /** 语义切块参数:threshold 相邻窗口余弦相似度低于该值即视为语义断点,windowSentences 每个嵌入窗口包含的句子数 */
    public record Semantic(double threshold, int windowSentences) {}

    /** 检索参数:topK 每次问答召回的参考文档块数 */
    public record Retrieval(int topK) {}
}
