#!/usr/bin/env python3
"""Match uploaded synthetic documents to SmartDoc IDs and build the 1000-QA set."""

from __future__ import annotations

import argparse
import csv
import json
import os
import urllib.error
import urllib.request
from pathlib import Path


def fetch_catalog(base_url: str, token: str) -> list[dict]:
    request = urllib.request.Request(
        base_url.rstrip("/") + "/api/documents/access/catalog",
        headers={"Authorization": f"Bearer {token}", "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")[:300]
        raise RuntimeError(f"获取文档目录失败：HTTP {error.code} {detail}") from error
    data = payload.get("data", payload) if isinstance(payload, dict) else payload
    if not isinstance(data, list):
        raise RuntimeError("文档目录接口未返回列表")
    return data


def document_title(item: dict) -> str:
    return str(item.get("title") or item.get("fileName") or item.get("name") or "").strip()


def newest_ready_by_title(catalog: list[dict]) -> dict[str, str]:
    """Prefer the latest successfully parsed document when earlier uploads have the same title."""
    selected: dict[str, dict] = {}
    for item in catalog:
        title = document_title(item)
        if not title or not str(item.get("id") or "").strip():
            continue
        current = selected.get(title)
        if current is None:
            selected[title] = item
            continue
        item_ready = str(item.get("parseStatus") or "").lower() == "ready"
        current_ready = str(current.get("parseStatus") or "").lower() == "ready"
        item_time = str(item.get("updateTime") or item.get("createTime") or "")
        current_time = str(current.get("updateTime") or current.get("createTime") or "")
        if (item_ready and not current_ready) or (item_ready == current_ready and item_time > current_time):
            selected[title] = item
    return {title: str(item["id"]).strip() for title, item in selected.items()}


def main() -> None:
    parser = argparse.ArgumentParser(description="自动绑定已上传的合成语料文档 ID")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--token-env", default="SMARTDOC_TOKEN")
    parser.add_argument("--mapping", required=True)
    parser.add_argument("--template", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    token = os.environ.get(args.token_env, "").strip()
    if not token:
        raise ValueError(f"未检测到环境变量 {args.token_env}，请先在当前 PowerShell 执行：$env:{args.token_env} = 'AccessToken'")
    by_title = newest_ready_by_title(fetch_catalog(args.base_url, token))
    missing: list[str] = []
    mapping: dict[str, str] = {}
    with Path(args.mapping).open(encoding="utf-8-sig", newline="") as file:
        for row in csv.DictReader(file):
            filename = (row.get("sourceFilename") or "").strip()
            placeholder = (row.get("placeholder") or "").strip()
            document_id = by_title.get(filename, "")
            if not document_id:
                missing.append(filename)
            else:
                mapping[placeholder] = document_id
    if missing:
        preview = "、".join(missing[:5])
        suffix = "…" if len(missing) > 5 else ""
        raise RuntimeError(f"有 {len(missing)} 份文档未在当前账号目录中找到：{preview}{suffix}。请确认已全部上传完成。")

    output_rows: list[dict] = []
    for line_number, line in enumerate(Path(args.template).read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        row = json.loads(line)
        row["expectedDocumentIds"] = [mapping.get(str(value), str(value)) for value in row.get("expectedDocumentIds") or []]
        if any(value.startswith("REPLACE_DOC_") for value in row["expectedDocumentIds"]):
            raise RuntimeError(f"第 {line_number} 行仍存在未绑定占位符")
        output_rows.append(row)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8") as file:
        for row in output_rows:
            file.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"已自动匹配 50 份文档，并生成 {len(output_rows)} 条问答集：{output}")


if __name__ == "__main__":
    main()
