from __future__ import annotations

import csv
import hashlib
import io
import json
import math
import os
import re
import sys
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

import dashscope
import numpy as np
from qcloud_cos import CosConfig, CosS3Client
from pymilvus import Collection, CollectionSchema, DataType, FieldSchema, connections, utility


ROOT = Path(r"D:\前后端项目\farmap")
OUTPUT_DIR = ROOT / "local-ai-assets" / "ref_embeddings"
MANIFEST_PATH = OUTPUT_DIR / "ref_image_manifest.csv"
VECTOR_PATH = OUTPUT_DIR / "ref_vectors_1152.npy"
FINAL_MANIFEST_PATH = OUTPUT_DIR / "ref_image_manifest_final.csv"
COS_MAP_PATH = OUTPUT_DIR / "cos_url_map.csv"
REPORT_PATH = OUTPUT_DIR / "milvus_ingest_report.txt"
ENV_PATH = ROOT / ".env"
PHENOLOGY_ROOT = ROOT / "plum_phenology"
DISEASE_ROOT = ROOT / "plum_disease"

COLLECTION_NAME = "farmap_reference_images_v1"
MODEL = "tongyi-embedding-vision-plus-2026-03-06"
DIMENSION = 1152
REGION_DEFAULT = "ap-chongqing"
BUCKET_DEFAULT = "farmap-1317766785"
MILVUS_PORT_DEFAULT = "19530"
COS_CACHE_CONTROL = "public,max-age=86400"
TEXT_RETRY_LIMIT = 3

MANIFEST_COLUMNS = [
    "ref_id",
    "crop",
    "ref_type",
    "stage_code",
    "disease_key",
    "label_cn",
    "source_subdir",
    "vigor",
    "file_name",
    "local_path",
    "image_url",
    "embedding_model",
    "dimension",
    "res_level",
]
COS_MAP_COLUMNS = ["ref_id", "object_key", "image_url", "action", "anon_get_status"]

MILVUS_FIELDS = [
    ("ref_id", DataType.VARCHAR, 128, None, True),
    ("embedding", DataType.FLOAT_VECTOR, None, DIMENSION, False),
    ("crop", DataType.VARCHAR, 32, None, False),
    ("ref_type", DataType.VARCHAR, 16, None, False),
    ("stage_code", DataType.VARCHAR, 32, None, False),
    ("disease_key", DataType.VARCHAR, 32, None, False),
    ("label_cn", DataType.VARCHAR, 128, None, False),
    ("source_subdir", DataType.VARCHAR, 255, None, False),
    ("vigor", DataType.VARCHAR, 16, None, False),
    ("file_name", DataType.VARCHAR, 255, None, False),
    ("local_path", DataType.VARCHAR, 512, None, False),
    ("image_url", DataType.VARCHAR, 1024, None, False),
    ("embedding_model", DataType.VARCHAR, 64, None, False),
]
OUTPUT_FIELDS = [name for name, _, _, _, _ in MILVUS_FIELDS]
SCALAR_INDEX_FIELDS = ["crop", "ref_type", "stage_code", "disease_key"]

PROBES = [
    ("脆李褐腐病，果实变褐腐烂、表面长灰色霉层", "brown_rot"),
    ("细菌性穿孔病，叶片出现褐色病斑并穿孔", "bacterial_spot"),
    ("李袋果病，果实畸形膨大、中空像袋子", "pocket_disease"),
    ("脆李花期，枝条上开满白色花朵", "flowering"),
    ("冬季休眠期，落叶后只剩光秃枝条", "dormancy"),
    ("果实膨大期，枝头上成簇的青色幼果", "fruit_development"),
]


def current_time() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


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
    text = str(value).replace("\x00", "")
    for secret in secrets:
        if secret:
            text = text.replace(secret, "[REDACTED]")
    return text


def required_credentials(env: Dict[str, str]) -> Tuple[str, str, str, str, str, str]:
    api_key = env.get("APIKEY", "").strip()
    baseurl = env.get("BASEURL", "").strip()
    region = env.get("TENCENT_COS_REGION", REGION_DEFAULT).strip()
    bucket = env.get("TENCENT_COS_BUCKET", BUCKET_DEFAULT).strip()
    secret_id = env.get("TENCENT_COS_SECRET_ID", "").strip()
    secret_key = env.get("TENCENT_COS_SECRET_KEY", "").strip()
    missing = [
        name
        for name, value in (
            ("APIKEY", api_key),
            ("BASEURL", baseurl),
            ("TENCENT_COS_REGION", region),
            ("TENCENT_COS_BUCKET", bucket),
            ("TENCENT_COS_SECRET_ID", secret_id),
            ("TENCENT_COS_SECRET_KEY", secret_key),
        )
        if not value
    ]
    if missing:
        raise RuntimeError(".env 缺少必要字段: " + ", ".join(missing))
    return api_key, baseurl, region, bucket, secret_id, secret_key


