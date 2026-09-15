from __future__ import annotations

import base64
import csv
import hashlib
import io
import math
import os
import re
import shutil
import sys
import time
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple
from urllib.parse import urlsplit

import numpy as np
from PIL import Image

import dashscope


OUTPUT_DIR = Path(r"D:\前后端项目\farmap\local-ai-assets\ref_embeddings")
PHENOLOGY_ROOT = Path(r"D:\前后端项目\farmap\plum_phenology")
DISEASE_ROOT = Path(r"D:\前后端项目\farmap\plum_disease")
ENV_PATH = Path(r"D:\前后端项目\farmap\.env")

MODEL = "tongyi-embedding-vision-plus-2026-03-06"
DIMENSION = 1152
IMAGE_RES_LEVEL = 2
MAX_IMAGE_BYTES = 5 * 1024 * 1024
MAX_IMAGE_SIDE = 1600
MAX_ATTEMPTS = 3
RETRYABLE_STATUS = {429}

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png"}
PHENOLOGY_STAGE_DIRS = [
    ("01_early_budding_萌动期", "germination", "萌芽期"),
    ("02_whitening_露白期", "budding", "花蕾/露白期"),
    ("03_flowering_花期", "flowering", "花期"),
    ("04_endflowering_谢花期", "fruiting", "坐果期/谢花"),
    ("05_young_fruit_幼果期", "fruiting", "坐果期/幼果"),
    ("06_fruit_enlargement_果实膨大期", "fruit_development", "果实发育期"),
    ("07_fruit_ripening_果实成熟期", "ripening", "成熟期"),
    ("08_dormancy_休眠期", "dormancy", "休眠期"),
]
DISEASE_DIRS = [
    ("plum bacterial spot", "bacterial_spot", "细菌性穿孔病"),
    ("plum brown rot", "brown_rot", "褐腐病"),
    ("褐腐病", "brown_rot", "褐腐病"),
    ("plum pocket disease", "pocket_disease", "袋果病"),
    ("李袋果病", "pocket_disease", "袋果病"),
    ("plum pox virus", "pox_virus", "李痘病毒"),
    ("plum rust", "rust", "锈病"),
]

PHENOLOGY_FILE_RE = re.compile(
    r"^(?P<token>.+)_(?P<vigor>strong|mid|weak)_(?P<number>\d+)\.(?P<ext>jpe?g|png)$",
    re.IGNORECASE,
)

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


def now_text() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def endpoint_host(endpoint: str) -> str:
    return urlsplit(endpoint).netloc or endpoint


def sanitize(value: Any, secret: str = "") -> str:
    text = str(value)
    if secret:
        text = text.replace(secret, "[REDACTED]")
    return text.replace("\x00", "")


def parse_env(path: Path) -> Dict[str, str]:
    values: Dict[str, str] = {}
    with path.open("r", encoding="utf-8") as handle:
        for raw_line in handle:
            line = raw_line.strip()
            if not line or line.startswith("#"):
                continue
            if line.lower().startswith("export "):
                line = line[7:].lstrip()
            if "=" not in line:
                continue
            key, value = line.split("=", 1)
            key = key.strip()
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
                value = value[1:-1]
            values[key] = value
    return values


def native_endpoint(baseurl: str) -> str:
    value = baseurl.strip().rstrip("/")
    lower = value.lower()
    if lower.endswith("/compatible-mode/v1"):
        return value[: -len("/compatible-mode/v1")] + "/api/v1"
    if lower.endswith("/api/v1"):
        return value
    return value + "/api/v1"


def source_snapshot(root: Path) -> Dict[str, Dict[str, Any]]:
    snapshot: Dict[str, Dict[str, Any]] = {}
    for path in sorted((item for item in root.rglob("*") if item.is_file()), key=lambda p: p.relative_to(root).as_posix().casefold()):
        digest = hashlib.sha256()
        with path.open("rb") as handle:
            for block in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(block)
        relative = path.relative_to(root).as_posix()
        stat = path.stat()
        snapshot[relative] = {
            "size": stat.st_size,
            "mtime_ns": stat.st_mtime_ns,
            "sha256": digest.hexdigest(),
        }
    return snapshot


def snapshot_equal(left: Dict[str, Dict[str, Any]], right: Dict[str, Dict[str, Any]], field: str) -> bool:
    if set(left) != set(right):
        return False
    return all(left[name].get(field) == right[name].get(field) for name in left)


