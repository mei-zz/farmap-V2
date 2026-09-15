"""Manual, redacted validation for the Bailian OpenAI-compatible gateway.

This script intentionally prints metadata only. It does not print API keys,
provider response bodies, reasoning content, or image data.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


MODELS = (
    "qwen3.6-plus",
    "qwen3-vl-32b-thinking",
    "qwen3-vl-235b-a22b-thinking",
)
ROOT = Path(__file__).resolve().parents[1]
IMAGE_PATH = ROOT / "farmap-frontend-dev" / "src" / "assets" / "demo" / "diagnosis-camera-chlorosis.png"


def load_dotenv() -> None:
    env_file = ROOT / ".env"
    if not env_file.exists():
        return
    for raw_line in env_file.read_text(encoding="utf-8-sig").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, value = line.split("=", 1)
        name = name.strip()
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        # The current process takes precedence over the local file.
        os.environ.setdefault(name, value)


def first_value(*names: str) -> str:
    for name in names:
        value = os.environ.get(name, "").strip()
        if value:
            return value
    return ""


def normalize_chat_url(base_url: str) -> str:
    normalized = base_url.rstrip("/")
    return normalized if normalized.endswith("/chat/completions") else normalized + "/chat/completions"


def classify_http(status: int) -> str:
    if status == 401:
        return "AUTH_FAILED"
    if status == 403:
        return "PERMISSION_DENIED"
    if status == 404:
        return "MODEL_NOT_FOUND"
    if status == 429:
        return "UNAVAILABLE"
    if status >= 500:
        return "UNAVAILABLE"
    return "ERROR"


def strip_private_reasoning(text: str) -> str:
    return re.sub(r"(?s)<think>.*?</think>", "", text).strip()


def extract_content(payload: dict[str, Any]) -> str:
    choices = payload.get("choices") or []
    if not choices:
        return ""
    message = choices[0].get("message") or {}
    content = message.get("content", "")
    if isinstance(content, str):
        return strip_private_reasoning(content)
    if isinstance(content, list):
        return strip_private_reasoning("".join(item.get("text", "") for item in content if isinstance(item, dict)))
    return ""


def usage_summary(payload: dict[str, Any]) -> dict[str, Any]:
    usage = payload.get("usage") or {}
    details = usage.get("completion_tokens_details") or {}
    return {
        "inputTokens": usage.get("prompt_tokens"),
        "outputTokens": usage.get("completion_tokens"),
        "thinkingTokens": usage.get("thinking_tokens", details.get("reasoning_tokens")),
        "totalTokens": usage.get("total_tokens"),
    }


def call_model(api_key: str, base_url: str, model: str, messages: list[dict[str, Any]], structured: bool = False) -> dict[str, Any]:
    body: dict[str, Any] = {
        "model": model,
        "messages": messages,
        "temperature": 0.1,
        "max_tokens": 1600,
    }
    if "thinking" in model:
        body["enable_thinking"] = True
    if structured:
        body["response_format"] = {"type": "json_object"}

    request = urllib.request.Request(
        base_url,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"},
        method="POST",
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=130) as response:
            raw = response.read()
            payload = json.loads(raw.decode("utf-8"))
            latency_ms = round((time.perf_counter() - started) * 1000)
            choices = payload.get("choices") or []
            content = extract_content(payload)
            return {
                "status": "VERIFIED" if choices and content else "ERROR",
                "http": response.status,
                "latencyMs": latency_ms,
                "model": payload.get("model", model),
                "finishReason": choices[0].get("finish_reason") if choices else None,
                "usage": usage_summary(payload),
                "content": content,
            }
    except urllib.error.HTTPError as error:
        return {
            "status": classify_http(error.code),
            "http": error.code,
            "latencyMs": round((time.perf_counter() - started) * 1000),
            "model": model,
            "errorType": classify_http(error.code),
        }
    except TimeoutError:
        return {"status": "NETWORK_ERROR", "latencyMs": round((time.perf_counter() - started) * 1000), "model": model, "errorType": "TIMEOUT"}
    except urllib.error.URLError:
        return {"status": "NETWORK_ERROR", "latencyMs": round((time.perf_counter() - started) * 1000), "model": model, "errorType": "NETWORK_ERROR"}
    except (json.JSONDecodeError, UnicodeDecodeError, KeyError, TypeError):
        return {"status": "ERROR", "latencyMs": round((time.perf_counter() - started) * 1000), "model": model, "errorType": "INVALID_RESPONSE"}


def json_from_content(content: str) -> dict[str, Any] | None:
    cleaned = content.strip()
    cleaned = re.sub(r"^```(?:json)?\s*|\s*```$", "", cleaned, flags=re.IGNORECASE | re.DOTALL).strip()
    try:
        parsed = json.loads(cleaned)
        return parsed if isinstance(parsed, dict) else None
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", cleaned, flags=re.DOTALL)
        if not match:
            return None
        try:
            parsed = json.loads(match.group(0))
            return parsed if isinstance(parsed, dict) else None
        except json.JSONDecodeError:
            return None


def evidence_check(payload: dict[str, Any] | None, evidence_ids: set[str]) -> dict[str, Any]:
    if not payload:
        return {"structured": False, "claimCount": 0, "invalidEvidenceIds": []}
    invalid: list[str] = []
    claims = payload.get("claims") or []
    for claim in claims:
        for evidence_id in claim.get("evidence_ids", []) if isinstance(claim, dict) else []:
            if evidence_id not in evidence_ids:
                invalid.append(str(evidence_id))
    required = {"diagnosis", "riskLevel", "confidence", "alternatives", "claims", "recommendations"}
    return {"structured": required.issubset(payload), "claimCount": len(claims), "invalidEvidenceIds": sorted(set(invalid))}


def print_result(label: str, result: dict[str, Any], include_content_check: bool = False, evidence_ids: set[str] | None = None) -> None:
    redacted = {key: value for key, value in result.items() if key != "content"}
    if include_content_check:
        parsed = json_from_content(result.get("content", ""))
        redacted["evidenceValidation"] = evidence_check(parsed, evidence_ids or set())
        if parsed:
            redacted["responseKeys"] = sorted(parsed.keys())
    print(f"{label}: {json.dumps(redacted, ensure_ascii=False, sort_keys=True)}")


def probe_all(api_key: str, base_url: str) -> dict[str, dict[str, Any]]:
    statuses: dict[str, dict[str, Any]] = {}
    messages = [{"role": "user", "content": "Return only {\"ok\":true}."}]
    for model in MODELS:
        result = call_model(api_key, base_url, model, messages, structured=False)
        statuses[model] = result
        print_result(f"availability.{model}", result)
    return statuses


def multimodal_message(prompt: str) -> list[dict[str, Any]]:
    image_data = base64.b64encode(IMAGE_PATH.read_bytes()).decode("ascii")
    return [
        {
            "role": "user",
            "content": [
                {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{image_data}"}},
                {"type": "text", "text": prompt},
            ],
        }
    ]


def run_tests(api_key: str, base_url: str, statuses: dict[str, dict[str, Any]]) -> None:
    evidence_ids = {"camera_1", "weather_14d", "knowledge_1"}
    if statuses["qwen3.6-plus"].get("status") == "VERIFIED":
        text_result = call_model(
            api_key,
            base_url,
            "qwen3.6-plus",
            [{"role": "user", "content": "用一句话回答：柑橘果实膨大期为什么要关注连续降雨？"}],
        )
        print_result("qwen36.text", text_result)

        grounded_prompt = """你是农业诊断系统。只输出 JSON，不输出思考过程。基于以下证据诊断 A-12 柑橘果实膨大期叶片黄化：
