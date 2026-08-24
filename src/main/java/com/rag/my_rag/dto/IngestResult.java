package com.rag.my_rag.dto;

/**
 * 摄入结果:message 展示文案,replaced 是否为替换(同名文档已存在并先被清除),chunkCount 本次写入的块数。
 */
public record IngestResult(String message, boolean replaced, int chunkCount) {
}
