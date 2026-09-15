import base64
import json
from pathlib import Path
import httpx

root = Path(__file__).resolve().parents[1]
url = "http://127.0.0.1:8001"
health = httpx.get(url + "/health", timeout=10).json()
text = httpx.post(url + "/embedding/text", json={"texts": ["柑橘叶片黄化", "持续降雨导致根区缺氧"]}, timeout=30).json()
image_path = root / "farmap-frontend-dev/src/assets/demo/diagnosis-camera-chlorosis.png"
image = "data:image/png;base64," + base64.b64encode(image_path.read_bytes()).decode()
visual = httpx.post(url + "/embedding/image", json={"image": image}, timeout=30).json()
assert health["status"] == "ok"
assert text["dimension"] == 512 and len(text["embeddings"]) == 2 and all(len(v) == 512 for v in text["embeddings"])
assert visual["dimension"] == 512 and len(visual["embedding"]) == 512
print(json.dumps({"status": "PASS", "bgeDimension": 512, "bgeLatencyMs": text["latencyMs"],
                  "clipDimension": 512, "clipLatencyMs": visual["latencyMs"]}))