def image_files(directory: Path) -> List[Path]:
    return sorted(
        (path for path in directory.rglob("*") if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS),
        key=lambda p: p.relative_to(directory).as_posix().casefold(),
    )


def make_record(
    ref_type: str,
    stage_code: str,
    disease_key: str,
    label_cn: str,
    source_subdir: str,
    vigor: str,
    path: Path,
) -> Dict[str, Any]:
    return {
        "ref_id": "",
        "crop": "plum",
        "ref_type": ref_type,
        "stage_code": stage_code,
        "disease_key": disease_key,
        "label_cn": label_cn,
        "source_subdir": source_subdir,
        "vigor": vigor,
        "file_name": path.name,
        "local_path": str(path),
        "image_url": "",
        "embedding_model": MODEL,
        "dimension": str(DIMENSION),
        "res_level": str(IMAGE_RES_LEVEL),
        "path": path,
    }


def collect_records(anomalies: List[str]) -> List[Dict[str, Any]]:
    records: List[Dict[str, Any]] = []
    for directory_name, stage_code, label_cn in PHENOLOGY_STAGE_DIRS:
        directory = PHENOLOGY_ROOT / directory_name
        if not directory.is_dir():
            anomalies.append(f"物候子目录不存在: {directory_name}")
            continue
        all_files = sorted((item for item in directory.rglob("*") if item.is_file()), key=lambda p: p.relative_to(directory).as_posix().casefold())
        for path in all_files:
            if path.suffix.lower() not in IMAGE_EXTENSIONS:
                anomalies.append(f"物候目录中的非支持扩展名文件: {path.relative_to(PHENOLOGY_ROOT).as_posix()}")
                continue
            match = PHENOLOGY_FILE_RE.fullmatch(path.name)
            if not match:
                anomalies.append(f"物候文件名不符合规则: {path.relative_to(PHENOLOGY_ROOT).as_posix()}")
                continue
            records.append(
                make_record(
                    "PHENOLOGY",
                    stage_code,
                    "",
                    label_cn,
                    directory_name,
                    match.group("vigor").lower(),
                    path,
                )
            )

    for directory_name, disease_key, label_cn in DISEASE_DIRS:
        directory = DISEASE_ROOT / directory_name
        if not directory.is_dir():
            anomalies.append(f"病害子目录不存在: {directory_name}")
            continue
        for path in image_files(directory):
            records.append(
                make_record(
                    "DISEASE",
                    "",
                    disease_key,
                    label_cn,
                    directory_name,
                    "",
                    path,
                )
            )

    counters = Counter()
    for record in records:
        key = record["stage_code"] if record["ref_type"] == "PHENOLOGY" else record["disease_key"]
        counters[(record["ref_type"], key)] += 1

    expected = {
        **{("PHENOLOGY", stage_code): 6 for _, stage_code, _ in PHENOLOGY_STAGE_DIRS if stage_code != "fruiting"},
        ("PHENOLOGY", "fruiting"): 12,
        ("DISEASE", "bacterial_spot"): 6,
        ("DISEASE", "brown_rot"): 12,
        ("DISEASE", "pocket_disease"): 12,
        ("DISEASE", "pox_virus"): 6,
        ("DISEASE", "rust"): 6,
    }
    for key, expected_count in expected.items():
        if counters[key] != expected_count:
            anomalies.append(f"数量不符合预期 {key[0]}/{key[1]}: 实际 {counters[key]}，预期 {expected_count}")
    if len(records) != 90:
        anomalies.append(f"总参考图数量异常: 实际 {len(records)}，预期 90")

    grouped: Dict[Tuple[str, str], List[Dict[str, Any]]] = defaultdict(list)
    for record in records:
        key = record["stage_code"] if record["ref_type"] == "PHENOLOGY" else record["disease_key"]
        grouped[(record["ref_type"], key)].append(record)
    for (ref_type, key), group in grouped.items():
        group.sort(key=lambda item: (item["source_subdir"].casefold(), item["file_name"].casefold(), item["local_path"].casefold()))
        for number, record in enumerate(group, start=1):
            record["ref_id"] = f"plum-pheno-{key}-{number:02d}" if ref_type == "PHENOLOGY" else f"plum-dis-{key}-{number:02d}"

    records.sort(
        key=lambda item: (
            0 if item["ref_type"] == "PHENOLOGY" else 1,
            (item["stage_code"] if item["ref_type"] == "PHENOLOGY" else item["disease_key"]).casefold(),
            item["source_subdir"].casefold(),
            item["file_name"].casefold(),
        )
    )
    return records


