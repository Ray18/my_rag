package com.rag.my_rag.service.chunking;

import com.rag.my_rag.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 切块策略选择器:Spring 会把容器里所有 {@link ChunkStrategy} Bean 按 Bean 名注入这个 Map,
 * 再根据配置 {@code rag.chunk.strategy} 取出对应策略。
 * 新增策略只需注册一个 {@code @Component} 并在配置里填它的 Bean 名,无需改动本类。
 */
@Component
public class ChunkStrategyResolver {

    private final Map<String, ChunkStrategy> strategies;
    private final RagProperties ragProperties;

    public ChunkStrategyResolver(Map<String, ChunkStrategy> strategies, RagProperties ragProperties) {
        this.strategies = strategies;
        this.ragProperties = ragProperties;
    }

    /** 返回当前配置选中的切块策略 */
    public ChunkStrategy resolve() {
        String name = ragProperties.chunk().strategy();
        ChunkStrategy strategy = strategies.get(name);
        if (strategy == null) {
            throw new IllegalStateException(
                    "未知的切块策略: '" + name + "'。可选值: " + strategies.keySet());
        }
        return strategy;
    }
}
