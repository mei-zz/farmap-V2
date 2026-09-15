from __future__ import annotations

import argparse
import csv
import math
import os
import re
import sys
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence
from urllib.parse import urlsplit

import dashscope
import numpy as np
from pymilvus import Collection, connections


ROOT = Path(r"D:\前后端项目\farmap")
ENV_PATH = ROOT / ".env"
COLLECTION_NAME = "farmap_reference_images_v1"
MODEL = "tongyi-embedding-vision-plus-2026-03-06"
DIMENSION = 1152
OUTPUT_FIELDS = [
    "ref_id", "crop", "ref_type", "stage_code", "disease_key", "label_cn",
    "source_subdir", "vigor", "file_name", "local_path", "image_url", "embedding_model",
]


def read_env(path: Path) -> Dict[str, str]:
    values: Dict[str, str] = {}
    with path.open("r", encoding="utf-8") as handle:
        for raw in handle:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            if line.lower().startswith("export "):
                line = line[7:].lstrip()
            if "=" not in line:
                continue
            key, value = line.split("=", 1)
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
                value = value[1:-1]
            values[key.strip()] = value
    return values


def redact(value: Any, secrets: Sequence[str]) -> str:
    text = str(value)
    for secret in secrets:
        if secret:
            text = text.replace(secret, "[REDACTED]")
    return text


def endpoint(baseurl: str) -> str:
    value = baseurl.rstrip("/")
    if value.lower().endswith("/compatible-mode/v1"):
        return value[: -len("/compatible-mode/v1")] + "/api/v1"
    if value.lower().endswith("/api/v1"):
        return value
    return value + "/api/v1"


def encode_query(api_endpoint: str, api_key: str, query: str, secrets: Sequence[str]) -> np.ndarray:
    for attempt in range(1, 4):
        if attempt > 1:
            time.sleep(0.4 * (2 ** (attempt - 2)))
        try:
            dashscope.api_key = api_key
            dashscope.base_http_api_url = api_endpoint
            response = dashscope.MultiModalEmbedding.call(model=MODEL, input=[{"text": query}], dimension=DIMENSION)
            status = int(getattr(response, "status_code", 200))
            if status != 200:
                raise RuntimeError(f"status={status}, code={getattr(response, 'code', '')}, message={getattr(response, 'message', '')}")
            vector = extract_vector(getattr(response, "output", response))
            array = np.asarray(vector, dtype=np.float64)
            norm = float(np.linalg.norm(array))
            if array.shape != (DIMENSION,) or not np.isfinite(array).all() or norm <= 0 or not math.isfinite(norm):
                raise RuntimeError("查询向量形状、有限值或范数异常")
            time.sleep(0.2)
            return (array / norm).astype(np.float32)
        except Exception as exc:
            if attempt == 3:
                raise RuntimeError(redact(exc, secrets)) from exc
            time.sleep(0.2)
    raise RuntimeError("查询向量调用失败")


def extract_vector(value: Any) -> Optional[List[float]]:
    if isinstance(value, np.ndarray):
        value = value.tolist()
    if isinstance(value, (list, tuple)) and len(value) == DIMENSION and all(isinstance(x, (int, float, np.integer, np.floating)) for x in value):
        return [float(x) for x in value]
    if isinstance(value, dict):
        keys = ["embedding", "embeddings", "vector", "data", "output"]
        for key in keys + [key for key in value if key not in keys]:
            if key in value:
                result = extract_vector(value[key])
                if result is not None:
                    return result
    elif isinstance(value, (list, tuple)):
        for item in value:
            result = extract_vector(item)
            if result is not None:
                return result
    else:
        for attr in ("embedding", "embeddings", "vector", "data", "output"):
            result = extract_vector(getattr(value, attr, None))
            if result is not None:
                return result
    return None


