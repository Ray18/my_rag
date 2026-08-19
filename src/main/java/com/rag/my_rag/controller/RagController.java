package com.rag.my_rag.controller;

import com.rag.my_rag.service.RagService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import reactor.core.publisher.Flux;

import java.io.IOException;

@RestController
class RagController {
    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    // 1. 上传知识库文档的接口
    @PostMapping("/rag/ingest")
    ResponseEntity<?> ingestPDF(@RequestParam MultipartFile file) throws IOException {
        ragService.ingest(file.getResource());
        return ResponseEntity.ok("文档上传并向量化成功！");
    }

    // 2. 用户提问的接口（history 为可选的多轮对话历史：URL 编码的 JSON 数组）
    @GetMapping("/rag/query")
    ResponseEntity<?> query(@RequestParam String question,
                            @RequestParam(required = false) String history) {
        String answer = ragService.query(question, history);
        return ResponseEntity.ok(answer);
    }

    // 3. 流式提问接口（SSE，逐 token 返回 JSON 包裹的增量文本）
    @GetMapping(value = "/rag/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<String> queryStream(@RequestParam String question,
                             @RequestParam(required = false) String history) {
        return ragService.queryStream(question, history);
    }
}
