# 召回评测回归集（Retrieval Recall Eval）

这套评测用「问题 → 应命中的真实片段」量化检索质量。它覆盖问题理解、多查询召回、RRF 与重排，
输出 HitRate@K、meanRecall@K 和 MRR，适合在分块、embedding、召回或 rerank 改动前后做同库对比。

## 为什么模板默认禁用

`retrieval-eval.json` 不再假设每个部署环境都存在某些示例文档。内置三条是结构模板，均为
`"enabled": false`，所以普通 `mvn test` 不访问数据库、Milvus 或在线模型，也不会因缺少虚构文档误报。

建立真值集时，复制模板用例，替换问题、真实文档 ID 和关键原文，然后改为 `"enabled": true`。
显式开启真实评测但没有任何启用用例时，测试会失败并提示先建立真值集；这是为了防止空数据产生“评测通过”的假象。

## 文件

- `retrieval-eval.json`：版本化真值集与模板。
- `RetrievalMetrics.java` / `RetrievalMetricsTest.java`：纯指标与默认 CI 单测。
- `RetrievalRecallEvalTest.java`：依赖真实 DB、向量库和模型的 opt-in 评测入口。

## v2 用例格式

```jsonc
{
  "id": "refund-policy-follow-up", // 稳定用例 ID，便于追踪回归
  "enabled": true,
  "question": "那退款时限呢？",
  "history": "用户：A 类合同如何取消？\n助手：……", // 可空；用于指代消解/多轮问题
  "kbIds": [128, 129],             // 本用例允许检索的真实文档 ID
  "topK": 6,
  "tags": ["多轮", "条款"],
  "expect": [
    {
      "id": "refund-deadline",
      "knowledgeBaseId": 128,
      "sourceNameContains": "退款政策",
      "contentContains": "七个工作日"
    }
  ],
  "exclude": [
    {
      "id": "other-tenant-policy",
      "knowledgeBaseId": 999
    }
  ],
  "notes": "真值来源与标注原因"
}
```

结构化真值项支持三个条件：`knowledgeBaseId`、`sourceNameContains`、`contentContains`。
同一个对象里填写多个条件时按 **AND** 匹配；只填一个也可以。`exclude` 中任一项出现在 Top-K 会立即失败，
可用于 ACL、同名文档和过时版本的负向门禁。旧版字符串形式仍兼容：
`"expect": ["128", "退款政策"]` 表示文档 ID 精确匹配或文件名包含匹配。

## 运行

```bash
# 指向已经入库的真实环境：
RUN_RETRIEVAL_EVAL=true mvn -Dtest=RetrievalRecallEvalTest test

# 按团队基线调整门禁：
RUN_RETRIEVAL_EVAL=true \
RETRIEVAL_EVAL_MIN_HITRATE=0.80 \
RETRIEVAL_EVAL_MIN_RECALL=0.75 \
RETRIEVAL_EVAL_MIN_MRR=0.65 \
mvn -Dtest=RetrievalRecallEvalTest test
```

## 建议覆盖面

从 20～50 条真实、高频问题起步，并保持每条真值可人工追溯：

- 专有名词、型号、条款编号、缩写和错别字；
- 同义问法、口语、省略主语与短问题；
- 多轮中的“它/那个/上面说的”等指代；
- 跨章节组合问题，以及答案确实不在知识库中的问题；
- 同名文件、旧版本文件、不同用户/数字员工知识范围的 ACL 隔离；
- 期望片段在长文开头、中间和末尾的位置分布。

真值集应绑定知识库快照或版本号；文档更新后同步复核标注，避免把内容漂移误判成代码回归。
