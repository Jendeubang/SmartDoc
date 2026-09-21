# SmartDoc RAG 可复现评测

这个目录把 **RAG 效果评测** 和 **RAG 性能压测** 分开保存：

| 目标 | 输入 | 输出 |
| --- | --- | --- |
| 检索效果 | `datasets/rag_qa_v1.jsonl` | Recall@5、MRR@5、逐题命中明细 |
| 性能与稳定性 | `datasets/rag_questions.csv` + JMeter 测试计划 | `.jtl` 原始结果、JMeter HTML 报告 |

> 不要提交真实 Token、客户文档、含个人信息的问答集或原始模型回答。测试语料必须使用可公开或已脱敏的数据。

## 0. 评测前准备

1. 启动 SmartDoc 的 MySQL、Redis Stack、AI 服务、文档服务与网关。
2. 使用专门的测试账号上传 20～50 份无敏感信息的文档。
3. 在工具箱的“知识库索引”中，把测试文档全部加入知识库。当前前端入口使用的知识库 ID 固定为 `default`。
4. 已更换 Embedding 模型时，先删除旧索引，再完整重建索引；不能混用不同 Embedding 模型生成的向量。
5. 登录后从浏览器开发者工具或 Postman 获取 Access Token。Token 只临时写入当前 PowerShell 窗口。

```powershell
$env:SMARTDOC_TOKEN = '粘贴你的AccessToken'
$env:SMARTDOC_REFRESH_TOKEN = '粘贴你的RefreshToken'
```

## 1. 建立 1000 条人工标注问答集

从 [rag_qa_v1.template.jsonl](datasets/rag_qa_v1.template.jsonl) 复制为 `rag_qa_v1.jsonl`。一行是一条 JSON，至少填写：

```json
{"id":"qa-0001","question":"员工试用期最长可以约定多久？","knowledgeBaseId":"default","expectedDocumentIds":["真实文档ID"],"referenceAnswer":"对应制度中的试用期条款。","category":"人事制度","difficulty":"easy"}
```

字段含义：

- `question`：真实用户可能提出的问题。
- `knowledgeBaseId`：索引时使用的知识库 ID；默认可填 `default`。
- `expectedDocumentIds`：正确答案应命中的一个或多个真实文档 ID。它是 Recall/MRR 计算依据，必须人工确认。
- `referenceAnswer`：人工参考答案，当前脚本不自动判定生成答案正确性，但保留它便于后续人工抽检。

建议按 400 条简单事实、300 条多条件问题、200 条同义改写、100 条易混淆问题构成 1000 条数据。不要直接把模型自动生成的问题当作金标准；至少抽样复核并人工修正其命中文档。

## 2. 自动执行 RAG 检索效果评测

脚本只调用检索接口 `/api/ai/rag/search/hybrid/rerank`，不会调用最终回答接口，因此不会把大模型生成耗时混入检索指标。

```powershell
python evaluation/scripts/run_rag_eval.py `
  --dataset evaluation/datasets/rag_qa_v1.jsonl `
  --base-url http://localhost:8080 `
  --token-env SMARTDOC_TOKEN `
  --refresh-token-env SMARTDOC_REFRESH_TOKEN `
  --top-k 5 `
  --knowledge-base-id default `
  --output-dir evaluation/results/rag-eval-v1
```

完成后检查：

- `summary.json`：样本数、错误数、Recall@1/@3/@5、MRR@5、单线程请求延迟。
- `details.csv`：每题的返回文档 ID、正确命中排名、耗时和错误原因。

只有 `errors=0` 且 `details.csv` 中的命中结果经过抽检后，才适合在简历中使用指标。

## 3. 从问答集生成 JMeter CSV

JMeter 只需要问题和知识库 ID。运行：

```powershell
python evaluation/scripts/export_questions_csv.py `
  --dataset evaluation/datasets/rag_qa_v1.jsonl `
  --output evaluation/datasets/rag_questions.csv
```

如果使用 1000 条合成评测集，先导出对应的 JMeter CSV：

```powershell
python evaluation/scripts/export_questions_csv.py `
  --dataset evaluation/datasets/rag_qa_1000.synthetic.jsonl `
  --output evaluation/datasets/rag_questions_1000.csv
