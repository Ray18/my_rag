一、一句话定位

一个基于 RAG(检索增强生成)的知识库问答系统:上传 PDF / Word 文档,系统解析、切块、向量化后存入向量库;之后用户基于文档内容进行多轮对话,支持 SSE 流式输出。

二、技术栈

│     层      │     选型                                
│ 语言 / 框架 │ Java 21 + Spring Boot 3.3               
│ AI 框架     │ Spring AI 1.0.0-M4(openai 模块 + elasticsearch-store 模块 + core) 
│ 对话大模型  │ DeepSeek deepseek-chat(OpenAI 兼容协议)                           
│ 向量嵌入    │ 阿里云百炼 DashScope text-embedding-v3(OpenAI 兼容)               
│ 向量数据库  │ Elasticsearch 8.x(cosine 相似度,1024 维)                          
│ 文档解析    │ PDFBox 3.0(PDF)、Apache POI 5.2.5(Word,.doc + .docx)              
│ 前端        │ 原生 HTML/CSS/JS(无框架),SSE 流式 + 手写迷你 Markdown 渲染器      
│ 构建        │ Maven(Spring Boot parent)                                         

▎ 核心结构:pom.xml:18-19 声明 JDK 21 + Spring AI M4;application.properties 集中管理所有外部依赖的接入参数。

三、整体架构与两条数据链路
─  知识摄入链路 (ingest)  
│  上传文件 → 按扩展名分发解析(PDF/Word) → 策略化切块(规则/结构感知/语义聚类,默认语义)   
│        → 分批调用百炼 text-embedding-v3 向量化 → 写入 Elasticsearch 向量库             

|  问答链路 (query)  
│  用户提问 → 向量检索 top-5 → 组装 System 提示词({{context}} 替换) + 多轮历史            
│        → DeepSeek 生成 → 完整返回 / SSE 逐 token 流式返回                              

一个关键设计:两条链路用了两个不同供应商的模型——对话用 DeepSeek、向量用百炼。这是本项目的核心亮点,细节见第五节。

四、核心模块逐层拆解

1. 配置层(3 个类,全部走 @ConfigurationProperties)

- RagProperties.java —— 用 record 绑定 rag.* 配置:切块策略(rule/structural/semantic)、切块大小 500、重叠 50、向量写入每批 10 条、语义参数(threshold 0.7、window-sentences 5)、检索 top-k 5。MyRagApplication.java:8 上的 @ConfigurationPropertiesScan 让它们自动注册。
- PromptConfig.java —— 绑定 rag.prompt.system,即提示词模板(占位符 {{context}})。提示词不在代码里,而在 prompts.yml,并通过 spring.config.import(application.properties:39)支持运行目录下的外部 prompts.yml 覆盖内置文件 → 改提示词无需重新编译。这是最近一次提交(35c71f1)做的优化。
- DashScopeEmbeddingConfig.java —— 手动构建向量嵌入客户端(见第五节,项目亮点)。

2. 接入层 RagController.java(3 个接口)

│        接口        │       方法        │                      说明                  │
│ POST /rag/ingest   │ 传 MultipartFile  │ 上传并向量化文档,同步返回成功提示              │
│ GET /rag/query     │ question + 可选   │ 完整问答                                    │
│                    │ history           │                                            │
│ GET                │ 同上              │ SSE 流式问答,produces =                       │
│ /rag/query/stream  │                   │ TEXT_EVENT_STREAM_VALUE,返回 Flux<String>    │

3. 服务层 RagService.java(核心,分四块)

① 文档解析 extractText(179-240 行)
按扩展名分发:
- .doc(旧二进制格式)→ POI 的 HWPFDocument + WordExtractor;
- .docx(OOXML)→ XWPFDocument,遍历 bodyElements,按文档顺序依次读出段落和表格(表格内逐行逐格拼接);
- 其余默认按 PDF → PDFBox 3.0 的 Loader.loadPDF + PDFTextStripper。

空文本直接抛异常,提示"可能是扫描件/图片型文档,需要 OCR"——做了失败兜底。

② 切块(策略化,service/chunking 包)
以 ChunkStrategy 接口为扩展点,通过 rag.chunk.strategy 配置选择(默认 semantic),Resolver 按 Bean 名注入 Map 选出策略——加一种策略只需新增 @Component:
- rule 规则法:按句读 (?<=[。！？.!?]) 切,超 size 收尾 + overlap 续接(原 splitText,零成本兜底);
- structural 结构感知:检测 一、/(一)/1./1.1/第X章/# 等标题,按章节切块,标题保留在块内供 LLM 理解上下文,超长章节内部再规则法切;
- semantic 语义聚类(默认):句子每 N(5)句组成窗口 → 批量(10 条/次)调百炼嵌入 → 相邻窗口余弦相似度 < threshold(0.7)即语义断点 → 超 size 的块规则法再切、末尾过小碎片并入前块;嵌入接口失败自动降级 rule,摄入链路不中断。

③ 摄入写入 ingest(66-82 行)
每个块包成 Document,元数据带 source(文件名)→ 按 batch-size(10)分批调用 vectorStore.add(),注释说明了原因:text-embedding-v3 单次请求上限 10 条。

④ 问答组装 query / queryStream / buildMessages(86-161 行)
两条路径复用同一个 buildMessages:
1. vectorStore.similaritySearch(SearchRequest.query(question).withTopK(topK)) 召回 top-5 个块,拼成 context;
2. 消息序列 = System(提示词模板替换 {{context}}) + 历史对话 + 当前问题;
3. 历史 history 是 URL 编码的 JSON 数组 [{"role","content"},...],解析失败优雅降级为单轮问答(不报错)。

