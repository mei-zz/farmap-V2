#!/usr/bin/env python3
import json
import mimetypes
import os
import sys
import urllib.error
import urllib.request
import uuid

BASE = "http://127.0.0.1:24080/api"
IMAGE_PATH = "/tmp/codex-apple-tree.jpg"


def json_request(path, method="GET", body=None, headers=None, timeout=60):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    request_headers = {"Accept": "application/json"}
    if data is not None:
        request_headers["Content-Type"] = "application/json"
    if headers:
        request_headers.update(headers)
    request = urllib.request.Request(BASE + path, data=data, headers=request_headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        try:
            return exc.code, json.loads(raw)
        except Exception:
            return exc.code, {"raw": raw[:500]}


with open(IMAGE_PATH, "rb") as source:
    image_bytes = source.read(2 * 1024 * 1024)
print(f"source_image=PASS bytes={len(image_bytes)}")

test_username = os.getenv("FARMAP_TEST_USERNAME")
test_password = os.getenv("FARMAP_TEST_PASSWORD")
if not test_username or not test_password:
    print("login=SKIP FARMAP_TEST_USERNAME/FARMAP_TEST_PASSWORD not set")
    sys.exit(0)
status, login = json_request("/user/login", "POST", {"username": test_username, "password": test_password})
token = login.get("data", {}).get("token")
if status != 200 or login.get("code") != 1 or not token:
    print(f"login=FAIL status={status}")
    sys.exit(1)
print("login=PASS")

boundary = "----FarmapAcceptance" + uuid.uuid4().hex
filename = os.path.basename(IMAGE_PATH)
multipart = (
    f"--{boundary}\r\n"
    f'Content-Disposition: form-data; name="files"; filename="{filename}"\r\n'
    f"Content-Type: {mimetypes.guess_type(filename)[0] or 'image/jpeg'}\r\n\r\n"
).encode("ascii") + image_bytes + f"\r\n--{boundary}--\r\n".encode("ascii")
upload_request = urllib.request.Request(
    BASE + "/ai-model/upload",
    data=multipart,
    method="POST",
    headers={
        "Authorization": f"Bearer {token}",
        "Content-Type": f"multipart/form-data; boundary={boundary}",
        "Accept": "application/json",
    },
)
try:
    with urllib.request.urlopen(upload_request, timeout=90) as response:
        upload_status = response.status
        upload = json.loads(response.read().decode("utf-8"))
except urllib.error.HTTPError as exc:
    upload_status = exc.code
    upload = json.loads(exc.read().decode("utf-8", errors="replace"))
urls = upload.get("data") or []
upload_ok = upload_status == 200 and upload.get("code") == 1 and len(urls) == 1
print(f"image_upload={'PASS' if upload_ok else 'FAIL'} status={upload_status} urls={len(urls)}")
if not upload_ok:
    print(json.dumps(upload, ensure_ascii=False)[:500])
    sys.exit(1)

status, analysis = json_request(
    "/ai-model/analyze",
    "POST",
    {"imageUrls": urls},
    {"Authorization": f"Bearer {token}"},
    timeout=360,
)
raw_result = analysis.get("data")
try:
    parsed = json.loads(raw_result) if isinstance(raw_result, str) else raw_result
except Exception:
    parsed = None
required = {"plant_validation", "analysis_result", "validation"}
top_keys = set(parsed.keys()) if isinstance(parsed, dict) else set()
contract_ok = status == 200 and analysis.get("code") == 1 and required.issubset(top_keys)
validation_ok = isinstance(parsed, dict) and isinstance(parsed.get("plant_validation"), dict) and isinstance(parsed.get("analysis_result"), dict) and isinstance(parsed.get("validation"), dict)
print(f"model_analyze={'PASS' if contract_ok and validation_ok else 'FAIL'} status={status}")
print(f"model_top_fields={','.join(sorted(top_keys)) if top_keys else 'none'}")
if isinstance(parsed, dict):
    print(f"model_analysis_field_count={len(parsed.get('analysis_result', {}))}")
    print(f"model_confidence_type={type(parsed.get('plant_validation', {}).get('confidence')).__name__}")
if not (contract_ok and validation_ok):
    print(f"model_response={json.dumps(analysis, ensure_ascii=False)[:1200]}")
    sys.exit(1)

print("ai_acceptance=PASS")