```

## 4. 运行 JMeter RAG 压测

图形界面可直接打开 [smartdoc-rag-search.jmx](jmeter/smartdoc-rag-search.jmx)。正式压测推荐非 GUI 模式：

```powershell
.\evaluation\jmeter\run-rag-performance.ps1 `
  -JMeterBin 'F:\DocAI\jmeter\apache-jmeter-5.6.3\bin\jmeter.bat' `
  -Token $env:SMARTDOC_TOKEN `
  -Threads 10 `
  -RampUpSeconds 30 `
  -ThinkTimeMs 800
```

第二轮可改成 `-Threads 20 -RampUpSeconds 60`。脚本会在 `evaluation/results/` 生成 `.jtl` 原始结果，在 `evaluation/reports/` 生成 JMeter HTML 报告。

`ThinkTimeMs=800` 是为了先在网关限流阈值内验证稳定性；若设为 `0`，可能收到 `429 Too Many Requests`，这表示限流生效，不应与服务端 5xx 混为一谈。

### 检索链路分段对比

当前 JMeter 脚本支持通过 `-Path` 和 `-Strategy` 分别测试不同检索阶段。建议统一使用 5 个线程、30 秒预热、每线程 200 次循环、1500ms 间隔，避免超过 AI 路由 5 QPS 的限流阈值：

```powershell
# 纯向量检索
.\evaluation\jmeter\run-rag-performance.ps1 -JMeterBin 'F:\DocAI\jmeter\apache-jmeter-5.6.3\bin\jmeter.bat' -Token $env:SMARTDOC_TOKEN -DataFile 'F:\DocAI\DocAI-main\evaluation\datasets\rag_questions_1000.csv' -Path '/api/ai/rag/search' -Threads 5 -RampUpSeconds 30 -Loops 200 -ThinkTimeMs 1500

# 向量 + BM25
.\evaluation\jmeter\run-rag-performance.ps1 -JMeterBin 'F:\DocAI\jmeter\apache-jmeter-5.6.3\bin\jmeter.bat' -Token $env:SMARTDOC_TOKEN -DataFile 'F:\DocAI\DocAI-main\evaluation\datasets\rag_questions_1000.csv' -Path '/api/ai/rag/search/hybrid' -Threads 5 -RampUpSeconds 30 -Loops 200 -ThinkTimeMs 1500

# 向量 + BM25 + 本地 BM25 融合重排
.\evaluation\jmeter\run-rag-performance.ps1 -JMeterBin 'F:\DocAI\jmeter\apache-jmeter-5.6.3\bin\jmeter.bat' -Token $env:SMARTDOC_TOKEN -DataFile 'F:\DocAI\DocAI-main\evaluation\datasets\rag_questions_1000.csv' -Path '/api/ai/rag/search/hybrid/rerank' -Strategy 'BM25_FUSION' -Threads 5 -RampUpSeconds 30 -Loops 200 -ThinkTimeMs 1500
```

每组报告都要记录 `Samples`、`Error %`、`Average`、`95th Percentile` 和 `Throughput`。不要把 429 请求的快速失败耗时混入成功请求的 P95；先保证 `Error % = 0%`，再比较优化前后的 P95。

## 5. 指标口径

```text
Recall@5 = Top-5 中至少命中一个 expectedDocumentIds 的问题数 / 全部标注问题数
MRR@5    = 所有问题的 1 / 正确文档首次出现排名 的平均值；Top-5 未命中记 0
```

RAG 效果评测的单线程延迟不能替代并发压测。简历中的 P95 和成功率应以 JMeter HTML 报告为准，并同时保留：Git Commit、Docker 镜像版本、CPU/内存、线程数、准备时长、数据集版本和原始 `.jtl`。

> 评测可能超过 Access Token 的 30 分钟有效期。脚本收到 HTTP 401 后会使用 Refresh Token 调用 `/api/users/refresh`，在内存中轮换 Access Token 和 Refresh Token 后仅重试当前请求一次；Token 不会写入 CSV、JSON 或日志。不要把 Token 写入代码或提交到 Git。

1000 次请求只能精确到 `99.9%`。若要声称“成功率不低于 99.95%”，正式稳定性压测至少应有 2000 次请求；2000 次中最多允许 1 次失败。
