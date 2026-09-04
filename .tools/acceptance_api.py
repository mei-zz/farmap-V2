#!/usr/bin/env python3
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:24080/api"


def call(path, method="GET", body=None, headers=None, timeout=60):
    payload = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    request_headers = {"Accept": "application/json"}
    if payload is not None:
        request_headers["Content-Type"] = "application/json"
    if headers:
        request_headers.update(headers)
    request = urllib.request.Request(BASE + path, data=payload, headers=request_headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
            return response.status, json.loads(raw)
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        try:
            data = json.loads(raw)
        except Exception:
            data = {"raw": raw[:300]}
        return exc.code, data


def ok_result(name, status, response, detail=""):
    passed = status == 200 and response.get("code") == 1
    print(f"{name}={'PASS' if passed else 'FAIL'} status={status}{detail}")
    if not passed:
        print(f"{name}_response={json.dumps(response, ensure_ascii=False)[:500]}")
    return passed


failures = []
test_username = os.getenv("FARMAP_TEST_USERNAME")
test_password = os.getenv("FARMAP_TEST_PASSWORD")
if not test_username or not test_password:
    print("login=SKIP FARMAP_TEST_USERNAME/FARMAP_TEST_PASSWORD not set")
    sys.exit(0)
status, login = call("/user/login", "POST", {"username": test_username, "password": test_password})
if not ok_result("login", status, login):
    sys.exit(1)
token = login.get("data", {}).get("token")
if not token:
    print("login_token=FAIL")
    sys.exit(1)
auth = {"Authorization": f"Bearer {token}"}

checks = [
    ("token_validate", "/user/validate-token", auth),
    ("farm_detail", "/user/get-farm?farmId=1", auth),
    ("farm_locations", "/map/farm/1/locations", {}),
    ("guidance", "/guidance/get?farmId=1&month=6", auth),
    ("weather_intro", "/weather/intro?farmId=1", auth),
    ("accumulated_temperature", "/weather/accumulated-temperature?farmType=default", auth),
    ("expert_pending", "/expert/pending-cases", auth),
    ("model_list", "/model/list?farmId=1", auth),
]

for name, path, headers in checks:
    status, response = call(path, headers=headers)
    data = response.get("data")
    if isinstance(data, list):
        detail = f" items={len(data)}"
    elif isinstance(data, dict):
        detail = f" fields={len(data)}"
    else:
        detail = ""
    if not ok_result(name, status, response, detail):
        failures.append(name)

status, users_response = call("/user/all", headers=auth)
users_ok = status == 200 and users_response.get("message") == "Success to get all users" and isinstance(users_response.get("users"), list)
print(f"user_list={'PASS' if users_ok else 'FAIL'} status={status} items={len(users_response.get('users', []))}")
if not users_ok:
    failures.append("user_list")

status, token_response = call("/monitor/get-accesstoken")
camera_token_ok = ok_result("camera_token", status, token_response)
if not camera_token_ok:
    failures.append("camera_token")
else:
    access_token = token_response.get("data", {}).get("accessToken")
    query = urllib.parse.urlencode({"accessToken": access_token, "deviceSerial": "GC9054260"})
    status, preview = call("/monitor/preview?" + query, timeout=90)
    preview_ok = ok_result("camera_preview", status, preview)
    if not preview_ok:
        failures.append("camera_preview")
    else:
        urls = []

        def visit(value):
            if isinstance(value, dict):
                for child in value.values():
                    visit(child)
            elif isinstance(value, list):
                for child in value:
                    visit(child)
            elif isinstance(value, str) and value.startswith(("http://", "https://", "ws://", "wss://")):
                urls.append(value)

        visit(preview)
        hosts = sorted({urllib.parse.urlparse(url).hostname for url in urls if urllib.parse.urlparse(url).hostname})
        print(f"camera_preview_url_hosts={','.join(hosts) if hosts else 'none'}")

print(f"api_acceptance={'PASS' if not failures else 'FAIL'} failures={','.join(failures) if failures else 'none'}")
sys.exit(1 if failures else 0)
