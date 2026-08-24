package com.rag.my_rag.service.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RRF(倒数秩融合)纯逻辑单测:合并、去重、排序、k 常数对"广覆盖 vs 最高位次"的权衡。
 */
class RrfFusionTest {

    private static Document doc(String id) {
        return Document.builder().withId(id).withContent("content-" + id).build();
    }

    private static List<String> ids(List<Document> docs) {
        return docs.stream().map(Document::getId).toList();
    }

    @Test
    void singleLegKeepsOrder() {
        List<Document> leg = List.of(doc("a"), doc("b"), doc("c"));
        assertEquals(List.of("a", "b", "c"),
                ids(RetrievalService.fuseRRF(List.of(leg), 60)));
    }

    @Test
    void mergesAndDeduplicatesAcrossLegs() {
        List<Document> legA = List.of(doc("a"), doc("b"));
        List<Document> legB = List.of(doc("b"), doc("c"));
        List<Document> fused = RetrievalService.fuseRRF(List.of(legA, legB), 60);
        List<String> ids = ids(fused);
        assertEquals(3, ids.size());
        assertTrue(ids.containsAll(List.of("a", "b", "c")));
    }

    @Test
    void docPresentInBothLegsRanksAboveSingleLegDoc() {
        // a 同时出现在两条腿的 rank1 → 2/(60+1);b 只在一条腿 rank2 → 1/(60+2),a 应在前
        List<Document> legA = List.of(doc("a"), doc("b"));
        List<Document> legB = List.of(doc("a"));
        List<Document> fused = RetrievalService.fuseRRF(List.of(legA, legB), 60);
        assertEquals("a", fused.get(0).getId());
        assertEquals("b", fused.get(1).getId());
    }

    @Test
    void kConstantTradesBreadthAgainstTopRank() {
        // A 在 3 条腿里都排第 1;B 在 5 条腿里都排第 11。
        // k=0(只认最高位次):A = 3/1 = 3.0 压倒一切 → A 赢;
        // k=60(兼顾覆盖广度):B = 5/71 ≈ 0.0704 反超 A = 3/61 ≈ 0.0492 → B 赢。
        List<Document> leg1 = listWithTopAndTail("A", "B");
        List<Document> leg2 = listWithTopAndTail("A", "B");
        List<Document> leg3 = listWithTopAndTail("A", "B");
        List<Document> leg4 = listWithTailOnly("B");
        List<Document> leg5 = listWithTailOnly("B");

        List<List<Document>> legs = List.of(leg1, leg2, leg3, leg4, leg5);
        assertEquals("A", RetrievalService.fuseRRF(legs, 0).get(0).getId());
        assertEquals("B", RetrievalService.fuseRRF(legs, 60).get(0).getId());
    }

    /** 生成 [top, 9 个填充, tail] 共 11 条:top 排第 1,tail 排第 11 */
    private static List<Document> listWithTopAndTail(String top, String tail) {
        List<Document> list = new ArrayList<>();
        list.add(doc(top));
        for (int i = 0; i < 9; i++) {
            list.add(doc("f-" + top + "-" + i));
        }
        list.add(doc(tail));
        return list;
    }

    /** 生成 [10 个填充, tail] 共 11 条:tail 排第 11 */
    private static List<Document> listWithTailOnly(String tail) {
        List<Document> list = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            list.add(doc("g-" + tail + "-" + i));
        }
        list.add(doc(tail));
        return list;
    }
}
