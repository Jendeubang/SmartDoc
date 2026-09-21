#!/usr/bin/env python3
"""Generate a public, de-identified 50-document / 1000-question RAG benchmark.

The corpus is intentionally synthetic.  It validates the reproducibility of the
upload, indexing, retrieval and evaluation pipeline; it is not evidence of
production performance on real enterprise documents.
"""

from __future__ import annotations

import csv
import json
import shutil
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CORPUS_DIR = ROOT / "generated-corpus"
DOCUMENTS_DIR = CORPUS_DIR / "documents"
DATASETS_DIR = ROOT / "datasets"
TEMPLATE_PATH = DATASETS_DIR / "rag_qa_1000.synthetic.template.jsonl"
MANIFEST_PATH = CORPUS_DIR / "document_id_mapping.csv"
ARCHIVE_PATH = CORPUS_DIR / "smartdoc-synthetic-rag-corpus-50.zip"

DEPARTMENTS = [
    ("人力资源部", "员工入职与劳动关系"),
    ("行政综合部", "办公与资产使用"),
    ("信息安全部", "账号与数据安全"),
    ("财务管理部", "费用报销与预算"),
    ("项目管理部", "项目交付与风险"),
    ("客户服务部", "客户工单与服务响应"),
    ("采购管理部", "供应商准入与采购"),
    ("销售运营部", "销售线索与合同归档"),
    ("研发管理部", "研发变更与发布"),
    ("质量管理部", "质量检查与整改闭环"),
]

VARIANTS = ["基础执行规范", "季度运行细则", "异常处理指引", "质量复盘办法", "资料归档标准"]


def doc_values(index: int, department: str, subject: str, variant: str) -> dict[str, object]:
    month = (index - 1) % 12 + 1
    day = (index * 3) % 28 + 1
    return {
        "number": index,
        "code": f"SYN-{index:03d}",
        "department": department,
        "subject": subject,
        "variant": variant,
        "title": f"澄明科技-{department}{subject}{variant}",
        "effective_date": f"2026-{month:02d}-{day:02d}",
        "review_months": 6 + index % 4,
        "submission_hours": 2 + index % 7,
        "approval_role": ["部门负责人", "业务负责人", "分管总监", "制度管理员"][index % 4],
        "online_start": f"{8 + index % 2:02d}:{30 if index % 3 else 0:02d}",
        "online_end": f"{17 + index % 2:02d}:{30 if index % 3 else 0:02d}",
        "expense_limit": (index % 8 + 2) * 1000,
        "archive_months": 12 + index % 5 * 6,
        "incident_minutes": 10 + index % 6 * 5,
        "training_hours": 2 + index % 5,
        "exception_role": ["部门负责人", "合规专员", "信息安全负责人", "项目经理"][index % 4],
        "target_rate": 90 + index % 9,
        "sample_count": 3 + index % 8,
        "version": f"V{1 + index % 3}.{index % 10}",
        "contact": f"support-syn{index:03d}@example.test",
        "workdays": ["周一和周四", "周二和周五", "每周三", "每周一", "每周五"][index % 5],
        "scope": ["正式员工与实习生", "全体在岗员工", "业务部门负责人", "参与该流程的员工", "外包协作人员"][index % 5],
    }


def document_text(v: dict[str, object]) -> str:
    return f"""# {v['title']}

> 文档编号：{v['code']}  
> 版本：{v['version']}  
> 生效日期：{v['effective_date']}  
> 制定部门：{v['department']}

## 1. 目的与适用范围

本规范用于统一澄明科技的{v['subject']}工作要求，适用于{v['scope']}。相关人员应依据本规范完成申请、审批、执行、留痕和复盘。

## 2. 职责与审批

执行人应在事项发生前至少 {v['submission_hours']} 个工作小时提交申请。常规事项由{v['approval_role']}审批；确有紧急或特殊情况时，须由{v['exception_role']}书面确认后执行。

## 3. 执行要求

工作日执行窗口为 {v['online_start']} 至 {v['online_end']}。每月费用或资源使用额度上限为 {v['expense_limit']} 元，超出额度必须重新走专项审批。例行检查安排在{v['workdays']}，检查结论应在当日登记到台账。

## 4. 安全、异常与培训

发现账号异常、资料泄露、关键数据错误或服务中断时，应在 {v['incident_minutes']} 分钟内报告，并同步保留处理记录。相关人员每季度至少完成 {v['training_hours']} 小时专项培训；培训未完成者不得独立处理高风险事项。

## 5. 质量、归档与复盘

本规范要求关键流程一次通过率不低于 {v['target_rate']}%。每月随机抽查不少于 {v['sample_count']} 份记录。流程材料应保存至少 {v['archive_months']} 个月。制度每 {v['review_months']} 个月复核一次，修订内容需保留版本号和变更说明。

## 6. 联系方式

如需解释本规范或反馈执行问题，请联系制度支持邮箱：{v['contact']}。
"""


