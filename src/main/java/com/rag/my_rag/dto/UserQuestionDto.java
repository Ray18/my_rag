package com.rag.my_rag.dto;


import lombok.Data;

@Data
public class UserQuestionDto {

    /**
     * 用户提交的问题
     */
    private String question;

    /**
     * history 为 URL 编码的 JSON 数组：[{"role":"user","content":"..."},{"role":"assistant","content":"..."}]
     *
     */
    private String history;

    /**
     *  source 为可选来源过滤(文件名),解析失败或为空时优雅降级为单轮问答。
     */
    private String source;
}
