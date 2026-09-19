#!/usr/bin/env python3
"""Bind SmartDoc document IDs from the upload manifest into the synthetic QA set."""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description="将上传后的 SmartDoc 文档 ID 绑定到评测问答集")
    parser.add_argument("--mapping", required=True, help="document_id_mapping.csv 路径")
    parser.add_argument("--template", required=True, help="含 REPLACE_DOC_* 占位符的 JSONL")
    parser.add_argument("--output", required=True, help="输出的可评测 JSONL 路径")
    args = parser.parse_args()

    mapping: dict[str, str] = {}
    with Path(args.mapping).open(encoding="utf-8-sig", newline="") as file:
        for row in csv.DictReader(file):
            placeholder = (row.get("placeholder") or "").strip()
            document_id = (row.get("documentId") or "").strip()
            if not placeholder or not document_id:
                raise ValueError(f"映射表存在未填写 documentId 的行：{row.get('sourceFilename')}")
            mapping[placeholder] = document_id

    output_rows: list[dict] = []
    for line_number, line in enumerate(Path(args.template).read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        row = json.loads(line)
        expected = row.get("expectedDocumentIds") or []
        row["expectedDocumentIds"] = [mapping.get(str(value), str(value)) for value in expected]
        unresolved = [value for value in row["expectedDocumentIds"] if value.startswith("REPLACE_DOC_")]
        if unresolved:
            raise ValueError(f"第 {line_number} 行存在未绑定占位符：{unresolved[0]}")
        output_rows.append(row)

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8") as file:
        for row in output_rows:
            file.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"已生成 {len(output_rows)} 条可评测问答：{output}")


if __name__ == "__main__":
    main()
