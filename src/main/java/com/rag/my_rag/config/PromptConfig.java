package com.rag.my_rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 提示词配置，绑定自 prompts.yml。
 * 默认随 classpath 加载，也可在应用运行目录放置外部 prompts.yml 直接覆盖，无需重新编译。
 */
@ConfigurationProperties(prefix = "rag.prompt")
public class PromptConfig {

    /** 知识库问答 System 提示词模板，{{context}} 会被替换为检索到的参考信息 */
    private String system;

    public String getSystem() {
        return system;
    }

    public void setSystem(String system) {
        this.system = system;
    }
}