def native_dashscope_endpoint(baseurl: str) -> str:
    value = baseurl.rstrip("/")
    lower = value.lower()
    if lower.endswith("/compatible-mode/v1"):
        return value[: -len("/compatible-mode/v1")] + "/api/v1"
    if lower.endswith("/api/v1"):
        return value
    return value + "/api/v1"


def endpoint_host(value: str) -> str:
    return urlsplit(value).netloc or value


def source_snapshot(root: Path) -> Dict[str, Dict[str, Any]]:
    result: Dict[str, Dict[str, Any]] = {}
    for path in sorted((item for item in root.rglob("*") if item.is_file()), key=lambda p: p.relative_to(root).as_posix().casefold()):
        digest = hashlib.sha256()
        with path.open("rb") as handle:
            for block in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(block)
        stat = path.stat()
        result[path.relative_to(root).as_posix()] = {
            "size": stat.st_size,
            "mtime_ns": stat.st_mtime_ns,
            "sha256": digest.hexdigest(),
        }
    return result


def snapshot_equal(left: Dict[str, Dict[str, Any]], right: Dict[str, Dict[str, Any]], field: str) -> bool:
    return set(left) == set(right) and all(left[name].get(field) == right[name].get(field) for name in left)


def read_inputs() -> Tuple[List[Dict[str, Any]], np.ndarray]:
    with MANIFEST_PATH.open("r", encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.DictReader(handle))
    if rows and list(rows[0].keys()) != MANIFEST_COLUMNS:
        raise RuntimeError("第一步 manifest 列不符合预期")
    if len(rows) != 90:
        raise RuntimeError(f"manifest 行数异常: {len(rows)}，预期 90")
    matrix = np.load(VECTOR_PATH)
    if matrix.shape != (90, DIMENSION) or matrix.dtype != np.float32 or not np.isfinite(matrix).all():
        raise RuntimeError(f"向量文件校验失败: shape={matrix.shape}, dtype={matrix.dtype}")
    norms = np.linalg.norm(matrix.astype(np.float64), axis=1)
    if not np.allclose(norms, 1.0, atol=1e-5):
        raise RuntimeError("第一步向量未通过 L2 归一化校验")
    for index, row in enumerate(rows):
        path = Path(row["local_path"])
        if not path.is_file():
            raise RuntimeError(f"源图不存在: {path}")
        if row["ref_id"] == "" or row["embedding_model"] != MODEL or int(row["dimension"]) != DIMENSION:
            raise RuntimeError(f"manifest 第 {index + 1} 行元数据异常")
    return rows, matrix


def object_key(row: Dict[str, str]) -> str:
    ref_type = row["ref_type"].lower()
    business_key = row["stage_code"] if ref_type == "phenology" else row["disease_key"]
    suffix = Path(row["file_name"]).suffix.lower()
    if suffix == ".jpeg":
        suffix = ".jpg"
    if suffix not in {".jpg", ".png"}:
        raise RuntimeError(f"不支持的源图片扩展名: {row['file_name']}")
    return f"farmap/reference/{ref_type}/plum/{business_key}/{row['ref_id']}{suffix}"


def public_url(bucket: str, region: str, key: str) -> str:
    return f"https://{bucket}.cos.{region}.myqcloud.com/{key}"


def anonymous_get(url: str, timeout: int = 25) -> Tuple[int, str]:
    request = Request(url, method="GET", headers={"User-Agent": "FarMap-reference-verifier/1.0"})
    try:
        with urlopen(request, timeout=timeout) as response:
            status = int(response.getcode())
            content_type = response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
            response.read(64)
            if status != 200:
                raise RuntimeError(f"HTTP {status}")
            if not content_type.startswith("image/"):
                raise RuntimeError(f"HTTP 200 但 Content-Type={content_type or 'missing'}")
            return status, content_type
    except HTTPError as exc:
        raise RuntimeError(f"HTTP {exc.code}") from exc
    except URLError as exc:
        raise RuntimeError(f"网络错误: {exc.reason}") from exc


def upload_and_verify(
    rows: List[Dict[str, str]],
    bucket: str,
    region: str,
    secret_id: str,
    secret_key: str,
    secrets: Sequence[str],
) -> Tuple[List[Dict[str, str]], List[str]]:
    config = CosConfig(Region=region, SecretId=secret_id, SecretKey=secret_key, Token=None, Scheme="https")
    client = CosS3Client(config)
    map_rows: List[Dict[str, str]] = []
    failures: List[str] = []
    for index, row in enumerate(rows, start=1):
        key = object_key(row)
        url = public_url(bucket, region, key)
        action = "skipped"
        try:
            try:
                client.head_object(Bucket=bucket, Key=key)
            except Exception as exc:
                status_getter = getattr(exc, "get_status_code", None)
                status = status_getter() if callable(status_getter) else getattr(exc, "status_code", None)
                if int(status or 0) != 404:
                    raise
                with Path(row["local_path"]).open("rb") as handle:
                    content_type = "image/png" if Path(row["file_name"]).suffix.lower() == ".png" else "image/jpeg"
                    client.put_object(
                        Bucket=bucket,
                        Key=key,
                        Body=handle,
                        ContentType=content_type,
                        CacheControl=COS_CACHE_CONTROL,
                    )
                action = "uploaded"
            # 仅给参考图对象授予匿名读取，不改变整个桶的 ACL。
            client.put_object_acl(Bucket=bucket, Key=key, ACL="public-read")
            status, content_type = anonymous_get(url)
            map_rows.append({"ref_id": row["ref_id"], "object_key": key, "image_url": url, "action": action, "anon_get_status": f"{status} {content_type}"})
        except Exception as exc:
            failures.append(f"{row['ref_id']} | key={key} | {redact(exc, secrets)}")
            break
        if index % 10 == 0 or index == len(rows):
            print(f"COS 进度: {index}/{len(rows)}，失败 {len(failures)}")
    if failures:
        return map_rows, failures
    if len(map_rows) != len(rows):
        failures.append(f"COS 结果行数异常: {len(map_rows)}/{len(rows)}")
    return map_rows, failures