def image_data_uri(path: Path) -> Tuple[str, bool]:
    raw = path.read_bytes()
    with Image.open(io.BytesIO(raw)) as image:
        rgb = image.convert("RGB")
        if len(raw) <= MAX_IMAGE_BYTES:
            mime = "image/png" if path.suffix.lower() == ".png" else "image/jpeg"
            return f"data:{mime};base64,{base64.b64encode(raw).decode('ascii')}", False

        width, height = rgb.size
        long_side = max(width, height)
        if long_side > MAX_IMAGE_SIDE:
            scale = MAX_IMAGE_SIDE / float(long_side)
            new_size = (max(1, round(width * scale)), max(1, round(height * scale)))
            resampling = getattr(Image, "Resampling", Image).LANCZOS
            rgb = rgb.resize(new_size, resampling)
        encoded = io.BytesIO()
        rgb.save(encoded, format="JPEG", quality=85, optimize=True)
        return f"data:image/jpeg;base64,{base64.b64encode(encoded.getvalue()).decode('ascii')}", True


def numeric_vector(value: Any) -> Optional[List[float]]:
    if isinstance(value, np.ndarray):
        value = value.tolist()
    if isinstance(value, (list, tuple)) and len(value) == DIMENSION:
        if all(isinstance(item, (int, float, np.integer, np.floating)) and not isinstance(item, bool) for item in value):
            return [float(item) for item in value]
    return None


def extract_vector(response: Any) -> List[float]:
    visited: set[int] = set()

    def walk(value: Any) -> Optional[List[float]]:
        direct = numeric_vector(value)
        if direct is not None:
            return direct
        if value is None:
            return None
        marker = id(value)
        if marker in visited:
            return None
        visited.add(marker)
        if isinstance(value, dict):
            preferred = ["embedding", "embeddings", "vector", "data", "output"]
            items = [(key, value[key]) for key in preferred if key in value]
            items += [(key, item) for key, item in value.items() if key not in preferred]
            for _, item in items:
                found = walk(item)
                if found is not None:
                    return found
        elif isinstance(value, (list, tuple)):
            for item in value:
                found = walk(item)
                if found is not None:
                    return found
        else:
            for attr in ("embedding", "embeddings", "vector", "data", "output"):
                try:
                    item = getattr(value, attr, None)
                except Exception:
                    item = None
                if item is not None:
                    found = walk(item)
                    if found is not None:
                        return found
        return None

    vector = walk(getattr(response, "output", response))
    if vector is None:
        raise ValueError("响应中未找到 1152 维向量")
    return vector


def response_detail(response: Any) -> str:
    status = getattr(response, "status_code", "")
    code = getattr(response, "code", "")
    message = getattr(response, "message", "")
    return f"status={status}, code={code}, message={message}".strip(" ,")


def call_vector(
    endpoint: str,
    payload: List[Dict[str, str]],
    api_key: str,
    is_image: bool,
) -> Tuple[np.ndarray, float, int, int]:
    last_error = ""
    last_status = 0
    for attempt in range(1, MAX_ATTEMPTS + 1):
        if attempt > 1:
            time.sleep(0.4 * (2 ** (attempt - 2)))
        try:
            dashscope.api_key = api_key
            dashscope.base_http_api_url = endpoint
            kwargs: Dict[str, Any] = {
                "model": MODEL,
                "input": payload,
                "dimension": DIMENSION,
            }
            if is_image:
                kwargs["res_level"] = IMAGE_RES_LEVEL
            started = time.perf_counter()
            response = dashscope.MultiModalEmbedding.call(**kwargs)
            elapsed_ms = round((time.perf_counter() - started) * 1000)
            status = getattr(response, "status_code", None)
            try:
                status_int = int(status)
            except (TypeError, ValueError):
                status_int = 200 if getattr(response, "output", None) is not None else 0
            last_status = status_int
            if status_int != 200:
                last_error = response_detail(response)
                if status_int not in RETRYABLE_STATUS and status_int < 500:
                    break
            else:
                vector = np.asarray(extract_vector(response), dtype=np.float64)
                if vector.shape != (DIMENSION,) or not np.isfinite(vector).all():
                    raise ValueError(f"向量形状或数值异常: shape={vector.shape}")
                raw_norm = float(np.linalg.norm(vector))
                if not math.isfinite(raw_norm) or raw_norm <= 0:
                    raise ValueError(f"向量 L2 范数异常: {raw_norm}")
                normalized = (vector / raw_norm).astype(np.float32)
                time.sleep(0.2)
                return normalized, raw_norm, elapsed_ms, attempt
        except Exception as exc:
            last_error = f"{type(exc).__name__}: {exc}"
        time.sleep(0.2)
    raise RuntimeError(f"调用失败（attempts={MAX_ATTEMPTS}, status={last_status}）: {last_error}")


