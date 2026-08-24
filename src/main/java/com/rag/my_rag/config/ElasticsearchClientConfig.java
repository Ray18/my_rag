package com.rag.my_rag.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsearch.client.RestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 高层 Elasticsearch 客户端 Bean。
 * <p>
 * Spring AI 的 ElasticsearchVectorStore 内部用自己的 ElasticsearchClient 做 kNN 检索,
 * 但没有把它暴露成 Bean(该项目未引入 spring-data-elasticsearch)。这里从 Spring AI 建好的
 * {@link RestClient} 复用一个同构的 ElasticsearchClient,供 BM25 混合检索 / 文档聚合 / delete_by_query 使用。
 * <p>
 * 注意 mapper 关闭 {@code FAIL_ON_UNKNOWN_PROPERTIES},与向量库内部一致,才能把 ES 文档反序列化成
 * Spring AI 的 {@code Document}。
 */
@Configuration
public class ElasticsearchClientConfig {

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient restClient) {
        return new ElasticsearchClient(new RestClientTransport(restClient,
                new JacksonJsonpMapper(new ObjectMapper()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false))));
    }
}