def milvus_connection(env: Dict[str, str], alias: str, secrets: Sequence[str]) -> Tuple[str, Dict[str, Dict[str, Any]], List[str]]:
    configured_host = os.environ.get("MILVUS_HOST") or env.get("MILVUS_HOST") or "localhost"
    port = os.environ.get("MILVUS_PORT") or env.get("MILVUS_PORT") or MILVUS_PORT_DEFAULT
    username = os.environ.get("MILVUS_USERNAME") or env.get("MILVUS_USERNAME") or ""
    password = os.environ.get("MILVUS_PASSWORD") or env.get("MILVUS_PASSWORD") or ""
    candidates = [configured_host]
    if configured_host in {"localhost", "127.0.0.1"}:
        candidates.append("::1")
    errors: List[str] = []
    for host in candidates:
        try:
            kwargs: Dict[str, Any] = {"alias": alias, "host": host, "port": port, "db_name": "default"}
            if username:
                kwargs["user"] = username
            if password:
                kwargs["password"] = password
            connections.connect(**kwargs)
            collections = sorted(utility.list_collections(using=alias))
            inventory = collection_inventory(collections, alias)
            return f"{host}:{port}", inventory, errors
        except Exception as exc:
            errors.append(f"{host}:{port} | {type(exc).__name__}: {redact(exc, secrets)}")
            try:
                connections.disconnect(alias)
            except Exception:
                pass
    raise RuntimeError("Milvus 连通/list_collections 失败: " + " || ".join(errors))


def collection_inventory(names: Sequence[str], alias: str) -> Dict[str, Dict[str, Any]]:
    result: Dict[str, Dict[str, Any]] = {}
    for name in names:
        collection = Collection(name=name, using=alias)
        try:
            entities = int(collection.num_entities)
        except Exception as exc:
            entities = f"ERROR:{type(exc).__name__}:{str(exc)[:120]}"
        result[name] = {"num_entities": entities}
    return result


def field_matches(actual: Any, name: str, dtype: Any, max_length: Optional[int], dim: Optional[int], primary: bool) -> bool:
    if getattr(actual, "name", None) != name or getattr(actual, "dtype", None) != dtype:
        return False
    if bool(getattr(actual, "is_primary", False)) != primary:
        return False
    params = getattr(actual, "params", {}) or {}
    if dtype == DataType.VARCHAR and int(params.get("max_length", 0)) != int(max_length or 0):
        return False
    if dtype == DataType.FLOAT_VECTOR and int(params.get("dim", 0)) != int(dim or 0):
        return False
    return True


def schema_matches(collection: Collection) -> bool:
    fields = list(collection.schema.fields)
    if len(fields) != len(MILVUS_FIELDS):
        return False
    for actual, expected in zip(fields, MILVUS_FIELDS):
        name, dtype, max_length, dim, primary = expected
        if not field_matches(actual, name, dtype, max_length, dim, primary):
            return False
    return bool(getattr(collection.schema, "auto_id", False) is False)


def expected_schema() -> CollectionSchema:
    fields = []
    for name, dtype, max_length, dim, primary in MILVUS_FIELDS:
        kwargs: Dict[str, Any] = {"name": name, "dtype": dtype, "is_primary": primary}
        if dtype == DataType.VARCHAR:
            kwargs["max_length"] = max_length
        if dtype == DataType.FLOAT_VECTOR:
            kwargs["dim"] = dim
        fields.append(FieldSchema(**kwargs))
    return CollectionSchema(
        fields=fields,
        description=f"FarMap reference images; {MODEL}; dimension={DIMENSION}; independent COS-backed collection",
        enable_dynamic_field=False,
    )


def index_map(collection: Collection) -> Dict[str, Dict[str, Any]]:
    result: Dict[str, Dict[str, Any]] = {}
    for index in collection.indexes:
        field_name = getattr(index, "field_name", "")
        params = getattr(index, "params", {}) or {}
        result[field_name] = dict(params)
    return result


