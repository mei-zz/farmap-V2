"""Offline validation for discovered local embedding assets.

Only dimensions, finite-value checks, cosine self-similarity and latency are
printed. No model weights or embedding values are printed.
"""

from __future__ import annotations

import os
import time
from pathlib import Path

import torch
import torch.nn.functional as F
from transformers import AutoModel, AutoTokenizer


ROOT = Path(__file__).resolve().parents[1]
BGE_ROOT = Path(os.environ.get("FARMAP_BGE_MODEL_DIR", Path.home() / ".cache" / "huggingface" / "hub" / "models--BAAI--bge-small-zh-v1.5"))
SNAPSHOTS = sorted(BGE_ROOT.glob("snapshots/*"))
if not SNAPSHOTS:
    raise SystemExit("BGE_LOCAL_INCOMPLETE: cached snapshot not found")
MODEL_DIR = SNAPSHOTS[-1]


def embed(tokenizer, model, text: str) -> tuple[torch.Tensor, int]:
    started = time.perf_counter()
    encoded = tokenizer([text], padding=True, truncation=True, return_tensors="pt", max_length=512)
    with torch.inference_mode():
        output = model(**encoded).last_hidden_state
    mask = encoded["attention_mask"].unsqueeze(-1).expand(output.size()).float()
    pooled = (output * mask).sum(1) / mask.sum(1).clamp(min=1e-9)
    vector = F.normalize(pooled, p=2, dim=1)[0]
    return vector, round((time.perf_counter() - started) * 1000)


def main() -> int:
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    tokenizer = AutoTokenizer.from_pretrained(MODEL_DIR, local_files_only=True)
    model = AutoModel.from_pretrained(MODEL_DIR, local_files_only=True)
    model.eval()
    first, first_latency = embed(tokenizer, model, "柑橘果实膨大期叶片黄化")
    second, second_latency = embed(tokenizer, model, "柑橘果实膨大期叶片黄化")
    finite = bool(torch.isfinite(first).all() and torch.isfinite(second).all())
    cosine = float(torch.dot(first, second))
    print(f"BGE status={'VERIFIED' if finite and first.shape[0] == 512 else 'ERROR'} provider=local model=bge-small-zh dimension={first.shape[0]} pooling=mean normalized=true finite={finite} cosineSelf={cosine:.6f} latencyMs={first_latency},{second_latency}")
    return 0 if finite and first.shape[0] == 512 and abs(cosine - 1.0) < 1e-5 else 1


if __name__ == "__main__":
    raise SystemExit(main())
