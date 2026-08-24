package com.rag.my_rag.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.rag.my_rag.config.PromptConfig;
import com.rag.my_rag.config.RagProperties;
import com.rag.my_rag.dto.DocumentInfo;
import com.rag.my_rag.dto.IngestResult;
import com.rag.my_rag.dto.UserQuestionDto;
import com.rag.my_rag.service.chunking.ChunkStrategy;
import com.rag.my_rag.service.chunking.ChunkStrategyResolver;
import com.rag.my_rag.service.retrieval.RetrievalService;
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
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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
    private final RetrievalService retrievalService;
    private final ElasticsearchClient esClient;
    private final String indexName;

    public RagService(VectorStore vectorStore, ChatModel chatModel, ObjectMapper objectMapper,
                      PromptConfig promptConfig, RagProperties ragProperties,
                      ChunkStrategyResolver chunkStrategyResolver,
                      RetrievalService retrievalService, ElasticsearchClient esClient,
                      @Value("${spring.ai.vectorstore.elasticsearch.index-name}") String indexName) {
        this.vectorStore = vectorStore;
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.promptConfig = promptConfig;
        this.ragProperties = ragProperties;
        this.chunkStrategy = chunkStrategyResolver.resolve();
        this.retrievalService = retrievalService;
        this.esClient = esClient;
        this.indexName = indexName;
        System.out.println("✅ RagService 初始化成功，VectorStore: " + vectorStore.getClass().getSimpleName()
                + "，切块策略: " + chunkStrategy.getClass().getSimpleName());
    }

    /**
     * 摄入文档(幂等 upsert):同名文档已存在则先删除旧块再写入,相当于「更新」,
     * 重试上传不会产生重复块,历史遗留的重复块也在重传时一并清理。
     */
    public IngestResult ingest(Resource file, long fileSize) throws IOException {
        String filename = file.getFilename();
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String content = extractText(file);
        List<String> chunks = chunkStrategy.split(content);

        boolean replaced = countDocuments(filename) > 0;
        if (replaced) {
            deleteBySource(filename);
        }

        long now = System.currentTimeMillis();
        List<Document> documents = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            documents.add(Document.builder()
                    .withContent(chunks.get(i))
                    .withMetadata(Map.of(
                            "source", filename,      // 文档身份 = 文件名
                            "docId", filename,
                            "chunkIndex", i,
                            "uploadedAt", now,       // epoch 毫秒,供文档列表展示
                            "fileSize", fileSize))
                    .build());
        }

        // DashScope text-embedding-v3 单次请求最多 10 条，分批写入
        int batchSize = ragProperties.chunk().batchSize();
        for (int i = 0; i < documents.size(); i += batchSize) {
            vectorStore.add(documents.subList(i, Math.min(i + batchSize, documents.size())));
        }
        System.out.println((replaced ? "✅ 文档已替换" : "✅ 文档已新建")
                + "，共 " + documents.size() + " 个块");
        String message = replaced
                ? "文档「" + filename + "」已替换（共 " + documents.size() + " 个块）"
                : "文档「" + filename + "」上传并向量化成功（共 " + documents.size() + " 个块）";
        return new IngestResult(message, replaced, documents.size());
    }

    /** 列出知识库中的文档(文件名 + 块数 + 最近上传时间),按最近上传倒序 */
    public List<DocumentInfo> listDocuments() {
        try {
            SearchResponse<Document> res = esClient.search(s -> s
                    .index(indexName)
                    .size(0)
                    .aggregations("sources", a -> a
                            .terms(t -> t.field("metadata.source.keyword").size(500))
                            .aggregations("latestUpload", a2 -> a2.min(m -> m.field("metadata.uploadedAt")))),
                    Document.class);
            List<DocumentInfo> docs = new ArrayList<>();
            var aggs = res.aggregations();
            if (aggs == null || aggs.get("sources") == null || !aggs.get("sources").isSterms()) {
                return docs;
            }
            for (var bucket : aggs.get("sources").sterms().buckets().array()) {
                String name = bucket.key().stringValue();
                long chunkCount = bucket.docCount();
                long uploadedAt = 0L;
                var sub = bucket.aggregations();
                if (sub != null && sub.get("latestUpload") != null
                        && sub.get("latestUpload").isMin()) {
                    uploadedAt = (long) sub.get("latestUpload").min().value();
                }
                docs.add(new DocumentInfo(name, chunkCount, uploadedAt));
            }
            docs.sort((a, b) -> Long.compare(b.uploadedAt(), a.uploadedAt()));
            return docs;
        } catch (IOException e) {
            throw new RuntimeException("列出文档失败: " + e.getMessage(), e);
        }
    }

    /** 删除文档的全部块,返回删除的块数(文档不存在返回 0) */
    public long deleteDocument(String name) {
        return deleteBySource(name);
    }

    /** 问答接口：混合检索向量库后返回大模型生成的完整回答，支持多轮对话历史与按来源过滤 */
    public String query(UserQuestionDto userQuestionDto) {
        try {
            List<Message> messages = buildMessages(userQuestionDto);
            return chatModel.call(new Prompt(messages)).getResult().getOutput().getContent();
        } catch (Exception e) {
            e.printStackTrace();
            return "⚠️ 查询失败：" + e.getMessage() + "\n提示：请检查 DeepSeek API 配置（api-key / base-url）以及网络连接。";
        }
    }

    /**
     * 流式查询：混合检索后用 SSE 逐个返回大模型生成的增量文本。
     * 每个 chunk 用 JSON 包裹（{"content": "..."}），避免模型输出里的换行/空行切断 SSE 事件流。
     */
    public Flux<String> queryStream(UserQuestionDto userQuestionDto) {
        return Flux.defer(() -> {
            List<Message> messages = buildMessages(userQuestionDto);
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
     * source 为可选来源过滤(文件名),解析失败或为空时优雅降级为单轮问答。
     */
    private List<Message> buildMessages(UserQuestionDto userQuestionDto) {
        List<Document> relevantDocs = retrievalService.retrieve(userQuestionDto.getQuestion(), userQuestionDto.getSource());
        System.out.println("🔎 检索到 " + relevantDocs.size() + " 个上下文块"
                + (userQuestionDto.getSource() != null && !userQuestionDto.getSource().isBlank() ? "（限定来源: " + userQuestionDto.getSource() + "）" : ""));

        String context = relevantDocs.stream()
                .map(Document::getContent)
                .collect(Collectors.joining("\n\n---\n\n"));

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(buildSystemPrompt(context)));

        if (userQuestionDto.getHistory() != null && !userQuestionDto.getHistory().isBlank()) {
            try {
                List<Map<String, String>> history = objectMapper.readValue(
                        userQuestionDto.getHistory(), new TypeReference<List<Map<String, String>>>() {
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

        messages.add(new UserMessage(userQuestionDto.getQuestion()));
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

    // ===== 文档管理辅助方法 =====

    /** 统计某个文档(按 metadata.source() 精确匹配)当前有多少个块 */
    private long countDocuments(String source) {
        try {
            var res = esClient.count(c -> c.index(indexName)
                    .query(q -> q.term(t -> t.field("metadata.source.keyword").value(source))));
            return res.count();
        } catch (IOException e) {
            System.out.println("⚠️ 查询文档「" + source + "」块数失败(" + e.getMessage() + ")，按不存在处理");
            return 0L;
        }
    }

    /** 按 metadata.source 删除文档的全部块(delete_by_query 直接删,无需先把 id 拉回内存) */
    private long deleteBySource(String source) {
        try {
            var res = esClient.deleteByQuery(d -> d
                    .index(indexName)
                    .query(q -> q.term(t -> t.field("metadata.source.keyword").value(source)))
                    .refresh(true));
            return res.deleted();
        } catch (IOException e) {
            throw new RuntimeException("删除文档「" + source + "」失败: " + e.getMessage(), e);
        }
    }

    // ===== 文本解析 =====

    /** 按文件扩展名分发解析：.doc/.docx 走 POI，其余默认按 PDF 解析 */
    private String extractText(Resource file) throws IOException {
        String filename = file.getFilename();
        String lower = filename == null ? "" : filename.toLowerCase();

        String text;
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) {
            text = extractTextFromWord(file);
        } else if (lower.endsWith(".xlsx")) {
            text = extractTextFromExcel(file);
        } else if (lower.endsWith(".txt")) {
            text = extractTextFromTxt(file);
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

    /** .txt 纯文本:按 UTF-8 读取并去掉 BOM;非 UTF-8(如 GBK 中文)自动回退解码 */
    private String extractTextFromTxt(Resource txtFile) throws IOException {
        byte[] bytes;
        try (InputStream is = txtFile.getInputStream()) {
            bytes = is.readAllBytes();
        }
        // 去掉 UTF-8 BOM(EF BB BF)
        int offset = (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
        } catch (CharacterCodingException e) {
            // 不是合法 UTF-8,按 GBK(常见中文 Windows 文本编码)回退
            return new String(bytes, offset, bytes.length - offset, Charset.forName("GBK"));
        }
    }

    /** .xlsx 电子表格:逐 Sheet / 逐行 / 逐格拼接为文本,单元格用制表符分隔;公式取计算缓存值 */
    private String extractTextFromExcel(Resource excelFile) throws IOException {
        DataFormatter formatter = new DataFormatter();
        StringBuilder sb = new StringBuilder();
        try (InputStream is = excelFile.getInputStream();
             XSSFWorkbook workbook = new XSSFWorkbook(is)) {
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                sb.append("[Sheet: ").append(sheet.getSheetName()).append("]\n");
                for (Row row : sheet) {
                    boolean first = true;
                    for (Cell cell : row) {
                        String value = formatter.formatCellValue(cell);
                        if (value == null || value.isEmpty()) {
                            continue;
                        }
                        if (!first) {
                            sb.append("\t");
                        }
                        first = false;
                        sb.append(value);
                    }
                    sb.append("\n");
                }
            }
        }
        return sb.toString();
    }

}
