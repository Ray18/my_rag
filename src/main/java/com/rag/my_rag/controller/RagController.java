package com.rag.my_rag.controller;

import com.rag.my_rag.dto.DocumentInfo;
import com.rag.my_rag.dto.IngestResult;
import com.rag.my_rag.service.RagService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
class RagController {
    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    // 1. 上传知识库文档的接口（同名文档自动替换，即「更新」；返回 JSON 标记是否替换）
    @PostMapping("/rag/ingest")
    ResponseEntity<?> ingest(@RequestParam MultipartFile file) throws IOException {
        IngestResult result = ragService.ingest(file.getResource(), file.getSize());
        return ResponseEntity.ok(result);
    }

    // 2. 文档列表：文件名 + 块数 + 最近上传时间
    @GetMapping("/rag/docs")
    ResponseEntity<?> listDocs() {
        List<DocumentInfo> docs = ragService.listDocuments();
        return ResponseEntity.ok(docs);
    }

    // 3. 删除文档（按文件名；文件名可能含中文/空格，走 query 参数避免 URL 转义问题）
    @DeleteMapping("/rag/docs")
    ResponseEntity<?> deleteDoc(@RequestParam String name) {
        long deleted = ragService.deleteDocument(name);
        if (deleted == 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "文档「" + name + "」不存在"));
        }
        return ResponseEntity.ok(Map.of(
                "message", "文档「" + name + "」已删除",
                "deletedChunks", deleted));
    }

    // 4. 用户提问的接口（history 为可选的多轮对话历史；source 为可选来源过滤，只检索指定文档）
    @GetMapping("/rag/query")
    ResponseEntity<?> query(@RequestParam String question,
                            @RequestParam(required = false) String history,
                            @RequestParam(required = false) String source) {
        String answer = ragService.query(question, history, source);
        return ResponseEntity.ok(answer);
    }

    // 5. 流式提问接口（SSE，逐 token 返回 JSON 包裹的增量文本）
    @GetMapping(value = "/rag/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<String> queryStream(@RequestParam String question,
                             @RequestParam(required = false) String history,
                             @RequestParam(required = false) String source) {
        return ragService.queryStream(question, history, source);
    }
}
