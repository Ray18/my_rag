package com.rag.my_rag.dto;

/**
 * 文档列表项:name 文件名(文档身份),chunkCount 该文档的块数,uploadedAt 最近上传时间(epoch 毫秒)。
 */
public record DocumentInfo(String name, long chunkCount, long uploadedAt) {
}
