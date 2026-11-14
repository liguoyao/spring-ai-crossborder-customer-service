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
- [七、异步索引管道：写入路径的性能与可靠性设计](#七异步索引管道写入路径的性能与可靠性设计)
- [八、语义缓存与查询层优化（低成本高杠杆）](#八语义缓存与查询层优化低成本高杠杆)
- [九、Agent 侧性能优化补充清单](#九agent-侧性能优化补充清单)
- [十、数据库与基础设施层的性能设计](#十数据库与基础设施层的性能设计)
- [十一、RAG 检索全链路的延迟预算拆解](#十一rag-检索全链路的延迟预算拆解)
- [十二、性能类面试问答框架](#十二性能类面试问答框架)
- [十三、尚未实现的性能优化路线图](#十三尚未实现的性能优化路线图)

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

**面试讲法**：这回答了"分块策略怎么设计才召回高"的深层问题——**chunk 不是切出来的是设计出来的**：政策文档按条款语义切（500/50），商品按信息单元切（title/desc/attr），检索目标不同，粒度设计完全不同。

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

# 七、异步索引管道：写入路径的性能与可靠性设计

## 7.1 完整管道

```
文档创建/更新
  → RagSafetyReviewService.isIndexAllowed(doc)   ← 入库安全审核（投毒拦截）
  → markIndexing(status=2)                        ← 状态机：PUBLISHED→INDEXING
  → DocumentChunkingService.chunk(500/50)         ← 递归分块
  → EmbeddingService.embedBatch(texts)            ← 批量嵌入（一次 API 调用打整批）
  → insertChunks(batchUpdate)                     ← 批量写入 + 邻居指针 + 治理元数据
  → completeIndexing(status=1, chunkCount)        ← 成功回写
  （任何一步失败 → markIndexFailed(status=3, indexError)）
```

## 7.2 五个性能/可靠性设计点（`PolicyIndexService` + `AsyncIndexingConfig`）

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

# 八、语义缓存与查询层优化（低成本高杠杆）

## 8.1 三级查询缓存体系（项目实际形态）

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

## 8.2 检索统计列：为"数据驱动的召回优化"埋的种子

```sql
retrieval_count INT NOT NULL DEFAULT 0,
last_retrieved_at TIMESTAMP,
```

配合视图 `v_vector_storage_stats`（按租户统计 chunk 数/字符数/最后更新）。用途：识别**从未被召回的 chunk**（候选分块参数问题）、识别热点 chunk（语义缓存的种子）、冷数据识别（归档降存储）。面试讲法：性能优化的终点是"让数据告诉你下一步优化哪里"，这些列就是埋点。

---

# 九、Agent 侧性能优化补充清单

> 姊妹篇已覆盖超时/重试/熔断/token 成本，这里补充**没讲过的**，按"延迟、吞吐、资源"归类。

## 9.1 延迟优化

| 设计 | 位置 | 原理 |
|---|---|---|
| **SSE 四类事件流**（status→delta→final→error） | `ChatController` | 用户先看到 "PROCESSING" 状态再等首 token，**感知延迟 < 实际延迟**；翻译路径按句切分补发 delta（`translatedSentences`），避免整段翻译期间长时间无输出 |
| **翻译非阻塞降级** | `TranslationService` | 翻译失败 fallback 原文 + 记录 fallbackReason——**任何辅助能力的失败都不拖慢主响应** |
| **请求内串行最小化** | `ReActAgentService` | 预处理（安全→编排→语言→路由）全是内存级计算；只有嵌入/检索/模型是 IO——关键路径上的 IO 数量被编排压到最少 |
| **SSE emitter 300s 超时 + boundedElastic 调度** | `ChatController` | Flux 订阅切到弹性线程池，Tomcat 工作线程立即释放——**异步 Servlet 释放容器线程**，同等容器线程数支撑更多并发会话 |

## 9.2 吞吐与资源

| 设计 | 位置 | 原理 |
|---|---|---|
| **双数据源连接池隔离** | `PgVectorDataSourceConfig` | PG 向量库独立 Hikari 池（独立 maxPoolSize/minIdle/timeout）——**RAG 检索慢不拖垮业务 MySQL 池**，反之亦然；故障域隔离 |
| **Flyway 双库迁移**（`flyway_schema_history_pgvector` 独立历史表） | 同上 | PG schema 变更与 MySQL 迁移互不干扰 |
| **记账/投影/trace 全异步** | TokenUsageProducer（MQ fire-and-forget）、HelpdeskProjectionScheduler、AgentTraceService(bestEffort) | 关键路径零同步写库；`HelpdeskProjectionRequestedEvent` 事件驱动 + 调度器兜底双保险——工单看板的投影计算不阻塞客服会话 |
| **会话锁防重复计算** | `AgentExecutionGuardService` | 同会话并发请求直接拒绝（而不是排队）——省下重复的整轮 LLM 调用，这也是**成本护栏** |
| **限流的并发层** | `TokenRateLimiter` Layer 3 | 租户级 inflight 上限保护下游 LLM 供应商配额与自身内存 |

## 9.3 一个容易忽略的点：`positiveOr` 上限钳制

```java
private int positiveOr(Integer value, int fallback) {
    return value != null && value > 0 ? Math.min(value, 50) : fallback;
}
```
用户传入的 topK **硬性钳到 50**——防止 Debug API 被传 `topK=100000` 拖垮检索层。**所有用户可控参数都要有服务器侧上限**，这是性能与安全的交集设计。

---

# 十、数据库与基础设施层的性能设计

1. **读写职责分离**：MySQL（业务/工单/审计/eval/trace）+ PostgreSQL/pgvector（向量+词法检索）+ Redis（记忆/锁/限流/嵌入缓存）——**每种数据放最合适的引擎**，而不是单库硬扛。
2. **批量写入**：索引 `batchUpdate`、评测结果批量插入——N 次往返合并为 1 次。
3. **索引服务于查询形状**：3.2 节的 11 个索引全部对应真实 SQL 的 WHERE/ORDER BY 组合，**没有"以防万一"索引**（每个索引都有写放大成本）。
4. **`updated_at` 触发器自动维护**（`trg_update_timestamp`）——应用层不传审计时间戳，杜绝时钟漂移。
5. **视图做统计**（`v_vector_storage_stats`）——管理后台的存储统计走视图预聚合，不在应用层 COUNT 全表。
6. **Redis TTL 治理**：会话记忆 7 天、嵌入缓存按配置 TTL、限流 QPS 键 1s、预算键 32 天、会话锁 90s——**每个 key 的生命周期都是显式决策**。

---

# 十一、RAG 检索全链路的延迟预算拆解

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

# 十二、性能类面试问答框架

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
> 先 profile 分解（第十一节预算表），再打大头：外部 API（嵌入/rerank）→ 三级缓存（嵌入缓存/语义缓存/批量）→ 内部只做预算控制（context 3600、topK 钳 50）。主动讲 trade-off：语义缓存的相似度风险（0.95 阈值 + TTL + index_version 失效）。

**Q4：多租户 RAG，数据量涨 100 倍怎么办？**
> 分层演进：① 现在的 tenant 前导复合索引已经是逻辑分区；② 下一步 PG 原生分区 `PARTITION BY HASH(tenant_id)`（索引与查询透明）；③ 向量检索换专用引擎（Milvus/Qdrant，它们原生支持 partition + 标量过滤）；④ 检索服务独立部署（连接池已经隔离，天然可拆）。**架构演进每一步都有触发条件（QPS/数据量），不是为未来过度设计**。

**Q5：文档索引更新会不会影响在线检索？**
> 双向影响都要答：写侧——异步管道 + 批量写 + 状态机不阻塞用户；读侧——索引版本机制让检索锁定 ACTIVE 版本，DRAFT 双写构建，激活是 O(1) 元数据切换，坏版本可回滚。**索引是生产资产，要灰度要回滚**。

**Q6：怎么发现"哪些 chunk 从来没被召回过"？**
> `retrieval_count / last_retrieved_at` 统计列 + eval 失败用例的 topFailedQueries（`KnowledgeHealthService` 已实现按 case_code/intent/failure_category 聚合 Top10 失败）——**运营数据反哺分块参数与语料补全**。

---

# 十三、尚未实现的性能优化路线图

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

---

## 附：本文档的两份姊妹文档

- `docs/agent-engineer-interview-guide.md` —— 面试全景（架构/安全/评测/题库/知识缺口）
- `docs/agent-rag-performance-optimization.md` —— 本篇（召回率五层链/索引设计/性能优化）

*本文档基于以下源文件实读：`sql/db_vector.sql`（建表+索引+语义缓存表）、`PolicyIndexService`、`AsyncIndexingConfig`、`PgVectorDataSourceConfig`、`KnowledgeHealthService`、`RagGovernanceService`（索引发布/回滚）、`HybridRagService`、`RagQueryPlanningService`、`RrfFusion`、`CrossEncoderReranker`、`RagContextPacker`、`DocumentChunkingService`、`EmbeddingService`、`ReActAgentService`、`ChatController`，生成于 2026-11-14。*
