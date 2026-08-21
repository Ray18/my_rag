package com.rag.my_rag.service;

import com.rag.my_rag.config.PromptConfig;
import com.rag.my_rag.config.RagProperties;
import com.rag.my_rag.service.chunking.ChunkStrategy;
import com.rag.my_rag.service.chunking.ChunkStrategyResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RagService {

    private final VectorStore vectorStore;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final PromptConfig promptConfig;
    private final RagProperties ragProperties;
    private final ChunkStrategy chunkStrategy;

    public RagService(VectorStore vectorStore, ChatModel chatModel, ObjectMapper objectMapper,
                      PromptConfig promptConfig, RagProperties ragProperties,
                      ChunkStrategyResolver chunkStrategyResolver) {
        this.vectorStore = vectorStore;
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.promptConfig = promptConfig;
        this.ragProperties = ragProperties;
        this.chunkStrategy = chunkStrategyResolver.resolve();
        System.out.println("✅ RagService 初始化成功，VectorStore: " + vectorStore.getClass().getSimpleName()
                + "，切块策略: " + chunkStrategy.getClass().getSimpleName());
    }

    public void ingest(Resource file) throws IOException {
        String content = extractText(file);
        List<String> chunks = chunkStrategy.split(content);

        List<Document> documents = chunks.stream()
                .map(chunk -> Document.builder()
                        .withContent(chunk)
                        .withMetadata(Map.of("source", file.getFilename()))
                        .build())
                .collect(Collectors.toList());

        // DashScope text-embedding-v3 单次请求最多 10 条，分批写入
        int batchSize = ragProperties.chunk().batchSize();
        for (int i = 0; i < documents.size(); i += batchSize) {
            vectorStore.add(documents.subList(i, Math.min(i + batchSize, documents.size())));
        }
        System.out.println("✅ 文档已存储，共 " + documents.size() + " 个块");
    }

    /** 问答接口：检索向量库后返回大模型生成的完整回答，支持多轮对话历史 */
    public String query(String userQuestion, String historyJson) {
        try {
            List<Message> messages = buildMessages(userQuestion, historyJson);
            return chatModel.call(new Prompt(messages)).getResult().getOutput().getContent();
        } catch (Exception e) {
            e.printStackTrace();
            return "⚠️ 查询失败：" + e.getMessage() + "\n提示：请检查 DeepSeek API 配置（api-key / base-url）以及网络连接。";
        }
    }

    /**
     * 流式查询：检索向量库后用 SSE 逐个返回大模型生成的增量文本。
     * 每个 chunk 用 JSON 包裹（{"content": "..."}），避免模型输出里的换行/空行切断 SSE 事件流。
     */
    public Flux<String> queryStream(String userQuestion, String historyJson) {
        return Flux.defer(() -> {
            List<Message> messages = buildMessages(userQuestion, historyJson);
            return chatModel.stream(new Prompt(messages))
                    .map(resp -> {
                        String content = resp.getResult() != null && resp.getResult().getOutput() != null
                                ? resp.getResult().getOutput().getContent()
                                : "";
                        return toJson(Map.of("content", content == null ? "" : content));
                    });
        }).onErrorResume(e -> {
            e.printStackTrace();
            return Flux.just(toJson(Map.of("error", e.getMessage() == null ? "未知错误" : e.getMessage())));
        });
    }

    /**
     * 组装多轮消息：System(检索到的资料) + 历史对话 + 当前问题。
     * history 为 URL 编码的 JSON 数组：[{"role":"user","content":"..."},{"role":"assistant","content":"..."}]
     * 解析失败或为空时优雅降级为单轮问答。
     */
    private List<Message> buildMessages(String userQuestion, String historyJson) {
        List<Document> relevantDocs = vectorStore.similaritySearch(
                SearchRequest
                        .query(userQuestion)
                        .withTopK(ragProperties.retrieval().topK())
        );

        String context = relevantDocs.stream()
                .map(Document::getContent)
                .collect(Collectors.joining("\n\n---\n\n"));

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(buildSystemPrompt(context)));

        if (historyJson != null && !historyJson.isBlank()) {
            try {
                List<Map<String, String>> history = objectMapper.readValue(
                        historyJson, new TypeReference<List<Map<String, String>>>() {
                        });
                if (history != null) {
                    for (Map<String, String> turn : history) {
                        String role = turn.get("role");
                        String content = turn.get("content");
                        if (content == null || content.isBlank()) {
                            continue;
                        }
                        if ("user".equals(role)) {
                            messages.add(new UserMessage(content));
                        } else if ("assistant".equals(role)) {
                            messages.add(new AssistantMessage(content));
                        }
                    }
                }
            } catch (JsonProcessingException e) {
                System.out.println("⚠️ history 解析失败，本次按单轮问答处理：" + e.getMessage());
            }
        }

        messages.add(new UserMessage(userQuestion));
        return messages;
    }

    private String buildSystemPrompt(String context) {
        // 提示词模板来自 prompts.yml，{{context}} 替换为检索到的参考信息
        return promptConfig.getSystem().replace("{{context}}", context);
    }

    private String toJson(Map<String, String> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"响应序列化失败\"}";
        }
    }

    // ===== 辅助方法 =====

    /** 按文件扩展名分发解析：.doc/.docx 走 POI，其余默认按 PDF 解析 */
    private String extractText(Resource file) throws IOException {
        String filename = file.getFilename();
        String lower = filename == null ? "" : filename.toLowerCase();

        String text;
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) {
            text = extractTextFromWord(file);
        } else {
            text = extractTextFromPdf(file);
        }

        if (text == null || text.isBlank()) {
            throw new IOException("未能从文档中提取到文本，可能是扫描件/图片型文档，请先进行 OCR 处理");
        }
        return text;
    }

    private String extractTextFromPdf(Resource pdfFile) throws IOException {
        // PDFBox 3.0 的 loadPDF 只接受 byte[]/File/RandomAccessRead，先读成字节数组
        try (InputStream is = pdfFile.getInputStream();
             PDDocument document = Loader.loadPDF(is.readAllBytes())) {
            return new PDFTextStripper().getText(document);
        }
    }

    private String extractTextFromWord(Resource wordFile) throws IOException {
        String filename = wordFile.getFilename();
        if (filename != null && filename.toLowerCase().endsWith(".doc")) {
            // 旧版 .doc（二进制格式，HWPF 解析）
            try (InputStream is = wordFile.getInputStream();
                 HWPFDocument doc = new HWPFDocument(is);
                 WordExtractor extractor = new WordExtractor(doc)) {
                return extractor.getText();
            }
        }
        // 默认按 .docx（OOXML 格式）解析，段落与表格按文档顺序读取
        try (InputStream is = wordFile.getInputStream();
             XWPFDocument doc = new XWPFDocument(is)) {
            StringBuilder sb = new StringBuilder();
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String text = paragraph.getText();
                    if (text != null && !text.isBlank()) {
                        sb.append(text).append("\n");
                    }
                } else if (element instanceof XWPFTable table) {
                    for (XWPFTableRow row : table.getRows()) {
                        for (XWPFTableCell cell : row.getTableCells()) {
                            String text = cell.getText();
                            if (text != null && !text.isBlank()) {
                                sb.append(text).append("\t");
                            }
                        }
                        sb.append("\n");
                    }
                }
            }
            return sb.toString();
        }
    }

}