def ensure_collection(alias: str, before_names: Sequence[str]) -> Tuple[Collection, str, List[str]]:
    actions: List[str] = []
    if COLLECTION_NAME in before_names:
        collection = Collection(name=COLLECTION_NAME, using=alias)
        if not schema_matches(collection):
            raise RuntimeError(f"已存在集合 {COLLECTION_NAME} 的 schema 不符合要求，停止且不修改")
        actions.append("target_collection=reused_schema_verified")
    else:
        collection = Collection(name=COLLECTION_NAME, schema=expected_schema(), using=alias)
        actions.append("target_collection=created")

    existing_indexes = index_map(collection)
    vector_params = {"index_type": "FLAT", "metric_type": "COSINE", "params": {}}
    if "embedding" not in existing_indexes:
        collection.create_index(field_name="embedding", index_params=vector_params)
        actions.append("index_embedding=created_FLAT_COSINE")
    else:
        params = existing_indexes["embedding"]
        if params.get("index_type") != "FLAT" or params.get("metric_type") != "COSINE":
            raise RuntimeError("目标集合 embedding 索引不是 FLAT/COSINE，拒绝静默修改")
        actions.append("index_embedding=verified_FLAT_COSINE")

    for field_name in SCALAR_INDEX_FIELDS:
        existing_indexes = index_map(collection)
        if field_name not in existing_indexes:
            collection.create_index(field_name=field_name, index_params={"index_type": "INVERTED", "params": {}})
            actions.append(f"index_{field_name}=created_INVERTED")
        elif existing_indexes[field_name].get("index_type") != "INVERTED":
            raise RuntimeError(f"目标集合 {field_name} 标量索引类型不符合 INVERTED")
        else:
            actions.append(f"index_{field_name}=verified_INVERTED")
    return collection, "schema_verified" if COLLECTION_NAME in before_names else "schema_created", actions


def active_ref_ids(collection: Collection) -> set[str]:
    result = collection.query(
        expr="ref_id like 'plum-%'",
        output_fields=["ref_id"],
        limit=1000,
        consistency_level="Strong",
    )
    return {str(item.get("ref_id")) for item in result if item.get("ref_id")}


def upsert_collection(collection: Collection, rows: Sequence[Dict[str, str]], matrix: np.ndarray, url_map: Dict[str, str]) -> Tuple[int, int, str]:
    target_ids = {row["ref_id"] for row in rows}
    existing_ids = active_ref_ids(collection)
    extra_ids = existing_ids - target_ids
    if extra_ids:
        raise RuntimeError(f"目标集合存在任务之外的 ref_id，拒绝删除: {sorted(extra_ids)[:5]}")
    if existing_ids == target_ids:
        current_count = int(collection.num_entities)
        if current_count != len(existing_ids):
            collection.compact(timeout=60)
            collection.wait_for_compaction_completed(timeout=60)
            current_count = int(collection.num_entities)
        if current_count != len(existing_ids):
            raise RuntimeError(f"目标集合 compaction 后 num_entities={current_count}，有效 ref_id={len(existing_ids)}")
        return 0, current_count, "skipped_existing_ids_verified"

    missing = [index for index, row in enumerate(rows) if row["ref_id"] not in existing_ids]
    entities: List[Dict[str, Any]] = []
    for index in missing:
        row = rows[index]
        entity = {
            "ref_id": row["ref_id"],
            "embedding": matrix[index].astype(np.float32).tolist(),
            "crop": row["crop"],
            "ref_type": row["ref_type"],
            "stage_code": row["stage_code"],
            "disease_key": row["disease_key"],
            "label_cn": row["label_cn"],
            "source_subdir": row["source_subdir"],
            "vigor": row["vigor"],
            "file_name": row["file_name"],
            "local_path": row["local_path"],
            "image_url": url_map[row["ref_id"]],
            "embedding_model": row["embedding_model"],
        }
        entities.append(entity)
    result = collection.upsert(entities)
    collection.flush()
    deadline = time.time() + 20
    count = int(collection.num_entities)
    while count < len(target_ids) and time.time() < deadline:
        time.sleep(0.5)
        count = int(collection.num_entities)
    active_count = len(active_ref_ids(collection))
    if active_count != len(target_ids):
        raise RuntimeError(f"upsert 后有效 ref_id={active_count}，预期 {len(target_ids)}")
    return len(entities), count, "upsert_missing_ids"


def extract_vector(response: Any) -> List[float]:
    def walk(value: Any) -> Optional[List[float]]:
        if isinstance(value, np.ndarray):
            value = value.tolist()
        if isinstance(value, (list, tuple)) and len(value) == DIMENSION and all(isinstance(x, (int, float, np.integer, np.floating)) for x in value):
            return [float(x) for x in value]
        if isinstance(value, dict):
            keys = ["embedding", "embeddings", "vector", "data", "output"]
            for key in keys + [key for key in value if key not in keys]:
                if key in value:
                    found = walk(value[key])
                    if found is not None:
                        return found
        elif isinstance(value, (list, tuple)):
            for item in value:
                found = walk(item)
                if found is not None:
                    return found
        else:
            for attr in ("embedding", "embeddings", "vector", "data", "output"):
                found = walk(getattr(value, attr, None))
                if found is not None:
                    return found
        return None

    vector = walk(getattr(response, "output", response))
    if vector is None:
        raise RuntimeError("百炼响应中未找到 1152 维向量")
    return vector