流式接口用 Flux.defer + chatModel.stream(),每个增量用 {"content":"..."} JSON 包裹——因为模型输出里的换行/空行会切断 SSE 帧,包一层 JSON 保证事件流完整;出错时也返回 {"error":...} 给前端,不中断连接。

4. 前端(原生三件套,零依赖)

- index.html —— 左右两栏:左侧知识库栏(拖拽上传 + 已传文档列表),右侧聊天区。
- app.js —— 三个值得说的点:
  - SSE 流式解析:fetch + ReadableStream 读 data: 行,30ms 节流渲染,支持"停止生成"(AbortController);
  - 多轮对话:前端维护 history,只回传最近 8 轮(MAX_HISTORY_TURNS,考虑 GET URL 长度上限);
  - 手写迷你 Markdown 渲染器:支持围栏代码块(带头部语言标签 + 复制按钮)、标题、引用、列表、行内代码/粗斜体/链接。先 escapeHtml 再还原受保护内容,用私有区字符  做占位符,防 XSS。
- style.css —— CSS 变量做主题、深色代码块、响应式(≤900px 自动变纵向)。

五、关键技术点与设计决策(面试重点)

1. 双供应商模型共存,是项目最大的亮点

DeepSeek 官方没有 embedding 接口,只有对话;而 Spring AI 的 OpenAI 模块默认 chat 和 embedding 共用一个 base-url,靠配置无法把两个供应商分开指向。

解法(DashScopeEmbeddingConfig.java):手动 new OpenAiApi(百炼base-url, api-key) + OpenAiEmbeddingModel,注册为独立的 EmbeddingModel Bean。这样注入 VectorStore 时用的是百炼向量客户端,注入 ChatModel 用的是 DeepSeek 客户端,互不干扰。

▎ 面试话术:这里体现了对 Spring AI 自动装配机制的理解——当一个 Bean 有多个候选时按类型/名称注入,通过手动定义 Bean 绕开默认配置的局限。

2. 踩过的坑:base-url 拼接

注释里写了(application.properties:23):Spring AI M4 会自动在 base-url 后拼 /v1/embeddings,所以百炼地址只能配到 https://dashscope.aliyuncs.com/compatible-mode,不能带 /v1,否则拼出 /v1/v1 报 404。

▎ 面试话术:能讲出这个细节说明是真的跑通调试过的,不是照抄 demo。

3. 切块策略:从规则法到语义切分

切块做成 ChunkStrategy 策略接口 + Resolver 按配置选 Bean,本身就是可扩展点:
- 规则法 rule:按句读切,块尾 overlap 续接——简单、快、零成本,作为兜底;
- 结构感知 structural:检测 一、/(一)/1./1.1/第X章/# 等标题,按章节切块,标题保留在块内;
- 语义聚类 semantic(默认):句子每 5 句组成窗口 → 批量嵌入 → 相邻窗口余弦相似度低于阈值(0.7,可调)即语义断点 → 超 size 再规则法切、末尾碎片并入前块。嵌入失败自动降级规则法。

▎ 面试话术:语义切分不是玄学——核心是"让块的边界落在语义自然断开处",用相邻句组的向量相似度判断断点,比固定窗口更贴合段落完整性和检索质量;复用已有的百炼嵌入模型,只多一次 ingest 时的向量调用。

4. 流式输出的可靠性设计

- 后端:每个 token 用 JSON 包裹,规避 SSE 帧被换行切断;
- 前端:按 \n\n 分帧、data: 提取、JSON 解析失败直接跳过(容错)、节流渲染避免频繁 DOM 操作。

5. 提示词工程外置

提示词从硬编码抽到 prompts.yml,{{context}} 占位符注入检索结果,且支持外部文件覆盖——改提示词不重新编译。提示词内容强调"只依据参考信息回答、无相关内容必须诚实说无法回答、避免编造",直接对治 LLM 幻觉。

6. 优雅降级与容错(遍布各层)

- history 解析失败 → 降级单轮;
- 文档解析不出文本 → 明确报错提示 OCR;
- 大模型调用异常 → 返回带排查建议的错误文案;
- 流式异常 → onErrorResume 发 {"error"} 而非断开。

7. 前端安全(迷你 Markdown 渲染器)

没有引入 marked 等库,自写渲染器并先转义再还原,用私有区字符做临时占位,从根上规避 AI 输出里的 HTML 注入。

六、已知限制与可优化方向(面试加分项:能说出"我知道它哪里不行")

1. 检索质量:固定 top-5,无 rerank,无 混合检索(BM25 + 向量)。可加 reranker 模型或按文档类型调参。
2. 缺少文档管理:只有 ingest,没有 delete / update / list,重复上传会产生重复块。
3. 配置安全:api-key 明文写在 application.properties(未提交到 git 需要额外处理),生产应走环境变量 / 密钥管理。
4. 不支持图片/扫描件:PDF 里如果只有图没有文本层会失败,未接 OCR。
5. 切块已策略化:A(结构感知)+ B(语义聚类)已落地、默认语义聚类;LLM 分块(C)留作扩展点——每次 ingest 需调 LLM 成本高,适合结构复杂的小规模文档,需要时按 ChunkStrategy 接口新增 @Component 即可。
6. 无鉴权、无并发控制、ES 单机——定位是本地/演示级,生产化需要补齐。

七、本地运行方式

# 1. 启动 Elasticsearch 8.x(localhost:9200),保证账号密码与配置一致
# 2. 编译(pom 要求 21)
# 3. 浏览器打开 http://localhost:8080,上传 PDF/Word 后即可对话

依赖的两个外部服务(ES + 两个模型 API)通过 application.properties 统一管理,initialize-schema=true 让 ES 首次启动自动建索引。
