package com.rag.my_rag.service.retrieval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import com.rag.my_rag.config.RagProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索服务:BM25 + 向量双路召回,RRF 倒数秩融合,可选 DashScope rerank 精排。
 * <p>
 * 检索链路(每步失败都可降级,不中断问答):
 * <ol>
 *   <li>向量腿:复用 {@link VectorStore} 的 kNN 检索(自动嵌入 query),召回 candidateN 条;</li>
 *   <li>BM25腿:直接查 ES {@code multi_match} 命中 content/source,召回 candidateN 条;</li>
 *   <li>RRF 融合:两腿按 1/(k+rank) 求和取并集排序;</li>
 *   <li>源过滤:sourceFilter 非空时按 {@code metadata.source} 精确过滤(硬约束);</li>
 *   <li>精排:rerank 开启时交给 {@link DashScopeReranker} 重排,失败自动降级为 RRF 序取前 topK。</li>
 * </ol>
 * 说明:BM25 腿对含英文/数字/标识符的内容效果好;纯中文正文受 ES 标准分词器影响(不分词),
 * 若需更强的中文 BM25 需在 ES 安装 IK 分词插件并重建映射(超出本项目范围)。
 */
@Component
public class RetrievalService {

    private final VectorStore vectorStore;
    private final ElasticsearchClient esClient;
    private final RagProperties ragProperties;
    private final DashScopeReranker reranker;
    private final String indexName;

    public RetrievalService(VectorStore vectorStore, ElasticsearchClient esClient,
                            RagProperties ragProperties, DashScopeReranker reranker,
                            @Value("${spring.ai.vectorstore.elasticsearch.index-name}") String indexName) {
        this.vectorStore = vectorStore;
        this.esClient = esClient;
        this.ragProperties = ragProperties;
        this.reranker = reranker;
        this.indexName = indexName;
    }

    /**
     * 检索与 query 相关的上下文块,最多返回配置的 topK 条。
     *
     * @param query        用户问题
     * @param sourceFilter 可选:限定只从该文档(文件名)中检索,null/空白表示不限制
     */
    public List<Document> retrieve(String query, String sourceFilter) {
        var retrieval = ragProperties.retrieval();
        int topK = retrieval.topK();
        int candidateN = Math.max(retrieval.candidateN(), topK);

        // 1. 向量腿(复用现有 kNN 检索,自动嵌入 query)
        List<Document> vectorHits = vectorStore.similaritySearch(
                SearchRequest.query(query).withTopK(candidateN));

        // 2. 混合检索:RRF 融合两腿;关闭混合则仅用向量腿
        List<Document> fused;
        if (retrieval.hybrid().enabled()) {
            List<Document> bm25Hits = bm25Search(query, candidateN, sourceFilter);
            fused = fuseRRF(List.of(vectorHits, bm25Hits), retrieval.hybrid().rrfK());
        } else {
            fused = new ArrayList<>(vectorHits);
        }

        // 3. 源过滤:硬约束,Java 侧精确匹配(避免 query_string 对中文/空格文件名的转义问题)
        if (sourceFilter != null && !sourceFilter.isBlank()) {
            fused = fused.stream()
                    .filter(d -> sourceFilter.equals(d.getMetadata().get("source")))
                    .toList();
        }

        // 4. 精排:失败自动降级为按 RRF 序取前 topK
        if (retrieval.rerank().enabled() && !fused.isEmpty()) {
            return reranker.rerank(query, fused, topK);
        }
        return fused.stream().limit(topK).toList();
    }

    /** BM25 腿:ES multi_match 命中 content(加权)与 source,可选 term 过滤来源,召回前 size 条 */
    private List<Document> bm25Search(String query, int size, String sourceFilter) {
        try {
            SearchResponse<Document> res = esClient.search(s -> s
                    .index(indexName)
                    .size(size)
                    .query(q -> q.bool(b -> {
                        b.must(m -> m.multiMatch(mm -> mm
                                .query(query)
                                .fields("content^2", "metadata.source^1")
                                .operator(Operator.Or)
                                .fuzziness("AUTO")));
                        if (sourceFilter != null && !sourceFilter.isBlank()) {
                            b.filter(f -> f.term(t -> t.field("metadata.source.keyword").value(sourceFilter)));
                        }
                        return b;
                    })), Document.class);
            return res.hits().hits().stream().map(hit -> hit.source()).toList();
        } catch (IOException e) {
            System.out.println("⚠️ BM25 检索失败(" + e.getMessage() + "),本次降级为仅向量检索");
            return List.of();
        }
    }

    /**
     * 倒数秩融合(Reciprocal Rank Fusion):每个结果在每条腿的得分为 1/(k + 排名),
     * 按腿求和后降序。k 越小越强调腿内高位次的结果。同一文档按 id 去重。
     */
    static List<Document> fuseRRF(List<List<Document>> legs, double k) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, Document> byId = new HashMap<>();
        for (List<Document> leg : legs) {
            for (int i = 0; i < leg.size(); i++) {
                Document doc = leg.get(i);
                String id = doc.getId();
                if (id == null) {
                    continue;
                }
                byId.putIfAbsent(id, doc);
                scores.merge(id, 1.0 / (k + i + 1), Double::sum);
            }
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(entry -> byId.get(entry.getKey()))
                .toList();
    }
}
