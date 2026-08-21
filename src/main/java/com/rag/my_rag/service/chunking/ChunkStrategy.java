package com.rag.my_rag.service.chunking;

import java.util.List;

/**
 * 切块策略接口 —— RAG 摄入链路的可扩展点。
 * <p>
 * 各实现把一段文档文本切成若干语义完整、便于向量化的块。
 * 约定:无论文本多简单(无标题、无结构)实现方都必须能切出至少一个块;
 * 语义/结构信息不足时应自行降级到规则法,保证任何文本都不会切块失败。
 * <p>
 * 新增一种策略 = 新增一个 {@code @Component} 实现 + 在 {@code rag.chunk.strategy}
 * 配置里填入该 Bean 名,调用方(RagService)无需任何改动。
 */
public interface ChunkStrategy {

    /**
     * 把整段文档文本切成适合写入向量库的块。
     *
     * @param text 已提取的纯文本(可能含换行/标题等结构信息)
     * @return 切块结果,至少包含一个块
     */
    List<String> split(String text);
}
