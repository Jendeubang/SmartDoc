#!/usr/bin/env python3
"""Evaluate SmartDoc RAG retrieval with labeled JSONL data using only Python stdlib."""

import argparse
import csv
import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path


def percentile(values, percent):
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, int((len(ordered) - 1) * percent + 0.999999)))
    return round(ordered[index], 2)


def load_cases(path: Path, default_knowledge_base_id: str, limit: int | None):
    cases = []
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        try:
            item = json.loads(line)
        except json.JSONDecodeError as error:
            raise ValueError(f"{path}:{line_number} 不是合法 JSON：{error.msg}") from error
        question = str(item.get("question") or "").strip()
        expected = item.get("expectedDocumentIds") or []
        if isinstance(expected, str):
            expected = [expected]
        expected = [str(value).strip() for value in expected if str(value).strip()]
        if not question or not expected:
            raise ValueError(f"{path}:{line_number} 必须包含 question 与 expectedDocumentIds")
        cases.append({
            "id": str(item.get("id") or f"line-{line_number}"),
            "question": question,
            "knowledgeBaseId": str(item.get("knowledgeBaseId") or default_knowledge_base_id),
            "expectedDocumentIds": expected,
            "category": str(item.get("category") or "uncategorized"),
        })
        if limit and len(cases) >= limit:
            break
    if not cases:
        raise ValueError("问答集为空；请先从模板复制并填写真实数据")
    return cases


def values_from_candidate(candidate):
    """Read common SmartDoc/Spring AI document-id locations without trusting text content."""
    values = []
    if not isinstance(candidate, dict):
        return values
    for key in ("documentId", "document_id", "sourceDocumentId", "source_document_id"):
        if candidate.get(key) is not None:
            values.append(str(candidate[key]))
    for metadata_key in ("metadata", "metaData", "metadataMap"):
        metadata = candidate.get(metadata_key)
        if isinstance(metadata, dict):
            for key in ("documentId", "document_id", "sourceDocumentId", "source_document_id"):
                if metadata.get(key) is not None:
                    values.append(str(metadata[key]))
    # Some vector-store adapters expose the business document id directly as id.
    if candidate.get("id") is not None:
        values.append(str(candidate["id"]))
    return list(dict.fromkeys(value.strip() for value in values if value and value.strip()))


def refresh_access_token(base_url, refresh_token, timeout):
    """Use the rotating refresh token to obtain a new access token.

    Tokens are kept only in memory and are never written to evaluation outputs.
    """
    url = base_url.rstrip("/") + "/api/users/refresh"
    request = urllib.request.Request(
        url,
        data=json.dumps({"refreshToken": refresh_token}).encode("utf-8"),
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"刷新 Access Token 失败：HTTP {error.code}: {detail[:300]}") from error
    except urllib.error.URLError as error:
        raise RuntimeError(f"刷新 Access Token 请求失败：{error.reason}") from error
    try:
        payload = json.loads(body)
    except json.JSONDecodeError as error:
        raise RuntimeError("刷新接口返回不是 JSON") from error
    data = payload.get("data", payload) if isinstance(payload, dict) else payload
    if not isinstance(data, dict):
        raise RuntimeError("刷新接口返回格式不正确")
    access_token = str(data.get("accessToken") or data.get("token") or "").strip()
    next_refresh_token = str(data.get("refreshToken") or refresh_token).strip()
    if not access_token:
        raise RuntimeError("刷新接口未返回 accessToken")
    return access_token, next_refresh_token