Field Context: A-12, 柑橘, 2.4 ha, 果实膨大期
Evidence: camera_1=叶片出现黄化；weather_14d=14天累计降雨58mm；knowledge_1=柑橘栽培技术规程§5.3建议关注根区排水。
JSON keys must be diagnosis, riskLevel, confidence, alternatives, claims, recommendations. Every claims[].evidence_ids value must be one of camera_1, weather_14d, knowledge_1."""
        grounded = call_model(
            api_key,
            base_url,
            "qwen3.6-plus",
            [{"role": "user", "content": grounded_prompt}],
            structured=True,
        )
        print_result("qwen36.grounded", grounded, include_content_check=True, evidence_ids=evidence_ids)
    else:
        print("qwen36.tests: SKIPPED provider not VERIFIED")

    if statuses["qwen3-vl-32b-thinking"].get("status") == "VERIFIED":
        vl_prompt = """你是农业多模态诊断系统。只输出 JSON，不输出思考过程或 reasoning_content。分析这张叶片图片，并结合上下文：A-12，柑橘，2.4 ha，果实膨大期；近14日降雨58mm；知识证据为柑橘栽培技术规程§5.3。返回 keys：visual_findings, diagnosis, riskLevel, confidence, alternatives, claims, recommendations。claims[].evidence_ids 只能引用 camera_1、weather_14d、knowledge_1。"""
        vl_result = call_model(
            api_key,
            base_url,
            "qwen3-vl-32b-thinking",
            multimodal_message(vl_prompt),
            structured=True,
        )
        print_result("vl32.a12.multimodal", vl_result, include_content_check=True, evidence_ids=evidence_ids)
    else:
        print("vl32.a12.multimodal: SKIPPED provider not VERIFIED")

    if statuses["qwen3-vl-235b-a22b-thinking"].get("status") == "VERIFIED":
        hard_prompt = """只输出 JSON，不输出思考过程。对 A-12 柑橘叶片黄化执行一次 hard diagnosis。证据存在冲突：camera_1 支持黄化，weather_14d 支持持续降雨，knowledge_1 指向根区排水，historical_1 指向缺镁。返回 diagnosis, riskLevel, confidence, alternatives, claims, recommendations；claims 只能引用 camera_1、weather_14d、knowledge_1、historical_1。"""
        hard_result = call_model(
            api_key,
            base_url,
            "qwen3-vl-235b-a22b-thinking",
            multimodal_message(hard_prompt),
            structured=True,
        )
        print_result("vl235.escalation.fixture", hard_result, include_content_check=True, evidence_ids=evidence_ids | {"historical_1"})
    else:
        print("vl235.escalation.fixture: SKIPPED provider not VERIFIED")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--probe-only", action="store_true")
    parser.add_argument("--tests-only", action="store_true", help="Reuse a just-completed verified probe; does not call availability again")
    parser.add_argument("--confirmed-models", default="", help="Comma-separated models confirmed VERIFIED by a previous probe")
    args = parser.parse_args()
    load_dotenv()
    api_key = first_value("BAILIAN_API_KEY", "DASHSCOPE_API_KEY", "APIKEY")
    base_url = first_value(
        "BAILIAN_API_BASE_URL",
        "BAILIAN_BASE_URL",
        "DASHSCOPE_BASE_URL",
        "OPENAI_COMPATIBLE_BASE_URL",
        "OPENAI_BASE_URL",
        "BASEURL",
    ) or "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
    base_url = normalize_chat_url(base_url)
    print(f"apiKey={'SET' if api_key else 'NOT SET'} baseUrl={'SET' if base_url else 'NOT SET'}")
    if not api_key:
        print("runtime: BLOCKED missing API key")
        return 2
    if args.tests_only:
        confirmed = {item.strip() for item in args.confirmed_models.split(",") if item.strip()}
        if set(MODELS) - confirmed:
            print("tests: BLOCKED --tests-only requires all three models in --confirmed-models")
            return 2
        statuses = {model: {"status": "VERIFIED"} for model in MODELS}
        print("availability: REUSED_PREVIOUS_VERIFIED_RESULT")
    else:
        statuses = probe_all(api_key, base_url)
    if not args.probe_only or args.tests_only:
        run_tests(api_key, base_url, statuses)
    return 0


if __name__ == "__main__":
    sys.exit(main())
