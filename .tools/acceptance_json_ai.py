#!/usr/bin/env python3
import json
import os
import sys
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:24080/api"


def post(path, body, headers=None, timeout=180):
    request_headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if headers:
        request_headers.update(headers)
    request = urllib.request.Request(
        BASE + path,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        method="POST",
        headers=request_headers,
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        return exc.code, json.loads(exc.read().decode("utf-8", errors="replace"))


test_username = os.getenv("FARMAP_TEST_USERNAME")
test_password = os.getenv("FARMAP_TEST_PASSWORD")
if not test_username or not test_password:
    print("json_ai_login=SKIP FARMAP_TEST_USERNAME/FARMAP_TEST_PASSWORD not set")
    sys.exit(0)
status, login = post("/user/login", {"username": test_username, "password": test_password})
token = login.get("data", {}).get("token")
if status != 200 or not token:
    print("json_ai_login=FAIL")
    sys.exit(1)

status, result = post(
    "/ai-json/modify",
    {
        "jsonData": '{"plant":"apple","status":"unknown","score":1}',
        "modificationText": "Change only status to healthy and score to 2.",
    },
    {"Authorization": f"Bearer {token}"},
    timeout=240,
)
data = result.get("data")
passed = status == 200 and result.get("code") == 1 and isinstance(data, dict)
print(f"json_ai_modify={'PASS' if passed else 'FAIL'} status={status}")
if isinstance(data, dict):
    print(f"json_ai_fields={','.join(sorted(data.keys()))}")
    print(f"json_ai_status_type={type(data.get('status')).__name__}")
if not passed:
    print(json.dumps(result, ensure_ascii=False)[:1000])
    sys.exit(1)
