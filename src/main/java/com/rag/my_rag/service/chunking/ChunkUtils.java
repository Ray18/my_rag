package com.rag.my_rag.service.chunking;

import java.util.Arrays;
import java.util.List;

/**
 * 切块策略共用的工具方法:句子切分、余弦相似度。
 */
final class ChunkUtils {

    /** 句读正则:按中文(。！？)和英文(.!?)句尾切分,lookbehind 保证切分点不吞掉标点 */
    static final String SENTENCE_SPLIT_REGEX = "(?<=[。！？.!?])";

    private ChunkUtils() {
    }

    /**
     * 把文本切成句子,去掉空句与首尾空白。
     * 用于语义/结构切块这类需要"句子"作为最小单元的策略。
     */
    static List<String> sentenceSplit(String text) {
        return Arrays.stream(text.split(SENTENCE_SPLIT_REGEX))
                .filter(s -> !s.isBlank())
                .map(String::trim)
                .toList();
    }

    /** 余弦相似度,取值 [-1,1],越大表示两个向量方向越一致 */
    static float cosine(float[] a, float[] b) {
        if (a.length == 0 || a.length != b.length) {
            return 0f;
        }
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0f;
        }
        return (float) (dot / (Math.sqrt(normA) * Math.sqrt(normB)));
    }
}
