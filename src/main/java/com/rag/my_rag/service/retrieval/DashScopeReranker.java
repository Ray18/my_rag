package com.rag.my_rag.service.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.rag.my_rag.config.RagProperties;
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * DashScope 文本排序(gte-rerank-v2 / qwen3-rerank)精排客户端。
 * <p>
 * 对混合检索融合后的候选块按 query 相关性重排,取前 topN。
 * 任何失败(无权限/超时/网络/解析)都打印告警并降级为「保持入参(RRF)顺序取前 N」,绝不中断问答链路。
 * <p>
 * 接口规范(见阿里云 Model Studio 文本排序文档):
 * POST {url} , Authorization: Bearer {api-key}
 * body: { model, input:{query, documents:[...]}, parameters:{top_n, return_documents:false} }
 * resp: { output:{ results:[{index, relevance_score}] } } —— index 为入参 documents 的下标,分降序。
 */
@Component
public class DashScopeReranker {

    private final RagProperties ragProperties;
    private final RestClient http;

    public DashScopeReranker(RagProperties ragProperties,
                             RestClient.Builder restClientBuilder) {
        this.ragProperties = ragProperties;
        int timeoutSeconds = ragProperties.retrieval().rerank().timeoutSeconds();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(timeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.http = restClientBuilder.requestFactory(factory).build();
    }

    /**
     * 精排候选块,返回重排后(分数高在前)的文档,最多 topN 条。
     *
     * @param query      用户问题
     * @param candidates 已融合的候选块(入参顺序 = RRF 序,失败时按此顺序降级截取)
     * @param topN       期望返回条数
     */
    public List<Document> rerank(String query, List<Document> candidates, int topN) {
        if (candidates.isEmpty() || topN <= 0) {
            return candidates;
        }
        var rerankProps = ragProperties.retrieval().rerank();
        int n = Math.min(topN, candidates.size());

        Map<String, Object> body = Map.of(
                "model", rerankProps.model(),
                "input", Map.of(
                        "query", query,
                        "documents", candidates.stream().map(Document::getContent).toList()),
                "parameters", Map.of("top_n", n, "return_documents", false));

        try {
            long started = System.currentTimeMillis();
            JsonNode resp = http.post()
                    .uri(rerankProps.url())
                    .header("Authorization", "Bearer " + rerankProps.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (resp == null) {
                throw new IllegalStateException("rerank 响应为空");
            }

            JsonNode results = resp.path("output").path("results");
            List<RankedResult> ranked = new ArrayList<>();
            if (results.isArray()) {
                for (JsonNode node : results) {
                    int index = node.path("index").asInt(-1);
                    double score = node.path("relevance_score").asDouble(Double.NaN);
                    if (index >= 0 && index < candidates.size() && !Double.isNaN(score)) {
                        ranked.add(new RankedResult(index, score));
                    }
                }
            }
            if (ranked.isEmpty()) {
                throw new IllegalStateException("rerank 未返回有效结果: " + resp);
            }
            ranked.sort(Comparator.comparingDouble(RankedResult::score).reversed());

            List<Document> out = new ArrayList<>(n);
            for (RankedResult r : ranked) {
                out.add(candidates.get(r.index()));
                if (out.size() >= n) {
                    break;
                }
            }
            System.out.println("✅ rerank 精排完成: " + ranked.size() + " 个候选,取前 " + out.size()
                    + ",耗时 " + (System.currentTimeMillis() - started) + "ms");
            return out;
        } catch (Exception e) {
            System.out.println("⚠️ rerank 调用失败(" + e.getMessage() + "),降级为按 RRF 排序取前 " + n);
            return new ArrayList<>(candidates.subList(0, n));
        }
    }

    /** 精排结果:index 为入参 candidates 下标,score 为相关性得分 */
    private record RankedResult(int index, double score) {}
}
