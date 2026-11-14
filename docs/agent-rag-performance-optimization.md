# Agent / RAG 性能优化与索引设计深度拆解 —— OmniMerchant 面试补充篇

> 本文是 `agent-engineer-interview-guide.md` 的姊妹篇，专注**性能优化**主线，回答三个核心问题：
> 1. **RAG 是怎么提高召回率的？**（五层手段链，每层都有本项目真实代码）
> 2. **RAG 的索引设计是干什么的？**（不只是"建个向量索引"——它是性能、一致性、可治理三者的交汇点）
> 3. **Agent/RAG 还有哪些实现层优化？**（异步索引管道、语义缓存、背压、连接池隔离……）
>
> 所有代码引用均来自项目真实文件，可对照源码复核。

---

## 目录

- [一、先建立正确认知：RAG 性能优化的两个维度](#一先建立正确认知rag-性能优化的两个维度)
- [二、RAG 召回率提升：五层手段链](#二rag-召回率提升五层手段链)
- [三、RAG 索引设计详解：不只是建索引](#三rag-索引设计详解不只是建索引)
- [四、HNSW 索引参数调优（面试深水区）](#四hnsw-索引参数调优面试深水区)
- [五、BM25 全文索引设计：零成本的词法召回](#五bm25-全文索引设计零成本的词法召回)
- [六、索引版本发布机制：像发代码一样发索引](#六索引版本发布机制像发代码一样发索引)
- [七、文档切割（Chunking）与向量化设计：召回率的地基](#七文档切割chunking与向量化设计召回率的地基)
- [八、异步索引管道：写入路径的性能与可靠性设计](#八异步索引管道写入路径的性能与可靠性设计)
- [九、语义缓存与查询层优化（低成本高杠杆）](#九语义缓存与查询层优化低成本高杠杆)
- [十、Agent 侧性能优化补充清单](#十agent-侧性能优化补充清单)
- [十一、数据库与基础设施层的性能设计](#十一数据库与基础设施层的性能设计)
- [十二、RAG 检索全链路的延迟预算拆解](#十二rag-检索全链路的延迟预算拆解)
- [十三、性能类面试问答框架](#十三性能类面试问答框架)
- [十四、RAG 全栈面试题库（30 问速答卡）](#十四rag-全栈面试题库30-问速答卡)
- [十五、尚未实现的性能优化路线图](#十五尚未实现的性能优化路线图)

---

# 一、先建立正确认知：RAG 性能优化的两个维度

面试开场如果聊到 RAG 优化，先抛出这个框架，避免东一榔头西一棒子：

```
RAG 性能 = 检索质量（效果） × 检索效率（速度）

检索质量（召回什么）：recall@K / MRR / nDCG / no-answer 准确率
检索效率（多快召回）：P50/P95 延迟 / QPS / 成本 per query

两者经常冲突：
  - 加 rerank 提精度 → 增加 50-200ms 延迟
  - 加大 ef_search 提召回 → 增加 HNSW 遍历时间
  - 多路召回提覆盖 → 增加并发查询数与成本
工程的本质是【分层预算】：每一层花多少钱、多少延迟，换回多少质量，
并且用评测报告把这笔账算清楚。
```

本项目用一句话概括设计哲学：**"确定性优先 + 每层可降级 + 每步可度量"**——所有优化手段都挂在 `RagDtos.DebugResponse` 上（vector/bm25/fused/reranked 四组候选 + evidence level + rerank mode + 耗时），**每次查询都能回答"质量从哪来、时间花在哪"**。

---

# 二、RAG 召回率提升：五层手段链

> "RAG 怎么提高召回率"是面试必考题。低水平答案只说"混合检索"；高水平答案能拆出**五层、每层独立起作用、可以独立评测**。本项目五层全部落地。
> （注意：五层的输入都是 chunk——**分块质量是这五层之前的地基**，完整设计见第七章。）

## 手段总览图

```
用户问题 "这个能退吗？多久退款到账"
   │
   ▼ ① 查询理解层 —— Query Planning / 改写 / 扩展
   │    "退吗"→"return policy refund window 退款政策"（消歧 + 跨语言扩展）
   ▼ ② 多路召回层 —— 双通道并行召回，扩大候选池
   │    语义通道（HNSW 向量） + 词法通道（BM25 tsvector）
   ▼ ③ 融合层 —— RRF 合并两路排名，双确认加权
   ▼ ④ 精排层 —— Cross-Encoder rerank，语境级重排
   ▼ ⑤ 上下文层 —— 邻居窗口扩展，修复"答案被切断"
   │
   ▼ 最终 Context Pack（3600 字符预算内装最相关证据）
```

## 第①层：查询理解层——先把"问题"变好

**问题**：用户问题短、口语化、多语言、含指代（"这个"），直接拿去检索必然召回差。这是**召回率损失的最大源头之一**——问题本身和文档词汇不匹配（vocabulary mismatch）。

**项目实现**（`RagQueryPlanningService.plan`）：

```java
private Set<String> expand(String query, String intent, String language) {
    if (q.contains("return") || q.contains("refund") || ... || q.contains("退款")) {
        terms.add("return policy");
        terms.add("refund window");
        terms.add("exchange condition");
        terms.add("退货政策");          // 中英双语扩展
    }
    ...
    var rewritten = original + " " + String.join(" ", expansions);
```

四个设计点：
1. **意图驱动扩展**：`RETURN_REFUND` 意图自动追加 "refund eligibility"，让扩展词和意图对齐而非纯字面匹配；
2. **跨语言扩展**：中文问题同时扩展英文关键词（反之亦然）——**解决"用户语言 ≠ 索引语言"的召回断层**；
3. **确定性实现**：规则扩展零 token 成本、零延迟、**评测可复现**（改一条规则跑一次 eval 立刻知道 MRR 变化）；
4. **演进开关**：`omnimerchant.rag.live-query-rewrite` 预留 LLM 改写路径——面试话术："先用规则版保住可评测性，需要覆盖长尾表达时再引入 LLM 改写，且新旧两种 planner 用同一套 51×2 用例对比 MRR，用数据决定是否切换"。

**面试加分**：还可以主动补充项目已有的"翻译桥"设计——表结构里有 `chunk_text_en`（英语翻译列），`chunk_tsv` 生成列优先取 `COALESCE(chunk_text_en, chunk_text)`，配合 Agent 侧"中转英语"策略，**让中文文档也被英文 tsquery 命中**。跨语言检索的三种方案（翻译查询 / 翻译文档 / 多语言 embedding 模型）这里同时用了前两种。

## 第②层：多路召回层——语义 + 词法双保险

**问题**：单路召回各有盲区。纯向量对**精确词**（订单号、SKU、专有名词）召回差；纯 BM25 对**同义改写**（"退货"vs"退款流程"）召回差。

**项目实现**（`HybridRagService.vectorSearch / bm25Search`）：

| 维度 | 语义通道 | 词法通道 |
|---|---|---|
| 索引 | HNSW（m=16, ef_construction=64） | GIN(chunk_tsv) |
| 距离/打分 | 余弦 `1 - (embedding <=> ?::vector)` | `ts_rank(chunk_tsv, plainto_tsquery(...))` |
| 擅长 | 同义、改写、跨语言、模糊语义 | 专有名词、精确术语、代码/单号 |
| 盲区 | 罕见词精确匹配 | 同义改写 |
| 检索模式 | BM25_ONLY 时跳过（`List.of()`） | VECTOR_ONLY 时跳过 |

两个通道的 **topK 独立可配**（`vectorTopK` / `bm25TopK` 分开配置），且都在 SQL 里做了租户/文档类型/语言/索引版本/风险等级过滤——**先过滤后排序，候选池既大又干净**。

**面试讲法**：两路召回不是"多此一举"，而是互补纠错——评测数据（BM25-only 与 Hybrid 都 102/102 通过）说明在政策类语料上 BM25 已很强，但向量通道保证改写/口语化问题的兜底召回。**双通道的价值在长尾问题上，均值指标看不出来**。

## 第③层：RRF 融合层——让"双确认"浮上来

（公式与论证见姊妹篇 7.1，这里补性能视角）

RRF 是**纯内存计算**（`RrfFusion.fuse` 只是两个 for 循环 + 排序），微秒级完成——**用近乎零成本把两路召回的"交集置信度"编码进排序**：两路都命中的 chunk 得分累加，天然比单路命中者靠前。这等价于一个免费的"投票精排"，在花钱的 Cross-Encoder 之前先把明显的噪声压下去。

## 第④层：精排层——Cross-Encoder 语境级重排

**问题**：Bi-encoder（向量检索）为了速度牺牲了精度——query 和 doc **分别**编码，交互只在最后余弦一步。**Cross-Encoder 把 query+doc 拼一起过模型**，能捕捉词级别的交互（"30 天内"到底限制的是"退货"还是"退款到账"）。

**项目实现**（`CrossEncoderReranker.rerankWithEvidence`）的三个性能细节：
1. **只对融合后的 top 候选重排**（不是全库）——Cross-Encoder 的 O(N) 成本被限制在小 N；
2. `candidates.size() <= topN` 直接跳过——**排序不改变集合时白花钱**；
3. 失败降级 `fallback-rrf` 并显式落 mode——**精度增强器永远不是单点故障**。

## 第⑤层：上下文扩展层——修复"答案被切断"

**问题**：chunk 化检索的经典召回陷阱——答案跨 chunk 边界（"退货窗口 30 天"在 chunk A，"但清仓品除外"在 chunk B），单独召回 A 会产生**事实性残缺**，这比召回不到更危险。

**项目实现**（`HybridRagService.expandContext`）：
- 向量表冗余 `neighbor_prev_uuid / neighbor_next_uuid` + `(tenant_id, doc_uuid, chunk_index)` 复合索引——邻居定位是**单条索引查询**，不是全表扫描；
- 邻居以 `0.5 × 原分数` 衰减加入，`putIfAbsent` 去重；
- 衰减的意义：邻居补全语义，但**排序上永远不压过本体命中**——召回率和排序质量同时保住。

## 召回率的"防守面"：主动拒答也是召回质量

低召回时硬答 = 幻觉。项目的证据分级（SUFFICIENT/PARTIAL/WEAK/NONE）+ 结构化拒答原因（`RAG_WEAK_EVIDENCE → 建议拒答或升级人工`），本质是**把"召回失败"转化成显式的系统行为**而不是模型瞎编。评测里 no-answer 100% 通过——**召回率优化必须和 no-answer 机制配套，否则召回质量无法度量**。

---

# 三、RAG 索引设计详解：不只是建索引

> "RAG 的索引设计是干什么的？"——低分答案："建 HNSW 索引加速检索"。高分答案：索引设计同时解决四个问题：**查得快（性能）、查得准（过滤下推）、切得动（版本原子切换）、修得好（失败可观测可重试）**。

## 3.1 向量表结构：一张表 = 检索单元 + 证据元数据 + 治理锚点

`policy_vectors` 的列设计分五组（`sql/db_vector.sql`）：

```
① 检索主体：   chunk_uuid / chunk_index / chunk_text / chunk_text_en / embedding(1536维)
              chunk_tsv（tsvector 生成列）
② 多租户：     tenant_id（所有复合索引的前导列）
③ 证据链：     doc_uuid / doc_version / source_title / source_uri / section_path
④ 治理字段：   source_trust_level / risk_level / content_hash / effective_from/to / index_version
⑤ 运营统计：   retrieval_count / last_retrieved_at（预留的热点统计）
```

**面试金句**：RAG 向量表不是"向量仓库"，而是**把证据治理（谁说的、可信吗、哪个版本、还有效吗）下沉到检索层的最后一道 WHERE**——`risk_level <> 'HIGH'` 和 `source_trust_level <> 'UNTRUSTED'` 直接写进检索 SQL，**不可信文档进不了候选池**，这比"检索后再过滤"快（不用浪费 topK 名额）也更安全（不存在漏网）。

## 3.2 索引全家福：11 个索引各司其职

```sql
-- 租户前导复合索引（过滤下推）
CREATE INDEX idx_policy_vec_tenant      ON policy_vectors (tenant_id);
CREATE INDEX idx_policy_vec_tenant_doc  ON policy_vectors (tenant_id, doc_id);
CREATE INDEX idx_policy_vec_tenant_type ON policy_vectors (tenant_id, doc_type, language);
CREATE INDEX idx_policy_vec_neighbors   ON policy_vectors (tenant_id, doc_uuid, chunk_index);
CREATE INDEX idx_policy_vec_risk        ON policy_vectors (tenant_id, risk_level, source_trust_level);
CREATE INDEX idx_policy_vec_doc_version ON policy_vectors (doc_id, doc_version DESC);

-- 向量近似检索
CREATE INDEX idx_policy_vec_embedding ON policy_vectors
USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);

-- 词法检索
CREATE INDEX idx_policy_vec_tsv ON policy_vectors USING gin(chunk_tsv);

-- JSONB 元数据
CREATE INDEX idx_policy_vec_metadata ON policy_vectors USING gin(metadata);
```

**逐个讲清楚"为什么"**（面试官最爱追问每一个索引给哪条查询用）：

| 索引 | 服务的查询 | 设计原理 |
|---|---|---|
| `(tenant_id)` 单列 | 兜底：所有查询的第一条件 | 保证任何查询都不会全表扫 |
| `(tenant_id, doc_type, language)` | 检索 SQL 的 `docType/language` 可选过滤 | **最左前缀**匹配 WHERE 组合 |
| `(tenant_id, doc_uuid, chunk_index)` | 邻居扩展、neighbors() API | 单文档内按序取邻居，索引即排序 |
| `(tenant_id, risk_level, source_trust_level)` | 安全过滤 | 让 `risk<>'HIGH'` 走索引而非顺序扫 |
| `(doc_id, doc_version DESC)` | 版本治理（只查最新版） | **DESC 索引**支持"取每文档最大版本"的排序需求 |
| HNSW(embedding) | 向量召回 | 近似最近邻，见第四节 |
| GIN(chunk_tsv) | BM25 召回 | GIN 是 tsvector 的标配倒排索引 |
| GIN(metadata) | JSONB 灵活过滤 | btree_gin + pg_trgm 扩展配套 |

**最重要的设计规律——tenant_id 前导列模式**：多租户 RAG 里**几乎每条查询都带 `tenant_id = ?`**，所以所有复合索引都把 tenant_id 放最左。面试讲法：这是 SaaS 向量检索的"分库分表思想在单表内的体现"——不同租户的数据在 B-tree 物理上聚集在一起，单个租户的索引子树很小，天然实现了"逻辑分区"的查询性能。数据量再大一个量级时的演进就是 PG 原生分区表（`PARTITION BY HASH/LIST (tenant_id)`）或换专用向量库。

## 3.3 商品向量表：多粒度分块 + 字段权重（容易被忽略的召回优化）

`product_vectors` 展示了另一种召回优化思路：

```sql
chunk_type   VARCHAR(32)  NOT NULL,   -- title/description/attribute/feature/review/qa
chunk_weight REAL         NOT NULL DEFAULT 1.0,  -- 检索时的权重
-- title 比 review 权重高
```

- **一个商品 → 多个不同粒度的 chunk**（标题/描述/属性/评论/问答各成一行），而不是整篇 embedding；
- **字段权重**（chunk_weight）：标题命中比评论命中更可信——**召回排序在数据建模阶段就注入了业务先验**，比检索后调权更直接；
- 配套索引 `(tenant_id, category_l1, category_l2)`、`(tenant_id, price)` 支持"类目 + 价格区间"的**结构化过滤 + 向量混查**（电商导购场景的标准形态）。

**面试讲法**：这回答了"分块策略怎么设计才召回高"的深层问题——**chunk 不是切出来的是设计出来的**：政策文档按条款语义切（500/50），商品按信息单元切（title/desc/attr），检索目标不同，粒度设计完全不同（完整设计与优化见第七章）。

---

# 四、HNSW 索引参数调优（面试深水区）

> 问到 HNSW 参数的面试官是在筛"真做过向量检索调优"的人。三个参数一个都不能含糊。

```sql
CREATE INDEX idx_policy_vec_embedding ON policy_vectors
USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);
-- 查询时：SET hnsw.ef_search = 40
```

## 4.1 三参数各自的作用与 trade-off

| 参数 | 值 | 作用 | 调大的代价 | 调小的代价 |
|---|---|---|---|---|
| **m** | 16 | 图中每个节点的最大邻居连接数。决定图的"密度" | 索引体积↑、构建时间↑、内存↑ | 图连通性差，**召回率↓**（孤岛） |
| **ef_construction** | 64 | **建索引时**的搜索深度（候选队列长度） | 构建时间明显↑ | 索引质量差，**召回率↓**（不可逆，只能重建） |
| **ef_search** | 40 | **查询时**的搜索深度 | **延迟↑**（要遍历更多节点） | **召回率↓**（可随时调，无需重建） |

## 4.2 项目为什么选这组值（以及怎么讲"调优过程"）

**面试话术模板**：
> "m=16、ef_construction=64 是 pgvector 官方对中小规模（百万级以内 chunk）的推荐起点——m 决定图的连通骨架，我保持在默认 16 是因为租户级数据量下没必要为 +1% 召回付双倍索引内存；ef_construction 从 64 起建索引足够，这个参数影响的是**索引质量的上限**，如果后期发现召回率瓶颈在这，重建索引才生效，所以宁可在建的时候给足。真正在日常调的是 **ef_search**——它免重建、按查询生效，我设 40 是在评测集上做了召回-延迟权衡后的选择：ef_search 从 40 提到 100，MRR 提升不到 1%，但 P95 检索延迟接近翻倍。**调优必须绑定评测数据**，我的 51×2 用例可以在改任何参数后立刻重跑 MRR/nDCG 验证。"

**关键认知（加分点）**：
1. `vector_cosine_ops` 必须与 embedding 模型的度量方式匹配（text-embedding-3 系列归一化后用余弦）——**索引算子选错，相似度语义全错**；
2. HNSW 是**近似**检索——没有"结果绝对正确"这个属性，只有"召回率-延迟"曲线，所以评测里的 MRR 数字必须标注是在哪个 ef_search 下测的（本项目 eval run 记录 embedding model + index version，正是为了这个可复现性）；
3. IVFFlat vs HNSW 的选型题：IVFFlat 建索引快、内存小，但需要先有数据（训练中心点）且召回率对 `probes` 参数更敏感、对增量写入更脆弱；**HNSW 查询快、召回稳、支持流式写入**，适合本项目"文档随时入库"的场景——这是一道真实的高频面试题的完整答案。

---

# 五、BM25 全文索引设计：零成本的词法召回

## 5.1 生成列 + GIN：一列两用

```sql
chunk_tsv tsvector GENERATED ALWAYS AS (
    to_tsvector('english', COALESCE(chunk_text_en, chunk_text))
) STORED,
CREATE INDEX idx_policy_vec_tsv ON policy_vectors USING gin(chunk_tsv);
```

- **GENERATED ... STORED**：tsvector 由 PG 自动维护，**写入时算一次、检索时零解析成本**——不可能出现"原文更新了但索引列忘了同步"的数据一致性问题；
- `COALESCE(chunk_text_en, chunk_text)`：优先索引英语翻译版——**跨语言词法召回**的巧思，中文文档被翻译成英文后可以命中英文 tsquery；
- GIN 索引是 tsvector 的倒排索引标配，支持 `@@` 匹配操作符走索引而非全表扫。

## 5.2 为什么"BM25-only 也能 102/102"——词法通道被严重低估

项目评测报告：BM25-only 与 Hybrid 的 MRR/nDCG 持平（0.90-0.91）。面试讲法：
> "政策客服语料的查询高度术语化（refund window / return policy），BM25 的词频-逆文档频打分在这类语料上非常强；向量通道的增量价值在口语化、改写、跨语言的**长尾**上。所以我的架构决策是：**BM25 是基线而非配角**——demo 环境没有 embedding key 时全靠它扛住 100% 门禁，这既是降级路径也是成本优化路径（低价值查询可以只走词法）。"

## 5.3 PG 之外的补充方案

表结构同时启用了 `pg_trgm`（三元组模糊匹配，typo 容错）和 `btree_gin`（标量列进 GIN 支持混合过滤）——面试可展开：typo 场景（"refnud"）BM25 直接 miss，trigram 相似度可以兜底；这也是"召回率防守"的一个角落。

---

# 六、索引版本发布机制：像发代码一样发索引

> 这是本项目 RAG 工程化里最"商用"的设计之一——**索引是生产资产，必须有灰度、原子切换和回滚能力**。

## 6.1 状态机

```
DRAFT（新文档双写进草稿版本）
  → activateIndex()（校验 ensureIndexed：该版本有 chunk 才允许激活）
     → ACTIVE（检索锁定此版本）
        旧 ACTIVE → SUPERSEDED
  → rollbackIndex()：ACTIVE → ROLLED_BACK，previous → ACTIVE
```

**实现**（`RagGovernanceService`）：

```java
public RagGovernanceDtos.IndexReleaseVO activateIndex(String version, Long actorId) {
    lockTenant(tenantId);                    // 租户级锁防并发发布
    findReleaseForUpdate(tenantId, version);
    ensureIndexed(tenantId, target.getIndexVersion());  // 空索引禁止激活
    ...
}
public String writeIndexVersion(Long tenantId, String fallback) {
    // 新入库文档优先写 DRAFT 版本（双写），激活后切换
    var draft = indexReleaseMapper.selectOne(... eq(status, "DRAFT") ...);
    if (draft != null) return draft.getIndexVersion();
    ...
}
```

## 6.2 解决的三个真实生产问题

1. **检索一致性**：一次检索的两路 SQL 都锁定 `index_version = 活动版本`——**查询期间索引重建不会让同一问题的两次召回来自不同索引**（引用不会漂移）；
2. **零停机切换**：新索引双写构建在 DRAFT 版本，`activate` 只改一行状态记录——**切换是 O(1) 元数据操作，不搬数据**；
3. **可回滚**：发布坏的索引（比如 embedding 模型换了导致维度不一致）一键回退上一版本——**变更管理能力**，不是"删了重建"。

**面试金句**：把"发索引"做成了"发代码"：DRAFT=staging、ACTIVE=prod、SUPERSEDED=历史版本、ROLLBACK=回滚按钮。**很多 RAG 系统的检索质量退化不是算法问题，是"改了分块参数/换了 embedding 模型后，新旧索引数据混在一起无法归因"**——版本化让每次检索质量变化都能定位到具体索引版本。

## 6.3 eval 与索引版本的联动

`AgentEvalRun` 记录 `indexVersion + embeddingModel + queryPlannerVersion + promptVersion + gitCommit` 五元组——**任何一次评测分数都能还原到"哪个代码 + 哪个 prompt + 哪个索引"**，回归归因不靠猜。

---

# 七、文档切割（Chunking）与向量化设计：召回率的地基

> "chunking 怎么设计、参数怎么定、怎么评测"是 RAG 面试必考题。前面五层召回链全部建立在 chunk 之上——**分块是嵌入的上游、检索的输入、引用的载体，分错块全链皆错**。

## 7.1 为什么 chunking 是 RAG 性能的第一决定因素

**检索黄金三角**（先给框架再谈参数）：

| chunk 过小 | chunk 刚好 | chunk 过大 |
|---|---|---|
| 语义不完整："30 天内"单独成块，缺"退货"主语 | 一个 chunk = 一个语义单元（一条政策条款） | 嵌入稀释：一个向量被迫代表多个主题，每个主题的语义信号都被平均掉 |
| 上下文碎片化，邻居扩展调用次数上升 | 检索命中即得完整条款 | 命中后注入浪费 context 预算（3600 字符预算很快被一块耗尽） |
| 存储与检索成本放大 | —— | 边界词干扰：主题 A 的词汇污染主题 B 的相似度计算 |

**面试金句**：chunking 的错误成本是**乘性的**——分错块之后，嵌入、双路召回、RRF、rerank、引用全部在错误粒度上工作，而唯一修复手段是**以新 index_version 全量重嵌入重索引**（嵌入调用是真金白银）。所以 chunking 是 RAG 里 ROI 最高的"事前优化"，调参必须走"改参数 → 新版本索引 → eval 对比 → 切换"的完整流程（见第六章），不能在线上裸改。

## 7.2 项目实现：三级递归降级分块

`DocumentChunkingService` 的策略是**按最大语义单元优先、装不下再降级**：

```
原始文档
  ↓ 第一级：段落切分（\n\n）—— 段落 ≤ chunkSize 就整段成块，语义边界优先
  ↓ 第二级：句子聚合（.!?）—— 长段落按句贪心装填到 chunkSize
  ↓ 第三级：词级硬切（\s+）—— 超长无标点文本按词硬切，单词超限自成一块
  ↓ overlap 拼接：每块头部拼接前块尾部 overlap 字符
```

四个实现要点：

1. **语义边界优先**：能按段落就不按句子、能按句子就不按词——递归降级保证正常文档第一级就停，只有病态输入才会走到词级兜底；
2. **句子级贪心装填保序**（`splitBySentence`）：句子按原文顺序累加，装不下就开新块，不回填、不跳跃；
3. **单词超限自成块**：绝不静默截断丢内容——`current = new StringBuilder(word)` 让超长词独立成块；
4. **chunk_index 严格保序是多处设计的前提**：邻居扩展（`neighbor_prev/next_uuid`）、`(tenant_id, doc_uuid, chunk_index)` 邻居索引、引用展示 `[doc#chunk]`，全部依赖分块顺序与原文一致——单测 `shouldPreserveChunkOrder` 专门锁定这一点。

## 7.3 overlap：断链修复与成本代价

```java
private List<String> applyOverlap(List<String> chunks, int overlap) {
    ...
    var overlapText = prev.length() > overlap ? prev.substring(prev.length() - overlap) : prev;
    result.add(overlapText + " " + chunks.get(i));   // 前块尾部拼接到后块头部
```

- **修复什么**："退货窗口 30 天"（chunk A 结尾）+ "但清仓品除外"（chunk B 开头）——边界处的限定语/转折不断链。这恰是政策文本里**最致命的召回陷阱**：只召回 A 会给出错误的绝对化答案（"所有商品 30 天可退"）；
- **代价**：默认 50/500 = 10% 存储冗余；重复内容两路召回各命中一次（RRF 按 chunkUuid 去重所以不重复计分，但 **embedding 调用按 chunk 数量付费**——overlap 是花钱买召回保险）；
- **业界口径**：10-20% 是常见区间，项目取下限 10%——政策条款边界清晰的语料不需要更高；
- **行为被测试锁定**（`shouldApplyOverlap`）：断言第二块包含第一块尾部 10 字符——**overlap 是契约而非巧合**。

## 7.4 结构感知：chunk 不是裸文本

分块时同步抽取结构元数据，每块产出 `ChunkInfo(chunkIndex, chunkText, section, "en")`：

```java
private String detectSection(String text) {
    var line = text.lines().findFirst().orElse("");
    if (line.matches("^#+\\s+.+") || line.matches("^[A-Z][A-Za-z\\s&/-]{2,50}$")) {
        return line.replaceAll("^#+\\s*", "").trim();   // markdown 标题 或 大写标题行
    }
    return null;
}
```

元数据一路贯通到检索与引用：`section / section_path` 存入向量表 → 引用里展示 "Returns > International" → 用户看到的不是裸文本而是**有章节定位的条款出处**。向量表还为 PDF 来源预留了 `page_number` 列（当前未消费，见 7.9）。

## 7.5 文档级参数覆盖：分块不是全局一刀切

`knowledge_doc` 表自带 `chunk_size / chunk_overlap` 字段（默认 500/50），索引管道**逐文档读取覆盖**：

```sql
`chunk_size`     INT  NOT NULL DEFAULT 500  COMMENT '切分块大小(字符)',
`chunk_overlap` INT  NOT NULL DEFAULT 50   COMMENT '块重叠字符数',
`chunk_count`   INT  NOT NULL DEFAULT 0    COMMENT '实际切分块数',
```

```java
var chunks = chunkingService.chunk(
        doc.getRawContent(),
        doc.getChunkSize() != null ? doc.getChunkSize() : 500,       // 文档级覆盖 > 全局默认
        doc.getChunkOverlap() != null ? doc.getChunkOverlap() : 50);
```

**设计意义**：分块参数是**语料相关的超参数**——政策条款（短、编号化）适合小块，FAQ（问答对自然成段）适合按自然段切，长文指南适合大块。全局默认值保底 + 文档级字段精调，是"分块参数治理"的最小可行形态。`chunk_count` 落库还能做**分块审计**（切出的块数与预期是否一致，异常文档一眼可见）。

## 7.6 两张表两种粒度：分块策略由检索目标反推

| | policy_vectors | product_vectors |
|---|---|---|
| 切分单位 | 语义条款（500/50 递归切分） | 信息单元（title/description/attribute/review/qa 各自成行） |
| chunk_type | 无（都是政策文本） | 有（标识来源字段） |
| 权重 | 无 | `chunk_weight`（title > review） |
| 检索目标 | "条款完整、引用可信" | "字段可信、可加权排序" |
| 反推逻辑 | 政策问答命中后要注入整条款 | 商品导购命中 title 远比命中 review 可信 |

**面试金句**：chunk 不是"切出来"的，是"设计出来"的——**先想清楚检索命中后要注入什么、什么粒度的命中才算可信，再反推分块单元**。项目用两张表结构固化了两种答案；这也是对"分块大小怎么定"这类问题的最高级回答：没有万能值，只有"检索目标 → 粒度设计"的映射。

## 7.7 向量化设计：嵌什么、怎么嵌、怎么管

1. **嵌入内容与索引列解耦**：embedding 嵌的是 `chunk_text` 原文（text-embedding-3 天然多语言，中文问题可直接命中英文文档）；而词法通道的 `chunk_tsv` 生成列取 `COALESCE(chunk_text_en, chunk_text)`（优先英语翻译列）——**语义通道靠模型多语言容错，词法通道靠翻译列对齐**，双通道各用所长；
2. **模型可追溯**：每行记录 `embedding_model`（当前 'text-embedding-3-small'）——**换嵌入模型 = 必须 重建索引**（不同模型的向量空间不可互比）；模型列让"新旧模型向量混在同一表"这种事故可检测，eval 报告的 embedding_model 字段有据可查；
3. **1536 维选型**：text-embedding-3-small 在成本（比 3-large 便宜约一个数量级）、内存（HNSW 内存 ≈ 向量数 × 维度 × 4 字节）、多语言能力三个维度上的平衡点；表结构 `vector(1536)` **定长声明**——维度不匹配的向量直接插入失败，从 schema 层杜绝混用；
4. **批量 + 缓存**：`embedBatch` 单次 API 调用打整批 chunk（摊薄 HTTP 往返与限流成本），Redis MD5+tenant 缓存让相同文本零成本复嵌入（详见第八章管道、第九章缓存）；
5. **空块防御双保险**：写入时 `chunk_length` 落库；检索打包时 `RagContextPacker` 对 `isBlank()` 的块 `continue` 跳过——**空块永远不会进入 context**，即使分块器产生了边界块。

## 7.8 分块质量的评测闭环

chunking 改动怎么验证？三层闭环，一层比一层接近线上：

1. **单元测试锁定边界行为**（`DocumentChunkingServiceTest`，8 个场景）：双换行分段 / 长段落按句切 / overlap 拼接生效 / 短内容单块 / 空输入返回空列表 / 零值参数回退默认 / **chunk 顺序保持** / 语言标签——分块器的行为契约被测试钉死，重构不破坏；
2. **检索评测回归**：改 chunk 参数 → 以新 `index_version` 重嵌重建 → 51×2 eval 用例跑 MRR/nDCG 对比 → 数据决定去留（完整的发布-评测-切换流程见第六章）——**没有评测集的分块调参是玄学**；
3. **运营数据反哺**：`retrieval_count=0` 的 chunk（从未被召回，提示粒度或语料问题）、`KnowledgeHealthService.topFailedQueries` 聚合的 `RAG_NO_RESULT` 失败用例（直接指向分块盲区）、`chunk_count` 与文档长度比对（分块数异常审计）。

## 7.9 尚未实现的分块/嵌入优化（面试"还能怎么优化"弹药）

| 方向 | 项目现状 | 方案与收益 |
|---|---|---|
| **语义分块** | 规则递归切分（段落→句子→词） | 相邻句嵌入相似度骤降处设断点；对无结构长文更准；成本 = 断点检测的额外嵌入调用 |
| **父子分块（small-to-big）** | 邻居窗口（前后各 1 块、0.5 衰减）是它的轻量版 | 索引小块（精准命中）+ 注入父块（完整上下文）；需要父子映射表——检索层补全 vs 索引层建映射，后者更彻底 |
| **Late chunking** | 先切后嵌（块向量缺全文档上下文） | 先整文档过长上下文嵌入模型取 token 级向量、再按块池化——块向量携带全局语境；2024 论文热点，适合长文 |
| **结构化解析** | 正则 `detectSection` | Markdown AST / PDF 表格/标题解析器；`page_number` 列已预留未消费 |
| **自适应块长** | 静态 500/50 | 短句多聚合、长句少聚合的动态窗口；文档级字段已支持按语料试点 |

---

# 八、异步索引管道：写入路径的性能与可靠性设计

## 8.1 完整管道

```
文档创建/更新
  → RagSafetyReviewService.isIndexAllowed(doc)   ← 入库安全审核（投毒拦截）
  → markIndexing(status=2)                        ← 状态机：PUBLISHED→INDEXING
  → DocumentChunkingService.chunk(500/50)         ← 递归分块（设计详见第七章）
  → EmbeddingService.embedBatch(texts)            ← 批量嵌入（一次 API 调用打整批）
  → insertChunks(batchUpdate)                     ← 批量写入 + 邻居指针 + 治理元数据
  → completeIndexing(status=1, chunkCount)        ← 成功回写
  （任何一步失败 → markIndexFailed(status=3, indexError)）
```

## 8.2 五个性能/可靠性设计点（`PolicyIndexService` + `AsyncIndexingConfig`）

1. **`@Async("indexingExecutor")` 独立线程池**——文档入库接口的响应时间**不包含嵌入和写库**（嵌入一批 chunk 可能要几百 ms 到秒级），写读分离的是"用户请求路径 vs 索引构建路径"；

2. **专用线程池参数即背压策略**：

```java
executor.setCorePoolSize(2);   maxPoolSize(4);   queueCapacity(100);
executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
```
   **CallerRunsPolicy 是关键**：队列满（100 篇文档积压）时，新任务**退回调用线程执行**——变相给上游降速，**宁可让入库接口变慢，也不丢任务、不 OOM**。面试讲法：这是"反压（backpressure）在应用层的最简实现"。

3. **TaskDecorator 传播租户上下文**——`TenantContextHolder` 是 ThreadLocal，异步线程默认读不到；装饰器在提交时快照 tenantId、执行时 set、finally 里 clear：

```java
executor.setTaskDecorator(runnable -> {
    var tenantId = TenantContextHolder.get();
    return () -> {
        try { TenantContextHolder.set(tenantId); runnable.run(); }
        finally { TenantContextHolder.clear(); }
    };
});
```
   **没有这一步，异步索引里的租户校验全部 fail-closed 拒绝**（这也是多租户系统做异步的经典坑，面试提这个细节非常加分）。

4. **`embedBatch` 批量嵌入**——N 个 chunk 一次 API 调用，摊薄 HTTP 往返与速率限制开销（OpenAI embed 接口单请求支持上百条）；配合 `EmbeddingService` 的 Redis MD5 缓存，重复文档重索引零嵌入成本；

5. **`batchUpdate` 批量插入**——JDBC batch 一次往返写 N 行；INSERT 时**顺手构建邻居指针**（`neighbor_prev_uuid/next_uuid` 由 chunkUuids 列表前后项直接赋值），邻居扩展不需要二次 UPDATE。

6. **索引状态机三态**（PUBLISHED=1 / INDEXING=2 / INDEX_FAILED=3 + indexError 落库）——失败可观测、可人工重试，`KnowledgeHealthService.health()` 把 `indexFailedDocs` 直接暴露成健康指标。

---

# 九、语义缓存与查询层优化（低成本高杠杆）

## 9.1 三级查询缓存体系（项目实际形态）

```
L1  Embedding 缓存（Redis）        —— EmbeddingService：MD5(text)+tenant key，命中免调嵌入 API
L2  Query 语义缓存（表结构已建）    —— query_cache_vectors：embedding 相似度 > 0.95 直接复用缓存结果
L3  会话记忆（Redis List）         —— RedisChatMemory：12 条滑动窗口
```

**L2 语义缓存表**（`db_vector.sql`）是**上一份文档没覆盖的重要发现**：

```sql
CREATE TABLE query_cache_vectors (
    query_text / query_text_en / query_hash CHAR(32),
    embedding vector(1536),
    retrieval_result JSONB,      -- 缓存的整个检索结果
    result_score REAL,
    hit_count INT, last_hit_at TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,     -- TTL 过期
    UNIQUE (tenant_id, query_hash),    -- 精确去重
    hnsw (embedding)                   -- 相似度召回旧问题
);
-- 设计意图（SQL 注释原文）：
-- "缓存常见用户问题的 embedding,新查询先做相似度搜索,
--  如果有 score > 0.95 的旧问题,直接复用旧问题的检索结果。
--  降低 embedding 调用 + RAG 检索的成本。"
```

**面试讲法**：这是典型的 **RAG-level semantic cache** 设计——与 LLM 层的语义缓存（缓存模型答案）不同，它缓存的是**检索结果**：
- 客服场景问题分布高度集中（"退货政策是什么"一天被问几百次）；
- 相似问题（>0.95 余弦）**跳过整个检索管线**（嵌入调用 + 双路检索 + rerank），P95 从几百 ms 直接降到一次向量查询（~几 ms）；
- `hit_count / last_hit_at` 支持缓存淘汰策略与命中率监控——**缓存好不好要靠指标说话**；
- 精确 `query_hash` 唯一键 + 相似度双通道：完全相同的问题走哈希（O(1)），相似问题走 HNSW；
- **风险边界**（主动讲）：相似 ≠ 等价——"退货政策"和"不能退货的商品"相似度可能很高但答案相反，所以阈值要保守（0.95）、TTL 要短（政策变更后缓存必须过期）、且缓存要带 `index_version`（换索引自动失效——这是演进点）。

## 9.2 检索统计列：为"数据驱动的召回优化"埋的种子

```sql
retrieval_count INT NOT NULL DEFAULT 0,
last_retrieved_at TIMESTAMP,
```

配合视图 `v_vector_storage_stats`（按租户统计 chunk 数/字符数/最后更新）。用途：识别**从未被召回的 chunk**（候选分块参数问题）、识别热点 chunk（语义缓存的种子）、冷数据识别（归档降存储）。面试讲法：性能优化的终点是"让数据告诉你下一步优化哪里"，这些列就是埋点。

---

# 十、Agent 侧性能优化补充清单

> 姊妹篇已覆盖超时/重试/熔断/token 成本，这里补充**没讲过的**，按"延迟、吞吐、资源"归类。

## 10.1 延迟优化

| 设计 | 位置 | 原理 |
|---|---|---|
| **SSE 四类事件流**（status→delta→final→error） | `ChatController` | 用户先看到 "PROCESSING" 状态再等首 token，**感知延迟 < 实际延迟**；翻译路径按句切分补发 delta（`translatedSentences`），避免整段翻译期间长时间无输出 |
| **翻译非阻塞降级** | `TranslationService` | 翻译失败 fallback 原文 + 记录 fallbackReason——**任何辅助能力的失败都不拖慢主响应** |
| **请求内串行最小化** | `ReActAgentService` | 预处理（安全→编排→语言→路由）全是内存级计算；只有嵌入/检索/模型是 IO——关键路径上的 IO 数量被编排压到最少 |
| **SSE emitter 300s 超时 + boundedElastic 调度** | `ChatController` | Flux 订阅切到弹性线程池，Tomcat 工作线程立即释放——**异步 Servlet 释放容器线程**，同等容器线程数支撑更多并发会话 |

## 10.2 吞吐与资源

| 设计 | 位置 | 原理 |
|---|---|---|
| **双数据源连接池隔离** | `PgVectorDataSourceConfig` | PG 向量库独立 Hikari 池（独立 maxPoolSize/minIdle/timeout）——**RAG 检索慢不拖垮业务 MySQL 池**，反之亦然；故障域隔离 |
| **Flyway 双库迁移**（`flyway_schema_history_pgvector` 独立历史表） | 同上 | PG schema 变更与 MySQL 迁移互不干扰 |
| **记账/投影/trace 全异步** | TokenUsageProducer（MQ fire-and-forget）、HelpdeskProjectionScheduler、AgentTraceService(bestEffort) | 关键路径零同步写库；`HelpdeskProjectionRequestedEvent` 事件驱动 + 调度器兜底双保险——工单看板的投影计算不阻塞客服会话 |
| **会话锁防重复计算** | `AgentExecutionGuardService` | 同会话并发请求直接拒绝（而不是排队）——省下重复的整轮 LLM 调用，这也是**成本护栏** |
| **限流的并发层** | `TokenRateLimiter` Layer 3 | 租户级 inflight 上限保护下游 LLM 供应商配额与自身内存 |

## 10.3 一个容易忽略的点：`positiveOr` 上限钳制

```java
private int positiveOr(Integer value, int fallback) {
    return value != null && value > 0 ? Math.min(value, 50) : fallback;
}
```
用户传入的 topK **硬性钳到 50**——防止 Debug API 被传 `topK=100000` 拖垮检索层。**所有用户可控参数都要有服务器侧上限**，这是性能与安全的交集设计。

---

# 十一、数据库与基础设施层的性能设计

1. **读写职责分离**：MySQL（业务/工单/审计/eval/trace）+ PostgreSQL/pgvector（向量+词法检索）+ Redis（记忆/锁/限流/嵌入缓存）——**每种数据放最合适的引擎**，而不是单库硬扛。
2. **批量写入**：索引 `batchUpdate`、评测结果批量插入——N 次往返合并为 1 次。
3. **索引服务于查询形状**：3.2 节的 11 个索引全部对应真实 SQL 的 WHERE/ORDER BY 组合，**没有"以防万一"索引**（每个索引都有写放大成本）。
4. **`updated_at` 触发器自动维护**（`trg_update_timestamp`）——应用层不传审计时间戳，杜绝时钟漂移。
5. **视图做统计**（`v_vector_storage_stats`）——管理后台的存储统计走视图预聚合，不在应用层 COUNT 全表。
6. **Redis TTL 治理**：会话记忆 7 天、嵌入缓存按配置 TTL、限流 QPS 键 1s、预算键 32 天、会话锁 90s——**每个 key 的生命周期都是显式决策**。

---

# 十二、RAG 检索全链路的延迟预算拆解

> 面试被问"你的 RAG 检索 P95 多少？时间花在哪？"——用这张预算表回答（数字为典型量级，实际以 `DebugResponse.elapsed` 落库为准）：

```
Query Planning（规则扩展）        ~0 ms     纯内存
Embedding（缓存命中）             ~1 ms     Redis GET
Embedding（缓存未命中）           50-150 ms OpenAI API ← 大头①，所以才有 L1 缓存
Vector 检索（HNSW, ef_search=40）  5-20 ms   PG 索引扫描
BM25 检索（GIN tsv）              3-10 ms   PG 索引扫描
RRF 融合                          ~0 ms     纯内存
Rerank（BGE cross-encoder, N≤20） 50-200 ms 外部 API ← 大头②，所以才有条件跳过
邻居扩展（复合索引单查）           1-3 ms × K
Context Packing                   ~0 ms     纯内存
─────────────────────────────────────────────
典型 P95（缓存命中+跳过rerank）   ~30 ms    ← 语义缓存的目标区间
典型 P95（全管线）                ~150-400 ms
```

**结论话术**：两个大头（嵌入 API、rerank API）都是**外部调用**——所以优化主线是"减少外部调用次数"（L1/L2 缓存、rerank 条件跳过、批量嵌入）而不是"优化内部计算"（HNSW/RRF/打包都是微秒到毫秒级）。**先 profile 再优化，优化打在外部 IO 上**——这句话本身就是面试得分点。

---

# 十三、性能类面试问答框架

**Q1：RAG 召回率低，你怎么系统性排查和优化？**
> 按五层链逐层定位，每层有独立证据：
> ① 查询层：看 query plan——问题词和文档词是否 vocabulary mismatch？→ 查询扩展/改写/跨语言翻译；
> ② 召回层：看 DebugResponse 的 vector/bm25 两组候选——目标 chunk 在哪一路漏了？只在向量漏→嵌入模型/ef_search 问题；只在 BM25 漏→分词/术语问题；都漏→分块或语料覆盖问题；
> ③ 融合层：目标 chunk 两路都召回但排不上来？→ RRF k 值 / 引入 rerank；
> ④ 精排层：rerank 后掉了？→ reranker 模型与语料域不匹配；
> ⑤ 上下文层：召回了但答案残缺？→ 邻居扩展窗口。
> 每次改动跑 51×2 用例回归 MRR/nDCG——**没有评测集的召回优化是玄学**。

**Q2：向量检索选 HNSW 还是 IVFFlat？参数怎么调？**
> 见第四节完整话术。核心：HNSW 适合"流式写入+在线低延迟+高召回"；参数三件套 m/ef_construction/ef_search 各管什么、哪个可在线调、哪个要重建；调优必须绑定 MRR 数据。

**Q3：RAG 系统的延迟怎么降？**
> 先 profile 分解（第十二节预算表），再打大头：外部 API（嵌入/rerank）→ 三级缓存（嵌入缓存/语义缓存/批量）→ 内部只做预算控制（context 3600、topK 钳 50）。主动讲 trade-off：语义缓存的相似度风险（0.95 阈值 + TTL + index_version 失效）。

**Q4：多租户 RAG，数据量涨 100 倍怎么办？**
> 分层演进：① 现在的 tenant 前导复合索引已经是逻辑分区；② 下一步 PG 原生分区 `PARTITION BY HASH(tenant_id)`（索引与查询透明）；③ 向量检索换专用引擎（Milvus/Qdrant，它们原生支持 partition + 标量过滤）；④ 检索服务独立部署（连接池已经隔离，天然可拆）。**架构演进每一步都有触发条件（QPS/数据量），不是为未来过度设计**。

**Q5：文档索引更新会不会影响在线检索？**
> 双向影响都要答：写侧——异步管道 + 批量写 + 状态机不阻塞用户；读侧——索引版本机制让检索锁定 ACTIVE 版本，DRAFT 双写构建，激活是 O(1) 元数据切换，坏版本可回滚。**索引是生产资产，要灰度要回滚**。

**Q6：怎么发现"哪些 chunk 从来没被召回过"？**
> `retrieval_count / last_retrieved_at` 统计列 + eval 失败用例的 topFailedQueries（`KnowledgeHealthService` 已实现按 case_code/intent/failure_category 聚合 Top10 失败）——**运营数据反哺分块参数与语料补全**。

---

# 十四、RAG 全栈面试题库（30 问速答卡）

> 本章把前十三章的设计要点**提炼成面试速答卡**：每题先给 30 秒答题框架，再给项目证据锚点（对应章节）。面试时按"框架 → 项目细节 → 主动讲边界/演进"三段式作答。
> 标 ★ 的是 2026 面经中出题密度最高的题。

## A. 分块与向量化（Chunking / Embedding）

**Q1 ★：RAG 分块（chunking）策略怎么设计？chunk 大小怎么定？**
> 框架：先给"检索黄金三角"——过小块语义不完整、过大块嵌入稀释且浪费 context 预算；**没有万能值，chunk 大小由检索目标反推**（命中后要注入什么粒度才算可信）。
> 项目：政策文档按条款递归切（段落→句子→词级兜底，500/50）；商品按信息单元切（title/desc/attr/review 各成行 + chunk_weight 权重）——两张表两种粒度。（7.2 / 7.6 / 3.3）

**Q2 ★：overlap 是干什么的？为什么需要？**
> 框架：修复"答案被 chunk 边界切断"——限定语/转折丢失会产生**事实性残缺**（"30 天可退"丢了"但清仓品除外"），比召回不到更危险。代价是存储冗余与嵌入成本（10-20% 常见区间）。
> 项目：50/500 = 10% 下限（政策条款边界清晰）；overlap 行为被单测锁定为契约。（7.3）

**Q3：语义分块（semantic chunking）了解吗？比固定分块好在哪？**
> 框架：相邻句嵌入相似度骤降处设断点，块边界跟随语义而非字符数；适合无结构长文。代价是断点检测要额外嵌入调用。固定分块的优势是**简单、可预算、可评测**——结构化语料（政策/FAQ）用递归分块足够。
> 项目边界：当前递归分块是"结构感知的规则版"，语义分块在演进路线里（7.9），切换前必须跑同一评测集对比 MRR。

**Q4 ★：嵌入（embedding）模型怎么选型？换模型要注意什么？**
> 框架：四维度——多语言能力（跨境必备）、维度（内存/速度 vs 精度）、成本、最大输入长度。**换模型 = 向量空间整体失效 = 必须全量重建索引**，新旧向量不可互比。
> 项目：text-embedding-3-small（成本/内存/多语言平衡点）；每行落 `embedding_model` 列让混用可检测；表结构 `vector(1536)` 定长声明从 schema 层杜绝维度混插。（7.7）

**Q5：embedding 调用有哪些成本/性能优化手段？**
> 框架：① 缓存（内容哈希做 key）；② 批量（一次 API 打整批摊薄往返）；③ 增量（文档 diff 只重嵌变更块）；④ 复用（query 级语义缓存）。
> 项目：四条全部有落点——Redis MD5+tenant 缓存、`embedBatch`、`content_hash` 预留增量、`query_cache_vectors` 表。（7.7 / 8.2 / 9.1）

**Q6：什么是 late chunking？解决什么问题？**
> 框架：传统"先切后嵌"让块向量缺乏全文档上下文；late chunking 先让长上下文嵌入模型产出 token 级向量池，再按块池化——块向量携带全局语境（代词指代、跨段主题不丢）。
> 项目边界：未实现，是演进路线之一；适用长文指南类语料，政策短条款收益有限。（7.9）

## B. 混合检索与排序（Hybrid / Fusion / Rerank）

**Q7 ★：为什么要混合检索（Hybrid Search）？纯向量不行吗？**
> 框架：两路盲区互补——向量擅长同义/改写/跨语言，但对**精确词**（单号、SKU、术语）召回差；BM25 相反。另外**降级价值**：嵌入服务故障时 BM25 独立扛住检索。
> 项目：双通道独立 topK；评测显示 BM25-only 也能过 102/102 门禁——词法通道是基线而非配角。（二、第②层）

**Q8 ★：RRF 是什么？为什么不用分数加权融合？**
> 框架：`score = Σ 1/(k + rank)`。三点：量纲免疫（余弦与 ts_rank 不可比）、双确认加权（两路都命中的自然置顶）、k=60 平滑头部抖动。加权融合要调多组权重且对分数分布敏感。
> 项目：`RrfFusion` 纯内存微秒级——相当于免费的"投票精排"。（二、第③层）

**Q9 ★：Cross-Encoder 和 Bi-encoder 的区别？rerank 为什么放在检索后而不是检索时？**
> 框架：Bi-encoder 分别编码 query/doc（可离线建索引，快但精度受限）；Cross-encoder 拼接后过模型（精度高但 O(N) 逐对计算）——所以只能对召回后的小候选集（top 几十）用，**两阶段 = 速度与精度的分治**。
> 项目：只对 RRF 后 top 候选 rerank；候选 ≤ topN 直接跳过；失败降级 fallback-rrf 且 mode 落库。（二、第④层）

**Q10：topK 怎么定？越大越好吗？**
> 框架：召回 K 与 rerank/注入成本正相关；太小丢召回，太大稀释精度（rerank 的 N^2 倾向）并挤占 context 预算。**用 recall@K 曲线找拐点**，且 topK 是模式/语料相关超参。
> 项目：vectorTopK/bm25TopK/rerankTopN 独立可配；用户传入 topK 服务端硬钳 50——防 Debug API 打垮检索层。（3.2 / 10.3）

**Q11：邻居窗口扩展（context expansion）解决什么问题？和父子分块什么区别？**
> 框架：解决"命中块语义残缺"——取命中 chunk 前后各一块拼上下文。区别：邻居窗口是**检索期**的轻量补全（无额外映射表）；父子分块是**索引期**建好 small→big 映射，命中小块注入父块，更彻底但要改索引结构。
> 项目：`neighbor_prev/next_uuid` + `(tenant_id, doc_uuid, chunk_index)` 索引，邻居单查询即得；0.5 分数衰减保证邻居不压本体。（二、第⑤层 / 7.9）

## C. 索引底层（HNSW / BM25 / Schema）

**Q12 ★：HNSW 原理和参数？和 IVFFlat 怎么选？**
> 框架：HNSW 是分层跳表式近似最近邻图。三参数：m=图的邻居密度（索引体积 vs 连通性）、ef_construction=建索引质量（**不可在线调，要重建**）、ef_search=查询深度（**可在线调，召回-延迟旋钮**）。IVFFlat 聚类分桶：建索引快内存小，但需先有数据训练中心点、增量写入脆弱、对 probes 敏感——**流式写入+在线低延迟+高召回选 HNSW**。
> 项目：m=16 / ef_construction=64 / ef_search=40，选择理由 + "调 ef_search 看 MRR 与 P95 的权衡数据"话术。（四、4.1 / 4.2）

**Q13：相似度度量用哪个？为什么？**
> 框架：跟随嵌入模型的训练度量——OpenAI text-embedding-3 系列归一化后余弦；未归一化用内积；欧氏适合量纲敏感场景。**索引算子（vector_cosine_ops）必须与度量匹配，选错则相似度语义全错**。
> 项目：`USING hnsw (embedding vector_cosine_ops)`，查询用 `1 - (embedding <=> ?::vector)`。（4.2）

**Q14 ★：BM25 在 RAG 里怎么实现？中文怎么处理？**
> 框架：BM25 是词频-逆文档频打分；PG 里 tsvector + GIN 倒排索引 + ts_rank 免外部组件实现。中文难点：默认分词器不切中文——方案：zhparser 扩展、翻译列、或 bigram。
> 项目：`chunk_tsv` 生成列（STORED 保证一致性）+ `COALESCE(chunk_text_en, chunk_text)` 优先索引英语翻译列（跨语言词法召回）；降级路径里对中文查询做 bigram 切分内存打分。（五、5.1 / 5.2）

**Q15：你的向量表 schema 里为什么有那么多非向量字段？**
> 框架：RAG 向量表 = 检索单元 + **证据元数据**（来源/章节/版本）+ **治理锚点**（信任等级/风险/生效期/索引版本）+ 运营统计。检索 SQL 内联 `risk_level<>'HIGH'` 过滤——不可信文档进不了候选池，比检索后过滤更快也更安全。
> 项目：policy_vectors 五组列设计的逐组讲解。（3.1）

**Q16：为什么要建 tenant_id 前导的复合索引？**
> 框架：多租户查询几乎全部 `WHERE tenant_id = ?`——前导列让每租户数据物理聚集（单表内实现逻辑分区），单租户索引子树小；这也是"租户分区"的前置形态。
> 项目：11 个索引逐个对应真实查询形状（邻居复合索引、`doc_version DESC`、风险过滤索引），无一多余。（3.2）

## D. 召回质量与幻觉治理

**Q17 ★：RAG 怎么减少幻觉？**
> 框架：四层——检索提供真实证据（工具化 RAG）；**证据不足主动拒答**（no-answer 能力：敢说"我没证据"）；citation 全链路可追溯（检索分→引用→文档版本）；忠实度校验进评测门禁。
> 项目：证据四级 SUFFICIENT/PARTIAL/WEAK/NONE + 结构化拒答原因；no-answer 评测 100%、citation coverage 100%。（姊妹篇 6.4）

**Q18 ★：怎么评估 RAG 效果？MRR 和 nDCG 怎么算、区别是什么？**
> 框架：MRR = 首个相关结果排名倒数的均值（关注"第一个对的"）；nDCG@K = 位置折损归一化的累计增益（关注整个排序列表的质量 + 分级相关性）。召回率 recall@K = 前K里相关结果覆盖比。**评测集要先分层**：该拒答的（no-answer）、该答的（citation）、攻击类（poisoning）。
> 项目：51 用例 × 2 租户 × 4 检索模式回归，每 run 记录 index/embedding/prompt/git 五元组保证可复现。（姊妹篇 6.2 / 6.3）

**Q19：RAG 召回率低怎么系统性排查？**
> 框架：五层链逐层定位（查询理解→双路召回→融合→精排→上下文），每层看 DebugResponse 的独立证据定位漏在哪一层——向量漏→嵌入/ef_search；BM25 漏→分词；都漏→分块或语料；召回了排不上→融合/rerank。
> 项目：DebugResponse 四组候选 + 评测回归，改动必须用数据验证。（13.Q1）

**Q20：引用（citation）怎么设计才可信？**
> 框架：引用不是"贴个链接"——要带 chunk 级定位（doc#chunk）、来源与章节路径、支撑分数、证据等级、文档版本；并且**忠实度可校验**（答案声明与引用文本的词法/语义覆盖）。
> 项目：Citation 含 12 个字段；CitationFaithfulnessChecker 词法覆盖 <40% 判 fail 进评测门禁。（姊妹篇 6.4 / 6.5）

**Q21：知识库被投毒怎么办？（间接 Prompt Injection）**
> 框架：RAG 攻击面不只来自用户输入，还来自知识库本身——被投毒文档会在数十次对话里持续放大危害。防线：入库扫描（注入指令/诱导越权/隐藏 HTML/密钥/零宽字符）→ HIGH 风险检索期 SQL 排除 → 人工审核队列。
> 项目：RagSafetyScanner 8 类规则 + risk_level SQL 过滤 + RagSafetyReview 审核流；poisoning block 评测 100%。（姊妹篇 6.6）

## E. 工程与运营

**Q22 ★：知识文档更新了，索引怎么跟着更新？会不会检索到旧政策？**
> 框架：写侧异步管道（状态机 PUBLISHED→INDEXING→FAILED，不阻塞用户）+ 读侧**索引版本机制**：新内容双写进 DRAFT 版本，激活是 O(1) 元数据切换，检索锁定 ACTIVE 版本——查询期间索引重建不会让引用漂移；坏版本一键回滚。**文档时效另有 effective_from/to 生效窗兜底**。
> 项目：activate/rollback/ensureIndexed 全流程。（六、8）

**Q23 ★：语义缓存是什么？有什么风险？**
> 框架：缓存"问题→检索结果"（甚至"问题→答案"），新查询先相似度匹配旧问题，超阈值直接复用——跳过整条检索管线。风险：**相似 ≠ 等价**（"退货政策"vs"不能退货的商品"相似度高但答案相反），所以阈值保守（0.95）、TTL 短、缓存要绑定 index_version 失效、命中率要可观测。
> 项目：query_cache_vectors 表（精确 hash 唯一键 + HNSW 相似召回双通道 + hit_count/expires_at）。（9.1）

**Q24：跨语言 RAG 怎么做？中文问题怎么命中英文文档？**
> 框架：三种方案——多语言嵌入模型（语义通道）、翻译查询或翻译文档（词法通道）、双语查询扩展。不必三选一，**双通道各配各的**：语义通道靠模型多语言容错嵌原文；词法通道靠文档侧翻译列对齐。
> 项目：embedding 嵌 chunk_text 原文，BM25 的 tsvector 取 `COALESCE(chunk_text_en, chunk_text)`；查询扩展中英双语同加。（7.7 / 5.1 / 二、第①层）

**Q25：多租户 RAG 怎么隔离？数据量涨 100 倍怎么办？**
> 框架：隔离=SQL 强制 tenant 过滤 + 所有索引前导 tenant_id + 缓存 key 带 tenant。扩展四级演进：前导索引逻辑分区 → PG 原生分区表 → 专用向量库（partition + 标量过滤）→ 检索服务独立部署。**每级演进有触发条件**。
> 项目：检索 SQL 全带 tenant_id；双数据源连接池已隔离（拆服务零改造）。（3.2 / 13.Q4 / 10.2）

**Q26：为什么把 RAG 做成 Agent 的工具（Agentic RAG），而不是每轮先检索再生成？**
> 框架：无脑前置检索的问题——非政策会话白白付出检索成本与延迟；检索上下文还会挤占窗口、引入无关噪声。工具化后：**模型按需判断是否检索**，且检索入口受 specialist 白名单管辖（安全），成本受控（非政策意图零检索开销）。
> 项目：refundPolicyRAG 只在 policy_rag specialist 白名单里。（姊妹篇 6.3）

**Q27：RAG 检索的延迟花在哪？怎么优化？**
> 框架：先 profile 分解再打大头——两个大头都是外部 API（嵌入 50-150ms、rerank 50-200ms），内部计算（HNSW/RRF/打包）都是 ms 级。所以优化主线是**减少外部调用次数**（三级缓存/批量/条件跳过），不是优化内部计算。
> 项目：延迟预算表全链路拆解。（十二）

**Q28：检索结果怎么注入 prompt？全塞进去行不行？**
> 框架：不行——context 窗口是稀缺资源，要**预算化打包**：字符预算内按相关性贪心装填、单引用截断、`[doc#chunk]` 标记对齐引用、证据分级附拒答原因。塞太多既烧钱又稀释注意力（lost in the middle）。
> 项目：3600 字符预算 + 每引用 360 截断 + evidence level。（姊妹篇 6.4 / RagContextPacker）

**Q29：RAG 里的评测用例从哪来？怎么维护？**
> 框架：三个来源——业务日志挖掘（真实分布）、领域专家构造（覆盖政策条款矩阵）、攻击红队构造（注入/投毒/跨租户）。维护：用例带 dataset_kind/version，变更走发布流程；**失败用例聚合驱动语料补全**（topFailedQueries）。
> 项目：200 CONTRACT + 51×2 RAG 用例、数据集版本门禁（GOLD 需人工审核发布）、失败 Top10 聚合。（姊妹篇 6.2 / 9.2）

**Q30 ★：RAG vs 微调，什么时候用哪个？**
> 框架：RAG 胜在时效（改文档即生效）、可追溯（citation 指到版本）、多租户（每租户独立库）；微调胜在固定领域风格、术语理解、小模型蒸馏。商用客服**必须可追溯**，所以 RAG 为主、微调为辅，两者不互斥。
> 项目：租户政策各不相同决定了"按租户出模型"不可行；退款政策按周变更决定训练周期跟不上。（姊妹篇 7.6）

---

## 速答卡使用心法

1. **先框架后细节**：任何 RAG 问题都先归位到"五层链 + 索引设计 + 工程运营"三个坐标系之一，再展开——显得有体系而非背题。
2. **每题钉一个数字**：MRR 0.90-0.91 / no-answer 100% / ef_search 40 / 500/50 / 0.95 阈值——数字是"真做过"的信号。
3. **主动讲边界**：每类题答完补一句"项目没做的是 X，什么条件下做、怎么做"（7.9 / 十五）——区分"知道天花板"和"没想过天花板"。
4. **警惕追问链**：Q1→Q2→Q7→Q8→Q9→Q12 是一条自然的深挖链（分块→融合→排序→底层索引），面试官常按链追问，按本文顺序背正好。

---

# 十五、尚未实现的性能优化路线图

> 面试"你觉得这个项目性能上还能怎么优化"的弹药库，按 ROI 排序：

| # | 优化 | 预期收益 | 实现要点 |
|---|---|---|---|
| 1 | **激活语义缓存**（表已建未接线） | 热点问题 P95 从百 ms 级 → 十 ms 级；嵌入/检索成本大降 | 相似度>0.95 复用 + TTL + index_version 失效 + 命中率指标 |
| 2 | **嵌入式查询缓存与 PG 检索并行化** | 双路召回 vector/bm25 现为顺序执行，改 `Mono.zip` 并行 | 总延迟取 max 而非 sum，约省 30-40% |
| 3 | **LLM 层语义缓存** | 相似问题直接复用答案（客服场景命中率极高） | 需要答案级别的失效策略（政策变更即失效），风险高于检索缓存 |
| 4 | **topK 两段式**：粗召回 50 → rerank 后 neighbor 查询只对 top 5 | 减少无效邻居查询 | 现在 neighbor 查询对每个 reranked 结果都执行 |
| 5 | **流式首句翻译** | 翻译路径首字节延迟从"整段完成"降到"首句完成" | 已按句切分 delta，翻译也可句级流水线化 |
| 6 | **HNSW ef_search 自适应**：简单查询（BM25 高分）低 ef，复杂查询高 ef | 延迟-召回动态平衡 | 需要查询难度信号（意图/查询长度） |
| 7 | **PG 分区表 + 专用向量库演进** | 100 倍数据量下的横向扩展 | 见 Q4 触发条件 |
| 8 | **嵌入式增量更新管道**：文档 diff 后只重嵌入变更 chunk（content_hash 已有） | 重索引成本从全文档降到变更块 | content_hash 正是为增量对比准备的 |
| 9 | **retrieval_count 冷热分层**：高频 chunk 进热缓存（Redis/内存） | 热点 chunk 免 PG 查询 | 统计列已埋点 |
| 10 | **GPU rerank 本地化**：BGE cross-encoder 部署到本地 GPU | rerank 延迟 50-200ms → <20ms，且去掉外部依赖 | 当前走 HTTP API |
| 11 | **分块策略演进**：语义分块 / 父子分块（small-to-big）/ late chunking | 长文与无结构语料的召回率上限 | 完整分析与实现要点见 7.9 节 |

---

## 附：本文档的两份姊妹文档

- `docs/agent-engineer-interview-guide.md` —— 面试全景（架构/安全/评测/题库/知识缺口）
- `docs/agent-rag-performance-optimization.md` —— 本篇（召回率五层链/索引设计/性能优化）

*本文档基于以下源文件实读：`sql/db_vector.sql`（建表+索引+语义缓存表）、`db/migration/mysql/V1__core_schema.sql`（knowledge_doc 分块参数字段）、`PolicyIndexService`、`AsyncIndexingConfig`、`PgVectorDataSourceConfig`、`KnowledgeHealthService`、`RagGovernanceService`（索引发布/回滚）、`HybridRagService`、`RagQueryPlanningService`、`RrfFusion`、`CrossEncoderReranker`、`RagContextPacker`、`DocumentChunkingService`、`DocumentChunkingServiceTest`（分块单测 8 场景）、`EmbeddingService`、`ReActAgentService`、`ChatController`，生成于 2026-11-14。*
