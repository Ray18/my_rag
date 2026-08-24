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

    /**
     * 检索参数:topK 最终进入上下文的块数,candidateN 混合检索每腿召回候选数,
     * hybrid BM25+向量混合检索开关与 RRF 融合常数,rerank 精排参数。
     */
    public record Retrieval(int topK, int candidateN, Hybrid hybrid, Rerank rerank) {}

    /** 混合检索参数:enabled 是否启用 BM25+向量双路召回,rrfK 倒数秩融合常数(标准值 60,越小越强调高位次) */
    public record Hybrid(boolean enabled, double rrfK) {}

    /**
     * 精排参数:enabled 是否调用 DashScope rerank,model 模型名(gte-rerank-v2 / qwen3-rerank),
     * url 文本排序 HTTP 端点,apiKey 百炼 API Key(默认复用 embedding key),timeoutSeconds 超时,失败自动降级为 RRF 排序。
     */
    public record Rerank(boolean enabled, String model, String url, String apiKey, int timeoutSeconds) {}
}