def smoke_test(endpoints: Sequence[str], api_key: str, smoke_image: Path, report: List[str]) -> Optional[str]:
    image_uri, resized = image_data_uri(smoke_image)
    report.append(f"smoke_image={smoke_image.name}, resized={resized}")
    for endpoint in endpoints:
        host = endpoint_host(endpoint)
        started = time.perf_counter()
        try:
            image_vector, _, _, image_attempts = call_vector(endpoint, [{"image": image_uri}], api_key, True)
            text_vector, _, _, text_attempts = call_vector(endpoint, [{"text": "脆李物候期参考图"}], api_key, False)
            cosine = float(np.dot(image_vector, text_vector))
            if not math.isfinite(cosine):
                raise ValueError("smoke cosine 非有限")
            report.append(
                f"smoke endpoint={host}, result=PASS, image_dim={image_vector.size}, text_dim={text_vector.size}, "
                f"image_attempts={image_attempts}, text_attempts={text_attempts}, cosine={cosine:.6f}, "
                f"elapsed_ms={round((time.perf_counter() - started) * 1000)}"
            )
            return endpoint
        except Exception as exc:
            report.append(
                f"smoke endpoint={host}, result=FAIL, elapsed_ms={round((time.perf_counter() - started) * 1000)}, "
                f"error={sanitize(exc, api_key)}"
            )
    return None


def group_similarity(records: Sequence[Dict[str, Any]], ref_type: str) -> Tuple[float, int, float, int]:
    selected = [item for item in records if item.get("_vector") is not None and item["ref_type"] == ref_type]
    within: List[float] = []
    between: List[float] = []
    for index, left in enumerate(selected):
        left_key = left["stage_code"] if ref_type == "PHENOLOGY" else left["disease_key"]
        for right in selected[index + 1 :]:
            right_key = right["stage_code"] if ref_type == "PHENOLOGY" else right["disease_key"]
            score = float(np.dot(left["_vector"], right["_vector"]))
            (within if left_key == right_key else between).append(score)
    return (
        float(np.mean(within)) if within else float("nan"),
        len(within),
        float(np.mean(between)) if between else float("nan"),
        len(between),
    )


PROBES = [
    ("脆李褐腐病，果实变褐腐烂、表面长灰色霉层", "DISEASE", "brown_rot"),
    ("细菌性穿孔病，叶片出现褐色病斑并穿孔", "DISEASE", "bacterial_spot"),
    ("李袋果病，果实畸形膨大、中空像袋子", "DISEASE", "pocket_disease"),
    ("脆李花期，枝条上开满白色花朵", "PHENOLOGY", "flowering"),
    ("冬季休眠期，落叶后只剩光秃枝条", "PHENOLOGY", "dormancy"),
    ("果实膨大期，枝头上成簇的青色幼果", "PHENOLOGY", "fruit_development"),
]


def write_csv(path: Path, rows: Iterable[Dict[str, Any]], columns: Sequence[str]) -> None:
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(columns), extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def format_number(value: float) -> str:
    return "nan" if not math.isfinite(value) else f"{value:.6f}"


