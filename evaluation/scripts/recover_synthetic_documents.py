#!/usr/bin/env python3
"""Repair and index uploaded synthetic evaluation documents after a parse failure."""

from __future__ import annotations

import argparse
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path


def request_json(base_url: str, token: str, method: str, path: str, body: str | None = None) -> object:
    headers = {"Authorization": f"Bearer {token}", "Accept": "application/json"}
    data = None
    if body is not None:
        data = body.encode("utf-8")
        headers["Content-Type"] = "text/plain; charset=utf-8"
    request = urllib.request.Request(base_url.rstrip("/") + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")[:300]
        raise RuntimeError(f"HTTP {error.code}: {detail}") from error
    result = payload.get("data", payload) if isinstance(payload, dict) else payload
    if isinstance(payload, dict) and payload.get("code") not in (None, 200):
        raise RuntimeError(str(payload.get("message") or payload))
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description="重新解析并自动索引失败的 SmartDoc 合成评测文档")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--token-env", default="SMARTDOC_TOKEN")
    parser.add_argument("--title-contains", default="-SYN-", help="只处理标题包含该文本的文档")
    parser.add_argument("--retry-all", action="store_true", help="连同状态为 ready 的匹配文档一起重新解析并索引")
    parser.add_argument("--output", default="evaluation/results/synthetic-recovery.json")
    args = parser.parse_args()

    token = os.environ.get(args.token_env, "").strip()
    if not token:
        raise ValueError(f"未检测到 {args.token_env}。请先设置：$env:{args.token_env} = 'AccessToken'")
    catalog = request_json(args.base_url, token, "GET", "/api/documents/access/catalog")
    if not isinstance(catalog, list):
        raise RuntimeError("文档目录接口未返回列表")
    latest_by_title: dict[str, dict] = {}
    for item in catalog:
        title = str(item.get("title") or "").strip()
        if args.title_contains not in title:
            continue
        current = latest_by_title.get(title)
        item_time = str(item.get("updateTime") or item.get("createTime") or "")
        current_time = str(current.get("updateTime") or current.get("createTime") or "") if current else ""
        if current is None or item_time > current_time:
            latest_by_title[title] = item
    # Only repair the newest copy of each source filename. Earlier failed copies remain
    # untouched so duplicate vectors never pollute the evaluation knowledge base.
    candidates = list(latest_by_title.values())
    if not args.retry_all:
        candidates = [item for item in candidates if str(item.get("parseStatus") or "").lower() == "failed"]
    if not candidates:
        print("没有需要恢复的匹配文档。")
        return

    report = {"generatedAt": datetime.now(timezone.utc).isoformat(), "processed": [], "failures": []}
    for position, document in enumerate(candidates, start=1):
        document_id = str(document.get("id") or "").strip()
        title = str(document.get("title") or document_id)
        try:
            reparsed = request_json(args.base_url, token, "POST", f"/api/documents/{urllib.parse.quote(document_id)}/reparse")
            content = str(reparsed.get("content") or "").strip() if isinstance(reparsed, dict) else ""
            if not content:
                raise RuntimeError("重新解析后仍无正文")
            request_json(args.base_url, token, "POST",
                         f"/api/ai/agent/knowledge/index/segment?documentId={urllib.parse.quote(document_id)}&strategy=AUTO", content)
            report["processed"].append({"id": document_id, "title": title, "contentLength": len(content)})
            print(f"[{position}/{len(candidates)}] 已恢复并提交索引：{title}")
        except Exception as error:  # continue repairing independent documents
            report["failures"].append({"id": document_id, "title": title, "error": str(error)})
            print(f"[{position}/{len(candidates)}] 失败：{title} -> {error}")
        # Keep this recovery operation below the gateway's per-user rate limit.
        time.sleep(0.9)

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"完成：成功 {len(report['processed'])}，失败 {len(report['failures'])}。报告：{output}")


if __name__ == "__main__":
    main()
