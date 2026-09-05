from __future__ import annotations

import base64
import io
import os
import time
from contextlib import asynccontextmanager
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
import torch.nn.functional as functional
from fastapi import FastAPI, HTTPException
from PIL import Image
from transformers import AutoModel, AutoTokenizer

ROOT = Path(__file__).resolve().parents[1]
BGE_CACHE = Path(os.getenv("FARMAP_BGE_MODEL_DIR", Path.home() / ".cache/huggingface/hub/models--BAAI--bge-small-zh-v1.5"))
CLIP_PATH = Path(os.getenv("FARMAP_CLIP_MODEL_PATH", ROOT / "zhgy/zhgy/src/main/resources/models/clip-image-encoder.onnx"))
MAX_IMAGE_BYTES = int(os.getenv("LOCAL_AI_MAX_IMAGE_BYTES", str(12 * 1024 * 1024)))


class Runtime:
    tokenizer = None
    text_model = None
    clip = None
    text_ready = False
    image_ready = False
    text_error: str | None = None
    image_error: str | None = None

    def load(self) -> None:
        os.environ["HF_HUB_OFFLINE"] = "1"
        os.environ["TRANSFORMERS_OFFLINE"] = "1"
        try:
            snapshots = sorted((BGE_CACHE / "snapshots").glob("*"))
            model_path = snapshots[-1] if snapshots else BGE_CACHE
            self.tokenizer = AutoTokenizer.from_pretrained(model_path, local_files_only=True)
            self.text_model = AutoModel.from_pretrained(model_path, local_files_only=True)
            self.text_model.eval()
            vector = self.embed_texts(["柑橘叶片黄化"])[0]
            self.text_ready = bool(len(vector) == 512 and np.isfinite(vector).all())
        except Exception as error:
            self.text_error = type(error).__name__
        try:
            self.clip = ort.InferenceSession(str(CLIP_PATH), providers=["CPUExecutionProvider"])
            sample = Image.new("RGB", (224, 224), "green")
            vector = self.embed_image_bytes(_encode_png(sample))
            self.image_ready = bool(len(vector) == 512 and np.isfinite(vector).all())
        except Exception as error:
            self.image_error = type(error).__name__

    def embed_texts(self, texts: list[str]) -> np.ndarray:
        encoded = self.tokenizer(texts, padding=True, truncation=True, return_tensors="pt", max_length=512)
        with torch.inference_mode():
            output = self.text_model(**encoded).last_hidden_state[:, 0]
        return functional.normalize(output, p=2, dim=1).cpu().numpy()

    def embed_image_bytes(self, content: bytes) -> np.ndarray:
        image = Image.open(io.BytesIO(content)).convert("RGB").resize((224, 224), Image.Resampling.BICUBIC)
        values = np.asarray(image, dtype=np.float32) / 255.0
        mean = np.asarray([0.48145466, 0.4578275, 0.40821073], dtype=np.float32)
        std = np.asarray([0.26862954, 0.26130258, 0.27577711], dtype=np.float32)
        tensor = np.transpose((values - mean) / std, (2, 0, 1))[None, ...]
        vector = self.clip.run(["embedding"], {"image": tensor})[0][0]
        norm = np.linalg.norm(vector)
        return vector / norm if norm else vector


runtime = Runtime()


def _encode_png(image: Image.Image) -> bytes:
    target = io.BytesIO()
    image.save(target, format="PNG")
    return target.getvalue()


def _decode_image(value: str) -> bytes:
    payload = value.split(",", 1)[1] if value.startswith("data:image/") and "," in value else value
    try:
        content = base64.b64decode(payload, validate=True)
    except Exception as error:
        raise HTTPException(400, "image must be base64 or a data URI") from error
    if not content or len(content) > MAX_IMAGE_BYTES:
        raise HTTPException(413, "image payload is empty or too large")
    return content


@asynccontextmanager
async def lifespan(_: FastAPI):
    runtime.load()
    yield


app = FastAPI(title="FarMap Local AI", version="1.0", lifespan=lifespan)


@app.get("/health")
def health():
    ok = runtime.text_ready and runtime.image_ready
    return {
        "status": "ok" if ok else "degraded",
        "textEmbedding": {"ready": runtime.text_ready, "model": "BAAI/bge-small-zh-v1.5", "dimension": 512 if runtime.text_ready else 0, "errorType": runtime.text_error},
        "imageEmbedding": {"ready": runtime.image_ready, "model": "clip-image-encoder.onnx", "dimension": 512 if runtime.image_ready else 0, "errorType": runtime.image_error},
    }


@app.post("/embedding/text")
def embed_text(request: dict):
    if not runtime.text_ready:
        raise HTTPException(503, "text embedding is unavailable")
    texts = request.get("texts")
    if not isinstance(texts, list) or not texts or not all(isinstance(value, str) and value.strip() for value in texts):
        raise HTTPException(400, "texts must be a non-empty string array")
    started = time.perf_counter()
    vectors = runtime.embed_texts(texts)
    return {"model": "BAAI/bge-small-zh-v1.5", "dimension": 512, "normalized": True,
            "embeddings": vectors.tolist(), "latencyMs": round((time.perf_counter() - started) * 1000)}


@app.post("/embedding/image")
def embed_image(request: dict):
    if not runtime.image_ready:
        raise HTTPException(503, "image embedding is unavailable")
    image = request.get("image")
    if not isinstance(image, str):
        raise HTTPException(400, "image must be a string")
    started = time.perf_counter()
    vector = runtime.embed_image_bytes(_decode_image(image))
    return {"model": "clip-image-encoder.onnx", "dimension": 512, "normalized": True,
            "embedding": vector.tolist(), "latencyMs": round((time.perf_counter() - started) * 1000)}
