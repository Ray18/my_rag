package com.rag.my_rag.service.chunking;

import com.rag.my_rag.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 结构感知切块:在扁平文本上按行检测章节标题(中文序号 / 数字序号 / Markdown 标题),
 * 以标题为边界把文档切成"标题 + 章节正文"的块,标题保留在块内供 LLM 理解上下文。
 * 对 PDF 和 Word 统一生效(二者提取后都是纯文本),零额外成本。
 * <p>
 * 检测不到任何标题时(纯段落文档)降级到 {@link RuleBasedChunkStrategy}。
 */
@Component("structural")
public class StructuralChunkStrategy implements ChunkStrategy {

    /** 行首标题模式:一、/1./(一)/1.1/第一章/# 等,行首锚定避免正文误伤 */
    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "^(?:"
                    + "[一二三四五六七八九十]{1,3}[、.．]"
                    + "|[（(][一二三四五六七八九十]{1,3}[）)]"
                    + "|\\d{1,2}[、.．]\\s"
                    + "|\\d+\\.\\d+\\s"
                    + "|第[一二三四五六七八九十百\\d]+[章节部分]"
                    + "|#{1,6}\\s"
                    + ")");

    /** 标题行长上限:超长的不太可能是标题,避免误判普通长行 */
    private static final int MAX_HEADING_LENGTH = 60;

    private final RuleBasedChunkStrategy ruleBased;
    private final RagProperties ragProperties;

    public StructuralChunkStrategy(RuleBasedChunkStrategy ruleBased, RagProperties ragProperties) {
        this.ruleBased = ruleBased;
        this.ragProperties = ragProperties;
    }

    @Override
    public List<String> split(String text) {
        List<String> lines = text.lines().toList();
        List<Integer> headingLines = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (isHeading(lines.get(i))) {
                headingLines.add(i);
            }
        }
        if (headingLines.isEmpty()) {
            return ruleBased.split(text);
        }

        List<String> chunks = new ArrayList<>();
        int firstHeading = headingLines.get(0);
        // 第一个标题前的引言/前言单独成块,避免丢失
        if (firstHeading > 0) {
            chunks.addAll(splitSection(String.join("\n", lines.subList(0, firstHeading))));
        }
        for (int h = 0; h < headingLines.size(); h++) {
            int start = headingLines.get(h);
            int end = (h + 1 < headingLines.size()) ? headingLines.get(h + 1) : lines.size();
            String section = String.join("\n", lines.subList(start, end));
            chunks.addAll(splitSection(section));
        }
        return chunks;
    }

    /** 单节切块:没超 size 直接成块;超了则在该节内部按句边界规则法再切(标题保留在首块) */
    private List<String> splitSection(String section) {
        if (section.length() <= ragProperties.chunk().size()) {
            return List.of(section);
        }
        return RuleBasedChunkStrategy.splitText(section, ragProperties.chunk().size(), ragProperties.chunk().overlap());
    }

    static boolean isHeading(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_HEADING_LENGTH) {
            return false;
        }
        return HEADING_PATTERN.matcher(trimmed).find();
    }
}
