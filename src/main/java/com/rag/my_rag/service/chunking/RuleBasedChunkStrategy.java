package com.rag.my_rag.service.chunking;

import com.rag.my_rag.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则法切块:按句读边界(。！？.!?)切,超过目标大小在句边界收尾,块尾保留 overlap 字符续到下一块。
 * 简单、快、零额外成本,是其余策略的最终降级手段。
 */
@Component("rule")
public class RuleBasedChunkStrategy implements ChunkStrategy {

    private final RagProperties ragProperties;

    public RuleBasedChunkStrategy(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    @Override
    public List<String> split(String text) {
        int chunkSize = ragProperties.chunk().size();
        int overlap = ragProperties.chunk().overlap();
        return splitText(text, chunkSize, overlap);
    }

    /** 按句子边界切块。单个句子即使超过 chunkSize 也不会被拦腰切断。 */
    static List<String> splitText(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        String[] sentences = text.split(ChunkUtils.SENTENCE_SPLIT_REGEX);
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            if (current.length() + sentence.length() > chunkSize && current.length() > 0) {
                chunks.add(current.toString());
                int start = Math.max(0, current.length() - overlap);
                current = new StringBuilder(current.substring(start));
            }
            current.append(sentence);
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }
}