def request_search(base_url, auth_state, question, knowledge_base_id, top_k, strategy, timeout, allow_refresh=True):
    query = urllib.parse.urlencode({
        "query": question,
        "topK": str(top_k),
        "knowledgeBaseId": knowledge_base_id,
        "strategy": strategy,
    })
    url = base_url.rstrip("/") + "/api/ai/rag/search/hybrid/rerank?" + query
    request = urllib.request.Request(
        url,
        headers={"Authorization": f"Bearer {auth_state['access']}", "Accept": "application/json"},
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read().decode("utf-8")
            status = response.status
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        if error.code == 401 and allow_refresh and auth_state.get("refresh"):
            access_token, refresh_token = refresh_access_token(base_url, auth_state["refresh"], timeout)
            auth_state["access"] = access_token
            auth_state["refresh"] = refresh_token
            return request_search(
                base_url, auth_state, question, knowledge_base_id, top_k, strategy, timeout, allow_refresh=False
            )
        if error.code == 401 and not auth_state.get("refresh"):
            raise RuntimeError(
                "HTTP 401：Access Token 可能已过期；请同时设置 SMARTDOC_REFRESH_TOKEN，评测脚本会自动续期"
            ) from error
        raise RuntimeError(f"HTTP {error.code}: {body[:300]}") from error
    except urllib.error.URLError as error:
        raise RuntimeError(f"请求失败：{error.reason}") from error
    latency_ms = (time.perf_counter() - started) * 1000
    if status != 200:
        raise RuntimeError(f"HTTP {status}: {body[:300]}")
    try:
        payload = json.loads(body)
    except json.JSONDecodeError as error:
        raise RuntimeError("接口返回不是 JSON") from error
    if isinstance(payload, dict) and payload.get("code") not in (None, 200):
        raise RuntimeError(f"业务失败：{payload.get('message') or payload}")
    data = payload.get("data", payload) if isinstance(payload, dict) else payload
    if not isinstance(data, list):
        raise RuntimeError("检索结果不是列表")
    return data, latency_ms


def evaluate_case(case, candidates):
    expected = set(case["expectedDocumentIds"])
    returned = []
    rank = None
    for index, candidate in enumerate(candidates, start=1):
        candidate_ids = values_from_candidate(candidate)
        returned.append("|".join(candidate_ids))
        if rank is None and expected.intersection(candidate_ids):
            rank = index
    return rank, returned


def main():
    parser = argparse.ArgumentParser(description="计算 SmartDoc RAG Recall@K 与 MRR@K")
    parser.add_argument("--dataset", required=True, help="人工标注 JSONL 问答集")
    parser.add_argument("--base-url", default="http://localhost:8080", help="网关地址")
    parser.add_argument("--token", default="", help="Access Token；优先建议使用 --token-env")
    parser.add_argument("--token-env", default="SMARTDOC_TOKEN", help="读取 Token 的环境变量名")
    parser.add_argument("--refresh-token", default="", help="Refresh Token；优先建议使用 --refresh-token-env")
    parser.add_argument(
        "--refresh-token-env", default="SMARTDOC_REFRESH_TOKEN", help="读取 Refresh Token 的环境变量名；Access Token 过期时自动续期"
    )
    parser.add_argument("--knowledge-base-id", default="default", help="问答集未指定时使用的知识库 ID")
    parser.add_argument("--top-k", type=int, default=5, choices=range(1, 21), metavar="1-20")
    parser.add_argument("--strategy", default="HYBRID", choices=("VECTOR", "BM25", "BM25_FUSION", "CROSS_ENCODER", "HYBRID"))
    parser.add_argument("--timeout", type=float, default=30, help="单请求超时秒数")
    parser.add_argument("--sleep-ms", type=int, default=0, help="每次请求间隔，避免触发网关限流")
    parser.add_argument("--limit", type=int, default=0, help="仅运行前 N 条，0 表示全部")
    parser.add_argument("--output-dir", required=True, help="结果输出目录")
    args = parser.parse_args()

    token = args.token.strip() or os.environ.get(args.token_env, "").strip()
    if not token:
        raise SystemExit(f"未找到 Token；请设置 $env:{args.token_env} 或传入 --token")
    refresh_token = args.refresh_token.strip() or os.environ.get(args.refresh_token_env, "").strip()
    dataset = Path(args.dataset)
    if not dataset.is_file():
        raise SystemExit(f"找不到问答集：{dataset}")
    try:
        cases = load_cases(dataset, args.knowledge_base_id, args.limit or None)
    except ValueError as error:
        raise SystemExit(str(error)) from error

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    details = []
    latencies = []
    recalls = {1: 0, 3: 0, 5: 0}
    reciprocal_rank_sum = 0.0
    errors = 0
    auth_state = {"access": token, "refresh": refresh_token}

    for position, case in enumerate(cases, start=1):
        row = {
            "id": case["id"], "category": case["category"], "question": case["question"],
            "knowledgeBaseId": case["knowledgeBaseId"], "expectedDocumentIds": "|".join(case["expectedDocumentIds"]),
            "returnedDocumentIds": "", "rank": "", "recallAt1": 0, "recallAt3": 0, "recallAt5": 0,
            "reciprocalRankAt5": 0, "latencyMs": "", "status": "success", "error": "",
        }
        try:
            candidates, latency_ms = request_search(
                args.base_url, auth_state, case["question"], case["knowledgeBaseId"],
                args.top_k, args.strategy, args.timeout
            )
            rank, returned = evaluate_case(case, candidates)
            row["returnedDocumentIds"] = " || ".join(returned)
            row["latencyMs"] = round(latency_ms, 2)
            latencies.append(latency_ms)
            if rank is not None:
                row["rank"] = rank
                for cutoff in recalls:
                    if rank <= cutoff:
                        recalls[cutoff] += 1
                        row[f"recallAt{cutoff}"] = 1
                if rank <= 5:
                    value = 1.0 / rank
                    reciprocal_rank_sum += value
                    row["reciprocalRankAt5"] = round(value, 6)
        except Exception as error:  # Keep evaluating the remaining labeled questions.
            errors += 1
            row["status"] = "error"
            row["error"] = str(error)
        details.append(row)
        print(f"[{position}/{len(cases)}] {case['id']}: {row['status']}")
        if args.sleep_ms > 0 and position < len(cases):
            time.sleep(args.sleep_ms / 1000)

    denominator = len(cases)
    summary = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "dataset": str(dataset), "baseUrl": args.base_url, "strategy": args.strategy,
        "topK": args.top_k, "totalCases": denominator, "requestErrors": errors,
        "recallAt1": round(recalls[1] / denominator, 6),
        "recallAt3": round(recalls[3] / denominator, 6),
        "recallAt5": round(recalls[5] / denominator, 6),
        "mrrAt5": round(reciprocal_rank_sum / denominator, 6),
        "sequentialLatencyMs": {
            "count": len(latencies), "average": round(statistics.mean(latencies), 2) if latencies else None,
            "p50": percentile(latencies, 0.50), "p95": percentile(latencies, 0.95), "p99": percentile(latencies, 0.99),
        },
        "notes": [
            "Latency is sequential evaluation latency, not a concurrency benchmark.",
            "Request errors count as misses in Recall and MRR denominators.",
            "Token values are never written to output files.",
        ],
    }
    fields = list(details[0].keys())
    with (output_dir / "details.csv").open("w", encoding="utf-8-sig", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=fields)
        writer.writeheader()
        writer.writerows(details)
    (output_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"\n结果已保存到：{output_dir}")


if __name__ == "__main__":
    main()
