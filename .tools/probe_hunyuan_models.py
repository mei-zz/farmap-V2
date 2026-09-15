#!/usr/bin/env python3
import json
import urllib.error
import urllib.request

values = {}
with open("/home/sym/mei/deployment/.env", encoding="utf-8") as source:
    for line in source:
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key] = value

url = "https://api.hunyuan.cloud.tencent.com/v1/chat/completions"
models = ["hunyuan-turbos-latest", "hunyuan-a13b", "hunyuan-turbos", "hunyuan-vision-1.5-instruct"]
for model in models:
    payload = json.dumps({
        "model": model,
        "messages": [{"role": "user", "content": "Reply only with OK."}],
        "temperature": 0,
        "stream": False,
    }).encode("utf-8")
    request = urllib.request.Request(url, data=payload, method="POST", headers={
        "Authorization": "Bearer " + values["HUNYUAN_API_KEY"],
        "Content-Type": "application/json",
    })
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            result = json.loads(response.read().decode("utf-8"))
            choices = result.get("choices") or []
            passed = response.status == 200 and bool(choices)
            print(f"{model}={'PASS' if passed else 'FAIL'} http={response.status}")
    except urllib.error.HTTPError as exc:
        try:
            error = json.loads(exc.read().decode("utf-8")).get("error", {})
            print(f"{model}=FAIL http={exc.code} code={error.get('code')} type={error.get('type')}")
        except Exception:
            print(f"{model}=FAIL http={exc.code}")
    except Exception as exc:
        print(f"{model}=FAIL transport={type(exc).__name__}")
