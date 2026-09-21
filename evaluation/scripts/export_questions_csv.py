#!/usr/bin/env python3
"""Export question and knowledgeBaseId columns from a labeled RAG JSONL dataset."""

import argparse
import csv
import json
from pathlib import Path


def load_rows(path: Path):
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError as error:
            raise SystemExit(f"{path}:{line_number} 不是合法 JSON：{error.msg}") from error
        question = str(row.get("question", "")).strip()
        if not question:
            raise SystemExit(f"{path}:{line_number} 缺少 question")
        yield question, str(row.get("knowledgeBaseId") or "default").strip()


def main():
    parser = argparse.ArgumentParser(description="从 RAG JSONL 问答集生成 JMeter CSV")
    parser.add_argument("--dataset", required=True, help="JSONL 问答集路径")
    parser.add_argument("--output", required=True, help="输出 CSV 路径")
    args = parser.parse_args()

    dataset = Path(args.dataset)
    output = Path(args.output)
    if not dataset.is_file():
        raise SystemExit(f"找不到问答集：{dataset}")
    output.parent.mkdir(parents=True, exist_ok=True)
    count = 0
    with output.open("w", encoding="utf-8-sig", newline="") as file:
        writer = csv.writer(file)
        writer.writerow(["question", "knowledgeBaseId"])
        for question, knowledge_base_id in load_rows(dataset):
            writer.writerow([question, knowledge_base_id])
            count += 1
    print(f"已导出 {count} 条问题到 {output}")


if __name__ == "__main__":
    main()
