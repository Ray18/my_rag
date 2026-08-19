package com.rag.my_rag.config;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云百炼 DashScope 向量嵌入客户端。
 * <p>
 * DeepSeek 官方没有 embedding 接口；而 Spring AI 的 OpenAI 模块默认 chat 和 embedding 共用一个 base-url，
 * 无法靠配置分别指向 DeepSeek（对话）和百炼（向量），所以这里手动构建一个只用于向量的 OpenAI 兼容客户端。
 */
@Configuration
public class DashScopeEmbeddingConfig {

    @Bean
    public EmbeddingModel dashScopeEmbeddingModel(
            @Value("${spring.ai.embedding.dashscope.base-url}") String baseUrl,
            @Value("${spring.ai.embedding.dashscope.api-key}") String apiKey,
            @Value("${spring.ai.embedding.dashscope.model}") String model) {
        OpenAiApi openAiApi = new OpenAiApi(baseUrl, apiKey);
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .withModel(model)
                .build();
        return new OpenAiEmbeddingModel(openAiApi, MetadataMode.NONE, options);
    }
}