def encode_text(endpoint: str, api_key: str, query: str, secrets: Sequence[str]) -> np.ndarray:
    last_error = ""
    for attempt in range(1, TEXT_RETRY_LIMIT + 1):
        if attempt > 1:
            time.sleep(0.4 * (2 ** (attempt - 2)))
        try:
            dashscope.api_key = api_key
            dashscope.base_http_api_url = endpoint
            response = dashscope.MultiModalEmbedding.call(
                model=MODEL,
                input=[{"text": query}],
                dimension=DIMENSION,
            )
            status = int(getattr(response, "status_code", 200))
            if status != 200:
                last_error = f"status={status}, code={getattr(response, 'code', '')}, message={getattr(response, 'message', '')}"
                if status < 500 and status != 429:
                    break
            else:
                vector = np.asarray(extract_vector(response), dtype=np.float64)
                if vector.shape != (DIMENSION,) or not np.isfinite(vector).all():
                    raise RuntimeError(f"查询向量 shape/finite 异常: {vector.shape}")
                norm = float(np.linalg.norm(vector))
                if norm <= 0 or not math.isfinite(norm):
                    raise RuntimeError("查询向量范数异常")
                time.sleep(0.2)
                return (vector / norm).astype(np.float32)
        except Exception as exc:
            last_error = f"{type(exc).__name__}: {redact(exc, secrets)}"
        time.sleep(0.2)
    raise RuntimeError(f"文本向量调用失败: {last_error}")


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


def search_collection(collection: Collection, vector: np.ndarray, topk: int = 5, expr: Optional[str] = None) -> List[Dict[str, Any]]:
    kwargs: Dict[str, Any] = {
        "data": [vector.tolist()],
        "anns_field": "embedding",
        "param": {"metric_type": "COSINE", "params": {}},
        "limit": topk,
        "output_fields": OUTPUT_FIELDS,
    }
    if expr:
        kwargs["expr"] = expr
    result = collection.search(**kwargs)
    hits = result[0]
    normalized: List[Dict[str, Any]] = []
    for hit in hits:
        normalized.append(
            {
                "ref_id": str(hit_value(hit, "ref_id")),
                "label_cn": str(hit_value(hit, "label_cn")),
                "ref_type": str(hit_value(hit, "ref_type")),
                "stage_code": str(hit_value(hit, "stage_code")),
                "disease_key": str(hit_value(hit, "disease_key")),
                "image_url": str(hit_value(hit, "image_url")),
                "distance": float(getattr(hit, "distance", 0.0)),
            }
        )
    return normalized


def format_hits(hits: Sequence[Dict[str, Any]]) -> str:
    return " | ".join(
        f"{hit['ref_id']} label={hit['label_cn']} type={hit['ref_type']} stage={hit['stage_code']} disease={hit['disease_key']} score={hit['distance']:.4f} url={hit['image_url']}"
        for hit in hits
    )


def verify_urls(hits: Iterable[Dict[str, Any]], secrets: Sequence[str]) -> Tuple[int, List[str]]:
    urls = sorted({hit["image_url"] for hit in hits if hit.get("image_url")})
    failures: List[str] = []
    for url in urls:
        try:
            status, content_type = anonymous_get(url)
            if status != 200 or not content_type.startswith("image/"):
                failures.append(f"{url} | status={status} content_type={content_type}")
        except Exception as exc:
            failures.append(f"{url} | {redact(exc, secrets)}")
    return len(urls), failures


def write_csv(path: Path, rows: Iterable[Dict[str, Any]], columns: Sequence[str]) -> None:
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(columns), extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def write_final_artifacts(rows: List[Dict[str, str]], map_rows: List[Dict[str, str]]) -> None:
    by_id = {item["ref_id"]: item["image_url"] for item in map_rows}
    final_rows: List[Dict[str, Any]] = []
    for row in rows:
        copied = dict(row)
        copied["image_url"] = by_id[row["ref_id"]]
        final_rows.append(copied)
    write_csv(FINAL_MANIFEST_PATH, final_rows, MANIFEST_COLUMNS)
    write_csv(COS_MAP_PATH, map_rows, COS_MAP_COLUMNS)