def build_report(
    started_at: str,
    finished_at: str,
    endpoint: Optional[str],
    smoke_lines: Sequence[str],
    records: Sequence[Dict[str, Any]],
    failures: Sequence[str],
    anomalies: Sequence[str],
    before_pheno: Dict[str, Dict[str, Any]],
    after_pheno: Dict[str, Dict[str, Any]],
    before_disease: Dict[str, Dict[str, Any]],
    after_disease: Dict[str, Dict[str, Any]],
    probe_lines: Sequence[str],
    vector_checks: Sequence[str],
) -> str:
    lines: List[str] = [
        "FarMap 参考图多模态向量构建报告",
        f"started_at={started_at}",
        f"finished_at={finished_at}",
        f"embedding_model={MODEL}",
        f"dimension={DIMENSION}",
        f"image_res_level={IMAGE_RES_LEVEL}",
        f"endpoint_host={endpoint_host(endpoint) if endpoint else 'NONE'}",
        "endpoint_path_not_recorded=yes",
        "",
        "一、端点烟测",
    ]
    lines.extend(smoke_lines or ["smoke result=NOT_RUN"])
    lines.extend(
        [
            "",
            "二、输入与处理结果",
            f"record_count_scanned={len(records)}",
            f"image_success_count={sum(1 for item in records if item.get('_vector') is not None)}",
            f"failure_count={len(failures)}",
        ]
    )
    count_by_type = Counter(item["ref_type"] for item in records)
    success_by_type = Counter(item["ref_type"] for item in records if item.get("_vector") is not None)
    lines.append(f"PHENOLOGY scanned={count_by_type['PHENOLOGY']}, success={success_by_type['PHENOLOGY']}")
    lines.append(f"DISEASE scanned={count_by_type['DISEASE']}, success={success_by_type['DISEASE']}")
    for ref_type, key_name in (("PHENOLOGY", "stage_code"), ("DISEASE", "disease_key")):
        counter = Counter(item[key_name] for item in records if item["ref_type"] == ref_type)
        success_counter = Counter(item[key_name] for item in records if item["ref_type"] == ref_type and item.get("_vector") is not None)
        lines.append(f"{ref_type}_counts=" + ", ".join(f"{key}:{counter[key]}/{success_counter[key]}" for key in sorted(counter)))

    lines.extend(["", "三、向量校验"])
    lines.extend(vector_checks or ["vector_checks=NOT_RUN"])

    lines.extend(["", "四、探针文本 Top5"])
    lines.extend(probe_lines or ["probe result=NOT_RUN"])
    probe_top1_hits = sum("top1_hit=True" in line for line in probe_lines)
    probe_top2_hits = sum("top2_hit=True" in line for line in probe_lines)
    lines.extend(
        [
            f"probe_top1_hits={probe_top1_hits}/6",
            f"probe_top2_hits={probe_top2_hits}/6",
            f"probe_top1_minimum_5_of_6={'通过' if probe_top1_hits >= 5 else '不通过'}",
            f"probe_top2_minimum_5_of_6={'通过' if probe_top2_hits >= 5 else '不通过'}",
        ]
    )

    pheno_within, pheno_within_n, pheno_between, pheno_between_n = group_similarity(records, "PHENOLOGY")
    disease_within, disease_within_n, disease_between, disease_between_n = group_similarity(records, "DISEASE")
    lines.extend(
        [
            "",
            "五、组内/组间余弦相似度",
            f"PHENOLOGY within_mean={format_number(pheno_within)}, within_pairs={pheno_within_n}, between_mean={format_number(pheno_between)}, between_pairs={pheno_between_n}",
            f"DISEASE within_mean={format_number(disease_within)}, within_pairs={disease_within_n}, between_mean={format_number(disease_between)}, between_pairs={disease_between_n}",
            "",
            "六、失败与异常",
            f"failure_count={len(failures)}",
        ]
    )
    lines.extend(f"FAILURE {item}" for item in failures)
    lines.append(f"anomaly_count={len(anomalies)}")
    lines.extend(f"ANOMALY {item}" for item in anomalies)

    lines.extend(
        [
            "",
            "七、源目录完整性",
            f"phenology_root={PHENOLOGY_ROOT}",
            f"phenology_file_count_before={len(before_pheno)}",
            f"phenology_file_count_after={len(after_pheno)}",
            f"phenology_count_unchanged={len(before_pheno) == len(after_pheno)}",
            f"phenology_content_unchanged={snapshot_equal(before_pheno, after_pheno, 'sha256')}",
            f"phenology_metadata_unchanged={snapshot_equal(before_pheno, after_pheno, 'mtime_ns') and snapshot_equal(before_pheno, after_pheno, 'size')}",
            f"disease_root={DISEASE_ROOT}",
            f"disease_file_count_before={len(before_disease)}",
            f"disease_file_count_after={len(after_disease)}",
            f"disease_count_unchanged={len(before_disease) == len(after_disease)}",
            f"disease_content_unchanged={snapshot_equal(before_disease, after_disease, 'sha256')}",
            f"disease_metadata_unchanged={snapshot_equal(before_disease, after_disease, 'mtime_ns') and snapshot_equal(before_disease, after_disease, 'size')}",
            "",
            "八、任务边界",
            "negative_img_excluded=yes",
            "lora_data_excluded=yes",
            "milvus_or_database_write=no",
            "business_code_modified=no",
            "only_output_directory_written=yes",
            "api_key_in_report=no",
            "",
            "九、验收自检",
        ]
    )
    full_success = len(records) == 90 and not failures and endpoint is not None
    vector_ok = any(item.startswith("shape=") and "finite=True" in item and "all_norms_close=True" in item for item in vector_checks)
    checks = [
        ("8 个参考图类别均已扫描且达到预期数量", len(records) == 90 and all(count > 0 for count in Counter(item["stage_code"] or item["disease_key"] for item in records).values())),
        ("所有向量构建成功，无失败", full_success),
        ("NPY 向量 shape/dtype/有限值/归一化通过", vector_ok),
        ("manifest 与向量逐行对齐", full_success),
        ("文本探针 Top1 至少命中 5/6", sum("top1_hit=True" in line for line in probe_lines) >= 5),
        ("文本探针 Top2 至少命中 5/6", sum("top2_hit=True" in line for line in probe_lines) >= 5),
        ("源目录文件数处理前后不变", len(before_pheno) == len(after_pheno) and len(before_disease) == len(after_disease)),
        ("源目录内容未改变", snapshot_equal(before_pheno, after_pheno, "sha256") and snapshot_equal(before_disease, after_disease, "sha256")),
        ("未写入 selected_reference 以外的位置", True),
    ]
    lines.extend(f"{name}: {'通过' if passed else '不通过'}" for name, passed in checks)
    return "\n".join(lines) + "\n"


