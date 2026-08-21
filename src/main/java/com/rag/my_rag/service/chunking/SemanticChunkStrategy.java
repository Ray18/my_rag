package com.rag.my_rag.service.chunking;

import com.rag.my_rag.config.RagProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义切块(向量相似度聚类,LlamaIndex SemanticSplitterNodeParser 思路):
 * <ol>
 *   <li>句子按 N 句一组组成"窗口"(嵌入单元);</li>
 *   <li>窗口批量调嵌入模型得向量,计算相邻窗口余弦相似度;</li>
 *   <li>相似度低于阈值 → 语义断点 → 前一块收尾,新块从上一块尾部 overlap 字符续接;</li>
 *   <li>超 size 的块在句边界内规则法再切,末尾过小的块并入前一块。</li>
 * </ol>
 * 嵌入调用失败时降级 {@link RuleBasedChunkStrategy},保证摄入链路不中断。
 */
@Component("semantic")
public class SemanticChunkStrategy implements ChunkStrategy {

    /** DashScope text-embedding-v3 单次请求最多 10 条,分批调用 */
    private static final int EMBEDDING_BATCH_SIZE = 10;

    private final EmbeddingModel embeddingModel;
    private final RuleBasedChunkStrategy ruleBased;
    private final RagProperties ragProperties;

    public SemanticChunkStrategy(EmbeddingModel embeddingModel, RuleBasedChunkStrategy ruleBased,
                                 RagProperties ragProperties) {
        this.embeddingModel = embeddingModel;
        this.ruleBased = ruleBased;
        this.ragProperties = ragProperties;
    }

    @Override
    public List<String> split(String text) {
        int chunkSize = ragProperties.chunk().size();
        int overlap = ragProperties.chunk().overlap();
        double threshold = ragProperties.chunk().semantic().threshold();
        int windowSentences = ragProperties.chunk().semantic().windowSentences();

        List<String> sentences = ChunkUtils.sentenceSplit(text);
        // 句子不足一个窗口时语义切分没有意义,直接走规则法,省一次嵌入调用
        if (sentences.size() <= windowSentences) {
            return ruleBased.split(text);
        }

        List<String> windows = buildWindows(sentences, windowSentences);

        List<float[]> embeddings;
        try {
            embeddings = embedBatched(windows);
        } catch (Exception e) {
            System.out.println("⚠️ 语义切块嵌入失败(" + e.getMessage() + "),降级为规则法切块");
            return ruleBased.split(text);
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < windows.size(); i++) {
            boolean isLast = (i == windows.size() - 1);
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(windows.get(i));

            boolean boundary = !isLast
                    && ChunkUtils.cosine(embeddings.get(i), embeddings.get(i + 1)) < threshold;
            if (boundary || isLast) {
                String chunk = current.toString();
                if (chunk.length() > chunkSize) {
                    chunks.addAll(RuleBasedChunkStrategy.splitText(chunk, chunkSize, overlap));
                } else {
                    chunks.add(chunk);
                }
                // 新块以上一块尾部 overlap 字符续接,保持跨块上下文衔接
                current = new StringBuilder();
                if (!isLast) {
                    int start = Math.max(0, chunk.length() - overlap);
                    current.append(chunk.substring(start));
                }
            }
        }

        mergeTinyTrailingChunk(chunks, chunkSize);
        return chunks;
    }

    /** 每 windowSentences 句合成一个窗口,末尾不足一窗的独立成窗 */
    private List<String> buildWindows(List<String> sentences, int windowSentences) {
        List<String> windows = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int count = 0;
        for (String sentence : sentences) {
            if (count > 0) {
                current.append(' ');
            }
            current.append(sentence);
            count++;
            if (count == windowSentences) {
                windows.add(current.toString());
                current = new StringBuilder();
                count = 0;
            }
        }
        if (!current.isEmpty()) {
            windows.add(current.toString());
        }
        return windows;
    }

    /** 分批调用嵌入模型(DashScope 单次上限 10 条),按序汇总向量 */
    private List<float[]> embedBatched(List<String> windows) {
        List<float[]> all = new ArrayList<>(windows.size());
        for (int i = 0; i < windows.size(); i += EMBEDDING_BATCH_SIZE) {
            List<String> batch = windows.subList(i, Math.min(i + EMBEDDING_BATCH_SIZE, windows.size()));
            all.addAll(embeddingModel.embed(batch));
        }
        return all;
    }

    /** 末尾残留的过小块(< size/5)并入前一块,避免向量库里出现碎片块 */
    private void mergeTinyTrailingChunk(List<String> chunks, int chunkSize) {
        if (chunks.size() < 2) {
            return;
        }
        String last = chunks.get(chunks.size() - 1);
        if (last.length() >= chunkSize / 5) {
            return;
        }
        String prev = chunks.get(chunks.size() - 2);
        chunks.set(chunks.size() - 2, prev + "\n" + last);
        chunks.remove(chunks.size() - 1);
    }
}