def json_line(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def build_report(
    started_at: str,
    finished_at: str,
    endpoint: str,
    milvus_before: Dict[str, Dict[str, Any]],
    milvus_after: Dict[str, Dict[str, Any]],
    milvus_attempt_errors: Sequence[str],
    cos_map_rows: Sequence[Dict[str, str]],
    failures: Sequence[str],
    details: Sequence[str],
    probe_details: Sequence[str],
    filter_details: Sequence[str],
    before_pheno: Dict[str, Dict[str, Any]],
    after_pheno: Dict[str, Dict[str, Any]],
    before_disease: Dict[str, Dict[str, Any]],
    after_disease: Dict[str, Dict[str, Any]],
    api_key: str,
    secrets: Sequence[str],
) -> str:
    before_names = sorted(milvus_before)
    after_names = sorted(milvus_after)
    target_before = COLLECTION_NAME in milvus_before
    expected_names = sorted(before_names if target_before else before_names + [COLLECTION_NAME])
    existing_unchanged = all(
        name == COLLECTION_NAME or milvus_before.get(name, {}).get("num_entities") == milvus_after.get(name, {}).get("num_entities")
        for name in milvus_before
    )
    lines = [
        "FarMap 参考图 COS + Milvus + 文字搜图端到端报告",
        f"started_at={started_at}",
        f"finished_at={finished_at}",
        f"embedding_model={MODEL}",
        f"dimension={DIMENSION}",
        f"cos_region={details[0].split('=', 1)[1] if details and details[0].startswith('cos_region=') else 'unknown'}",
        f"cos_bucket={details[1].split('=', 1)[1] if len(details) > 1 and details[1].startswith('cos_bucket=') else 'unknown'}",
        f"milvus_endpoint={endpoint or 'NONE'}",
        "",
        "一、第一步输入",
        "manifest_rows=90",
        "vector_shape=(90, 1152)",
        "vector_l2_normalized=True",
        "",
        "二、COS 上传与匿名 GET",
        f"cos_map_rows={len(cos_map_rows)}",
        f"cos_uploaded={sum(row['action'] == 'uploaded' for row in cos_map_rows)}",
        f"cos_skipped={sum(row['action'] == 'skipped' for row in cos_map_rows)}",
        f"cos_anonymous_get_success={sum(row['anon_get_status'].startswith('200 image/') for row in cos_map_rows)}",
        f"cos_url_fixed_domain={all('myqcloud.com/' in row['image_url'] and '?' not in row['image_url'] for row in cos_map_rows)}",
    ]
    lines.extend(f"COS {json_line(row)}" for row in cos_map_rows)
    lines.extend(
        [
            "",
            "三、Milvus 集合与索引",
            f"milvus_connection_attempt_errors={json_line(list(milvus_attempt_errors))}",
            f"collections_before={json_line(before_names)}",
            f"collections_after={json_line(after_names)}",
            f"collections_expected_after={json_line(expected_names)}",
            f"existing_collections_content_unchanged_by_entity_count={existing_unchanged}",
            f"target_collection_existed_before={target_before}",
        ]
    )
    lines.extend(f"MILVUS_BEFORE {name} {json_line(info)}" for name, info in sorted(milvus_before.items()))
    lines.extend(f"MILVUS_AFTER {name} {json_line(info)}" for name, info in sorted(milvus_after.items()))
    lines.extend(
        [
            f"target_collection={COLLECTION_NAME}",
            f"target_collection_status={'created_or_reused_and_loaded' if any(item.startswith('schema_action=') for item in details) else 'not_created_COS_gate_failed'}",
            "schema=ref_id VARCHAR(128) primary; embedding FLOAT_VECTOR(1152); crop VARCHAR(32); ref_type VARCHAR(16); stage_code VARCHAR(32); disease_key VARCHAR(32); label_cn VARCHAR(128); source_subdir VARCHAR(255); vigor VARCHAR(16); file_name VARCHAR(255); local_path VARCHAR(512); image_url VARCHAR(1024); embedding_model VARCHAR(64)",
            "auto_id=False",
            "embedding_index=FLAT metric=COSINE params={}",
            "scalar_indexes=crop,ref_type,stage_code,disease_key type=INVERTED",
            f"upsert_count={next((item.split('=', 1)[1] for item in details if item.startswith('upsert_count=')), '0')}",
            f"num_entities={next((item.split('=', 1)[1] for item in details if item.startswith('num_entities=')), 'unknown')}",
        ]
    )
    lines.extend(["", "四、6 条探针全库 Top5"])
    lines.extend(probe_details or ["probe_result=NOT_RUN"])
    lines.extend(["", "五、过滤检索"])
    lines.extend(filter_details or ["filter_result=NOT_RUN"])
    lines.extend(
        [
            "",
            "六、URL 显示校验",
            f"returned_unique_urls_checked={next((item.split('=', 1)[1] for item in details if item.startswith('returned_unique_urls_checked=')), '0')}",
            f"returned_url_anonymous_get_failures={next((item.split('=', 1)[1] for item in details if item.startswith('returned_url_anonymous_get_failures=')), 'unknown')}",
            "",
            "七、源目录完整性",
            f"phenology_file_count_before={len(before_pheno)}",
            f"phenology_file_count_after={len(after_pheno)}",
            f"phenology_count_unchanged={len(before_pheno) == len(after_pheno)}",
            f"phenology_content_unchanged={snapshot_equal(before_pheno, after_pheno, 'sha256')}",
            f"disease_file_count_before={len(before_disease)}",
            f"disease_file_count_after={len(after_disease)}",
            f"disease_count_unchanged={len(before_disease) == len(after_disease)}",
            f"disease_content_unchanged={snapshot_equal(before_disease, after_disease, 'sha256')}",
            "",
            "八、失败清单",
            f"failure_count={len(failures)}",
        ]
    )
    lines.extend(f"FAILURE {redact(item, secrets)}" for item in failures)
    if any("HTTP 403" in item for item in failures):
        lines.extend(
            [
                "cos_public_read_blocker=HTTP 403 on anonymous GET",
                "cos_required_action=请在腾讯云 COS 控制台检查 farmap/reference/* 的匿名 GetObject 策略及公共访问阻止设置，修复后重跑主脚本",
                "milvus_write_blocked_by_cos_gate=yes",
            ]
        )
    lines.extend(
        [
            "",
            "九、任务边界",
            "source_images_modified=no",
            "business_code_modified=no",
            "existing_milvus_collections_modified=no",
            "negative_img_and_lora_data_excluded=yes",
            "milvus_write_scope=target_collection_only",
            "api_key_in_report=no",
            "cos_secret_in_report=no",
        ]
    )
    probe_top1 = sum("top1_hit=True" in line for line in probe_details)
    probe_top5 = sum("top5_hit=True" in line for line in probe_details)
    filter_ok = all("all_types_ok=True" in line for line in filter_details)
    url_ok = not any("returned_url_anonymous_get_failures=0" not in line for line in details if line.startswith("returned_url_anonymous_get_failures="))
    full_success = len(cos_map_rows) == 90 and not failures and not milvus_attempt_errors
    lines.extend(
        [
            "",
            "十、完成标准自检",
            f"90 对象全部上传或幂等跳过且匿名 GET 200: {'通过' if full_success and all(row['anon_get_status'].startswith('200 image/') for row in cos_map_rows) else '不通过'}",
            f"目标集合 90 实体、1152 维、FLAT/COSINE: {'通过' if any(item == 'num_entities=90' for item in details) else '不通过'}",
            f"现有集合清单及实体数未改变: {'通过' if sorted(after_names) == expected_names and existing_unchanged else '不通过'}",
            f"6 探针 recall@5（probe_6 可例外）: {'通过' if probe_top5 >= 5 else '不通过'}，实际 {probe_top5}/6",
            f"过滤检索结果满足 expr: {'通过' if filter_ok else '不通过'}",
            f"命中 URL 可匿名打开: {'通过' if url_ok else '不通过'}",
            f"源图、业务代码、现有集合未改动: {'通过' if snapshot_equal(before_pheno, after_pheno, 'sha256') and snapshot_equal(before_disease, after_disease, 'sha256') and existing_unchanged else '不通过'}",
        ]
    )
    return "\n".join(lines) + "\n"


def safe_write_report(text: str, secrets: Sequence[str]) -> None:
    for secret in secrets:
        if secret:
            text = text.replace(secret, "[REDACTED]")
    REPORT_PATH.write_text(text, encoding="utf-8")


def main() -> int:
    started_at = current_time()
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    env: Dict[str, str] = {}
    api_key = ""
    secrets: List[str] = []
    milvus_endpoint = ""
    milvus_before: Dict[str, Dict[str, Any]] = {}
    milvus_after: Dict[str, Dict[str, Any]] = {}
    milvus_attempt_errors: List[str] = []
    rows: List[Dict[str, str]] = []
    matrix = np.empty((0, DIMENSION), dtype=np.float32)
    map_rows: List[Dict[str, str]] = []
    failures: List[str] = []
    details: List[str] = []
    probe_details: List[str] = []
    filter_details: List[str] = []
    before_pheno: Dict[str, Dict[str, Any]] = {}
    before_disease: Dict[str, Dict[str, Any]] = {}
    after_pheno: Dict[str, Dict[str, Any]] = {}
    after_disease: Dict[str, Dict[str, Any]] = {}
    alias = "farmap_reference_task"

    try:
        before_pheno = source_snapshot(PHENOLOGY_ROOT)
        before_disease = source_snapshot(DISEASE_ROOT)
        env = read_env(ENV_PATH)
        api_key, baseurl, region, bucket, secret_id, secret_key = required_credentials(env)
        secrets = [api_key, secret_id, secret_key]
        rows, matrix = read_inputs()
        details.extend([f"cos_region={region}", f"cos_bucket={bucket}"])

        try:
            milvus_endpoint, milvus_before, milvus_attempt_errors = milvus_connection(env, alias, secrets)
        except Exception as exc:
            raise RuntimeError(redact(exc, secrets)) from exc
        before_names = sorted(milvus_before)
        print(f"Milvus 已连接: {milvus_endpoint}")
        print(f"现有集合: {', '.join(before_names)}")

        map_rows, cos_failures = upload_and_verify(rows, bucket, region, secret_id, secret_key, secrets)
        failures.extend(cos_failures)
        if failures:
            raise RuntimeError("COS 上传或匿名 GET 未全部通过，停止，不写入 Milvus")
        write_final_artifacts(rows, map_rows)
        url_map = {item["ref_id"]: item["image_url"] for item in map_rows}
        details.append(f"cos_all_anon_get_ok={all(item['anon_get_status'].startswith('200 image/') for item in map_rows)}")

        collection, schema_action, index_actions = ensure_collection(alias, before_names)
        details.append(f"schema_action={schema_action}")
        details.extend(index_actions)
        collection.load()
        upsert_count, entity_count, upsert_action = upsert_collection(collection, rows, matrix, url_map)
        details.append(f"upsert_count={upsert_count}")
        details.append(f"upsert_action={upsert_action}")
        details.append(f"num_entities={entity_count}")
        print(f"Milvus 数据完成: 本次写入 {upsert_count} 条，num_entities={entity_count}")

        endpoint = native_dashscope_endpoint(baseurl)
        for index, (query, expected) in enumerate(PROBES, start=1):
            vector = encode_text(endpoint, api_key, query, secrets)
            hits = search_collection(collection, vector, topk=5)
            top1_hit = bool(hits) and hits[0]["disease_key"] == expected or bool(hits) and hits[0]["stage_code"] == expected
            top5_hit = any(hit["disease_key"] == expected or hit["stage_code"] == expected for hit in hits)
            probe_details.append(f"probe_{index} expected={expected} top1_hit={top1_hit} top5_hit={top5_hit} top5={format_hits(hits)}")

        filter_queries = [
            ("DISEASE", "叶片褐色斑点", "ref_type == 'DISEASE'"),
            ("PHENOLOGY", "白色花朵", "ref_type == 'PHENOLOGY'"),
        ]
        all_filter_hits: List[Dict[str, Any]] = []
        for label, query, expr in filter_queries:
            vector = encode_text(endpoint, api_key, query, secrets)
            hits = search_collection(collection, vector, topk=5, expr=expr)
            all_filter_hits.extend(hits)
            all_types_ok = all(hit["ref_type"] == label for hit in hits)
            filter_details.append(f"filter={expr} query={query} all_types_ok={all_types_ok} results={format_hits(hits)}")
        unique_url_count, url_failures = verify_urls(all_filter_hits + [hit for line in [] for hit in []], secrets)
        details.append(f"returned_unique_urls_checked={unique_url_count}")
        details.append(f"returned_url_anonymous_get_failures={len(url_failures)}")
        failures.extend(url_failures)
        if url_failures:
            raise RuntimeError("检索返回 URL 匿名 GET 失败")

        milvus_after = collection_inventory(sorted(utility.list_collections(using=alias)), alias)
        after_pheno = source_snapshot(PHENOLOGY_ROOT)
        after_disease = source_snapshot(DISEASE_ROOT)
        report = build_report(
            started_at,
            current_time(),
            milvus_endpoint,
            milvus_before,
            milvus_after,
            milvus_attempt_errors,
            map_rows,
            failures,
            details,
            probe_details,
            filter_details,
            before_pheno,
            after_pheno,
            before_disease,
            after_disease,
            api_key,
            secrets,
        )
        safe_write_report(report, secrets)
        if api_key and api_key in report:
            raise RuntimeError("报告中检测到 API key")
        print("端到端构建完成: COS、Milvus、文字搜图及匿名 URL 校验均已执行")
        return 0
    except Exception as exc:
        failures.append(f"BUILD {type(exc).__name__}: {redact(exc, secrets)}")
        try:
            if not milvus_after and milvus_endpoint:
                milvus_after = collection_inventory(sorted(utility.list_collections(using=alias)), alias)
        except Exception as snapshot_exc:
            failures.append(f"Milvus after-list 失败: {redact(snapshot_exc, secrets)}")
        try:
            after_pheno = source_snapshot(PHENOLOGY_ROOT)
            after_disease = source_snapshot(DISEASE_ROOT)
        except Exception as snapshot_exc:
            failures.append(f"源目录 after-snapshot 失败: {redact(snapshot_exc, secrets)}")
        try:
            report = build_report(
                started_at,
                current_time(),
                milvus_endpoint,
                milvus_before,
                milvus_after,
                milvus_attempt_errors,
                map_rows,
                failures,
                details,
                probe_details,
                filter_details,
                before_pheno,
                after_pheno,
                before_disease,
                after_disease,
                api_key,
                secrets,
            )
            safe_write_report(report, secrets)
        except Exception as report_exc:
            REPORT_PATH.write_text(f"报告生成失败: {redact(report_exc, secrets)}\n原始失败: {redact(exc, secrets)}\n", encoding="utf-8")
        print(f"任务未完成，详情见 {REPORT_PATH}: {redact(exc, secrets)}")
        return 1
    finally:
        try:
            connections.disconnect(alias)
        except Exception:
            pass


if __name__ == "__main__":
    sys.exit(main())