def questions(v: dict[str, object]) -> list[tuple[str, str, str]]:
    title = f"《{v['title']}》"
    return [
        (f"{title}的文档编号是什么？", str(v["code"]), "文档元数据"),
        (f"{title}由哪个部门制定？", str(v["department"]), "文档元数据"),
        (f"{title}规范的核心工作主题是什么？", str(v["subject"]), "适用范围"),
        (f"{title}适用于哪些人员？", str(v["scope"]), "适用范围"),
        (f"{title}从哪一天开始生效？", str(v["effective_date"]), "文档元数据"),
        (f"{title}多久复核一次？", f"每 {v['review_months']} 个月复核一次。", "复盘"),
        (f"{title}的常规事项由谁审批？", str(v["approval_role"]), "审批"),
        (f"{title}的特殊情况需要谁书面确认？", str(v["exception_role"]), "审批"),
        (f"{title}至少提前多少个工作小时提交申请？", f"至少 {v['submission_hours']} 个工作小时。", "执行要求"),
        (f"{title}工作日的执行窗口是什么时间？", f"{v['online_start']} 至 {v['online_end']}。", "执行要求"),
        (f"{title}每月费用或资源使用额度上限是多少？", f"{v['expense_limit']} 元。", "执行要求"),
        (f"{title}的例行检查安排在哪些时间？", str(v["workdays"]), "执行要求"),
        (f"{title}发生安全或数据异常后多久内要报告？", f"{v['incident_minutes']} 分钟内。", "安全"),
        (f"{title}每季度至少要完成多少小时专项培训？", f"{v['training_hours']} 小时。", "培训"),
        (f"{title}关键流程的一次通过率目标是多少？", f"不低于 {v['target_rate']}%。", "质量"),
        (f"{title}每月随机抽查记录的最低数量是多少？", f"不少于 {v['sample_count']} 份。", "质量"),
        (f"{title}的流程材料至少保存多久？", f"至少 {v['archive_months']} 个月。", "归档"),
        (f"{title}当前版本号是什么？", str(v["version"]), "文档元数据"),
        (f"{title}的制度支持邮箱是什么？", str(v["contact"]), "联系方式"),
        (f"在{title}中，未完成培训的人员能否独立处理高风险事项？", "不能。", "培训"),
    ]


def main() -> None:
    if CORPUS_DIR.exists():
        shutil.rmtree(CORPUS_DIR)
    DOCUMENTS_DIR.mkdir(parents=True, exist_ok=True)
    DATASETS_DIR.mkdir(parents=True, exist_ok=True)

    manifest_rows: list[dict[str, str]] = []
    qa_rows: list[dict[str, object]] = []
    index = 1
    for department, subject in DEPARTMENTS:
        for variant in VARIANTS:
            v = doc_values(index, department, subject, variant)
            filename = f"{index:03d}-{v['code']}-{department}-{variant}.md"
            (DOCUMENTS_DIR / filename).write_text(document_text(v), encoding="utf-8")
            placeholder = f"REPLACE_DOC_{index:03d}"
            manifest_rows.append({
                "logicalDocumentKey": str(v["code"]),
                "sourceFilename": filename,
                "documentTitle": str(v["title"]),
                "documentId": "",
                "placeholder": placeholder,
            })
            for offset, (question, answer, category) in enumerate(questions(v), start=1):
                qa_rows.append({
                    "id": f"synthetic-{index:03d}-{offset:02d}",
                    "question": question,
                    "knowledgeBaseId": "default",
                    "expectedDocumentIds": [placeholder],
                    "referenceAnswer": answer,
                    "category": f"{department}-{category}",
                    "difficulty": "easy" if offset <= 8 else ("medium" if offset <= 16 else "hard"),
                    "sourceFilename": filename,
                    "synthetic": True,
                })
            index += 1

    with MANIFEST_PATH.open("w", encoding="utf-8-sig", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=["logicalDocumentKey", "sourceFilename", "documentTitle", "documentId", "placeholder"])
        writer.writeheader()
        writer.writerows(manifest_rows)
    with TEMPLATE_PATH.open("w", encoding="utf-8") as file:
        for row in qa_rows:
            file.write(json.dumps(row, ensure_ascii=False) + "\n")
    shutil.make_archive(str(ARCHIVE_PATH.with_suffix("")), "zip", DOCUMENTS_DIR)

    readme = CORPUS_DIR / "README.md"
    readme.write_text(
        "# SmartDoc 合成 RAG 评测语料\n\n"
        "- 文档数：50\n- 问答数：1000（每份文档 20 题）\n"
        "- 内容：虚构、脱敏、可公开，不含个人资料或客户资料。\n"
        "- 目的：验证上传、索引、混合检索、重排和评测脚本能否复现。\n\n"
        "上传 `documents/` 中所有 Markdown 后，在 `document_id_mapping.csv` 的 `documentId` 列填写对应文档 ID，"
        "再运行 `bind_document_ids.py` 生成可评测问答集。\n",
        encoding="utf-8",
    )
    print(f"Generated {len(manifest_rows)} documents and {len(qa_rows)} questions.")
    print(f"Documents: {DOCUMENTS_DIR}")
    print(f"Dataset template: {TEMPLATE_PATH}")
    print(f"Mapping file: {MANIFEST_PATH}")
    print(f"ZIP: {ARCHIVE_PATH}")


if __name__ == "__main__":
    main()