def hit_value(hit: Any, name: str, fallback: Any = "") -> Any:
    entity = getattr(hit, "entity", None)
    if entity is not None:
        getter = getattr(entity, "get", None)
        if callable(getter):
            try:
                value = getter(name)
                if value is not None:
                    return value
            except Exception:
                pass
        try:
            value = entity[name]
            if value is not None:
                return value
        except Exception:
            pass
    fields = getattr(hit, "fields", None)
    if isinstance(fields, dict) and name in fields:
        return fields[name]
    if name == "ref_id":
        return getattr(hit, "id", fallback)
    return fallback


def search(collection: Collection, vector: np.ndarray, topk: int, expr: Optional[str]) -> List[Dict[str, Any]]:
    kwargs: Dict[str, Any] = {
        "data": [vector.tolist()],
        "anns_field": "embedding",
        "param": {"metric_type": "COSINE", "params": {}},
        "limit": topk,
        "output_fields": OUTPUT_FIELDS,
    }
    if expr:
        kwargs["expr"] = expr
    hits = collection.search(**kwargs)[0]
    return [
        {
            "ref_id": str(hit_value(hit, "ref_id")),
            "label_cn": str(hit_value(hit, "label_cn")),
            "ref_type": str(hit_value(hit, "ref_type")),
            "stage_code": str(hit_value(hit, "stage_code")),
            "disease_key": str(hit_value(hit, "disease_key")),
            "image_url": str(hit_value(hit, "image_url")),
            "score": float(getattr(hit, "distance", 0.0)),
        }
        for hit in hits
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description="FarMap 参考图中文文字搜图")
    parser.add_argument("query", help="中文查询文本")
    parser.add_argument("--crop", default="", help="可选 crop 过滤值，例如 plum")
    parser.add_argument("--ref-type", default="", choices=["", "PHENOLOGY", "DISEASE"], help="可选 PHENOLOGY 或 DISEASE")
    parser.add_argument("--topk", type=int, default=5)
    args = parser.parse_args()
    if args.topk < 1:
        raise SystemExit("--topk 必须大于 0")
    env = read_env(ENV_PATH)
    api_key = env.get("APIKEY", "").strip()
    baseurl = env.get("BASEURL", "").strip()
    if not api_key or not baseurl:
        raise SystemExit(".env 缺少 APIKEY 或 BASEURL")
    secrets = [api_key]
    host = os.environ.get("MILVUS_HOST") or env.get("MILVUS_HOST") or "localhost"
    port = os.environ.get("MILVUS_PORT") or env.get("MILVUS_PORT") or "19530"
    username = os.environ.get("MILVUS_USERNAME") or env.get("MILVUS_USERNAME") or ""
    password = os.environ.get("MILVUS_PASSWORD") or env.get("MILVUS_PASSWORD") or ""
    kwargs: Dict[str, Any] = {"alias": "farmap_reference_search", "host": host, "port": port, "db_name": "default"}
    if username:
        kwargs["user"] = username
    if password:
        kwargs["password"] = password
    try:
        connections.connect(**kwargs)
        collection = Collection(name=COLLECTION_NAME, using="farmap_reference_search")
        collection.load()
        expr_parts = []
        if args.crop:
            expr_parts.append(f"crop == '{args.crop.replace(chr(39), chr(39) + chr(39))}'")
        if args.ref_type:
            expr_parts.append(f"ref_type == '{args.ref_type}'")
        expr = " and ".join(expr_parts) or None
        vector = encode_query(endpoint(baseurl), api_key, args.query, secrets)
        results = search(collection, vector, args.topk, expr)
        print(f"query={args.query}")
        print(f"expr={expr or '<none>'}")
        for index, item in enumerate(results, start=1):
            print(f"{index}. ref_id={item['ref_id']} label={item['label_cn']} ref_type={item['ref_type']} stage={item['stage_code']} disease={item['disease_key']} score={item['score']:.4f} image_url={item['image_url']}")
        return 0
    except Exception as exc:
        print(f"检索失败: {redact(exc, secrets)}")
        return 1
    finally:
        try:
            connections.disconnect("farmap_reference_search")
        except Exception:
            pass


if __name__ == "__main__":
    sys.exit(main())
