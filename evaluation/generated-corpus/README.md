# SmartDoc 合成 RAG 评测语料

- 文档数：50
- 问答数：1000（每份文档 20 题）
- 内容：虚构、脱敏、可公开，不含个人资料或客户资料。
- 目的：验证上传、索引、混合检索、重排和评测脚本能否复现。

上传 `documents/` 中所有 Markdown 后，在 `document_id_mapping.csv` 的 `documentId` 列填写对应文档 ID，再运行 `bind_document_ids.py` 生成可评测问答集。