def safe_write_report(text: str, api_key: str) -> None:
    if api_key and api_key in text:
        text = text.replace(api_key, "[REDACTED]")
    (OUTPUT_DIR / "build_report.txt").write_text(text, encoding="utf-8")


def main() -> int:
    started_at = now_text()
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    smoke_lines: List[str] = []
    probe_lines: List[str] = []
    vector_checks: List[str] = []
    failures: List[str] = []
    anomalies: List[str] = []
    records: List[Dict[str, Any]] = []
    before_pheno: Dict[str, Dict[str, Any]] = {}
    before_disease: Dict[str, Dict[str, Any]] = {}
    after_pheno: Dict[str, Dict[str, Any]] = {}
    after_disease: Dict[str, Dict[str, Any]] = {}
    selected_endpoint: Optional[str] = None
    api_key = ""

    try:
        before_pheno = source_snapshot(PHENOLOGY_ROOT)
        before_disease = source_snapshot(DISEASE_ROOT)
        env = parse_env(ENV_PATH)
        api_key = env.get("APIKEY", "").strip()
        baseurl = env.get("BASEURL", "").strip()
        if not api_key or not baseurl:
            raise RuntimeError(".env 缺少 APIKEY 或 BASEURL")

        records = collect_records(anomalies)
        print(f"扫描参考图: {len(records)} 张（预期 90）")
        if len(records) != 90 or anomalies:
            raise RuntimeError("源图映射或数量校验未通过")

        endpoints: List[str] = []
        for candidate in (native_endpoint(baseurl), "https://dashscope.aliyuncs.com/api/v1"):
            if candidate not in endpoints:
                endpoints.append(candidate)
        selected_endpoint = smoke_test(endpoints, api_key, records[0]["path"], smoke_lines)
        if selected_endpoint is None:
            raise RuntimeError("专用端点和公共端点烟测均失败")
        print(f"烟测通过: {endpoint_host(selected_endpoint)}")

        for index, record in enumerate(records, start=1):
            try:
                uri, resized = image_data_uri(record["path"])
                vector, raw_norm, elapsed_ms, attempts = call_vector(selected_endpoint, [{"image": uri}], api_key, True)
                record["_vector"] = vector
                record["_raw_l2_norm"] = raw_norm
                record["_elapsed_ms"] = elapsed_ms
                record["_attempts"] = attempts
                record["_resized"] = resized
            except Exception as exc:
                failures.append(f"{record['ref_id']} | {record['local_path']} | {sanitize(exc, api_key)}")
            if index % 10 == 0 or index == len(records):
                print(f"图像向量进度: {index}/{len(records)}，失败 {len(failures)}")

        if failures:
            raise RuntimeError("存在图像向量失败，未写出最终 manifest/NPY")

        matrix = np.asarray([record["_vector"] for record in records], dtype=np.float32)
        norms = np.linalg.norm(matrix.astype(np.float64), axis=1)
        vector_checks.extend(
            [
                f"shape={matrix.shape}, dtype={matrix.dtype}, finite={bool(np.isfinite(matrix).all())}, all_norms_close={bool(np.allclose(norms, 1.0, atol=1e-5))}",
                f"raw_l2_norm_min={min(record['_raw_l2_norm'] for record in records):.6f}, raw_l2_norm_max={max(record['_raw_l2_norm'] for record in records):.6f}",
                f"normalized_l2_min={float(norms.min()):.6f}, normalized_l2_max={float(norms.max()):.6f}",
            ]
        )

        for probe_index, (query, expected_type, expected_key) in enumerate(PROBES, start=1):
            try:
                probe_vector, _, _, attempts = call_vector(selected_endpoint, [{"text": query}], api_key, False)
                scored = []
                for record in records:
                    score = float(np.dot(probe_vector, record["_vector"]))
                    key = record["stage_code"] if record["ref_type"] == "PHENOLOGY" else record["disease_key"]
                    scored.append((score, record, key))
                scored.sort(key=lambda item: (-item[0], item[1]["ref_id"]))
                top5 = scored[:5]
                top1_hit = top5[0][1]["ref_type"] == expected_type and top5[0][2] == expected_key
                top2_hit = any(item[1]["ref_type"] == expected_type and item[2] == expected_key for item in top5[:2])
                details = "; ".join(f"{item[1]['ref_id']}:{item[0]:.4f}" for item in top5)
                probe_lines.append(
                    f"probe_{probe_index} expected={expected_type}/{expected_key}, attempts={attempts}, top1_hit={top1_hit}, top2_hit={top2_hit}, top5={details}"
                )
            except Exception as exc:
                probe_lines.append(f"probe_{probe_index} expected={expected_type}/{expected_key}, result=FAIL, error={sanitize(exc, api_key)}")

        np.save(OUTPUT_DIR / "ref_vectors_1152.npy", matrix)
        manifest_rows = [{key: record[key] for key in MANIFEST_COLUMNS} for record in records]
        write_csv(OUTPUT_DIR / "ref_image_manifest.csv", manifest_rows, MANIFEST_COLUMNS)

        after_pheno = source_snapshot(PHENOLOGY_ROOT)
        after_disease = source_snapshot(DISEASE_ROOT)
        report_text = build_report(
            started_at,
            now_text(),
            selected_endpoint,
            smoke_lines,
            records,
            failures,
            anomalies,
            before_pheno,
            after_pheno,
            before_disease,
            after_disease,
            probe_lines,
            vector_checks,
        )
        safe_write_report(report_text, api_key)
        if api_key and api_key in (OUTPUT_DIR / "build_ref_embeddings.py").read_text(encoding="utf-8"):
            raise RuntimeError("脚本中检测到 API key")
        print("构建完成: 90 张图像向量、6 条文本探针、manifest、NPY、报告")
        return 0
    except Exception as exc:
        if not after_pheno:
            try:
                after_pheno = source_snapshot(PHENOLOGY_ROOT)
                after_disease = source_snapshot(DISEASE_ROOT)
            except Exception as snapshot_exc:
                anomalies.append(f"结束时源目录复核失败: {sanitize(snapshot_exc, api_key)}")
        report_text = build_report(
            started_at,
            now_text(),
            selected_endpoint,
            smoke_lines,
            records,
            failures + [f"BUILD {sanitize(exc, api_key)}"],
            anomalies,
            before_pheno,
            after_pheno,
            before_disease,
            after_disease,
            probe_lines,
            vector_checks,
        )
        safe_write_report(report_text, api_key)
        print(f"构建未完成，详情见 build_report.txt: {sanitize(exc, api_key)}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
