"""Local contract smoke test for the Milestone 4 product loop.

It exercises Agent -> approval -> Operations, Expert Review persistence and the
historical ingestion dry-run. It does not fabricate a historical case or call
deployment infrastructure.
"""
import json
import sys
import time
import urllib.request

BASE = "http://127.0.0.1:8080"


def request(method, path, body=None):
    payload = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(BASE + path, data=payload, method=method, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def main():
    context = {"farmId": "1", "fieldId": "A-12", "fieldName": "A-12 东侧", "crop": "柑橘", "imageUrls": [], "diagnosis": {"id": "a12-demo", "title": "叶片黄化", "confidence": 0.88}, "weather": {"summary": "持续降雨"}, "sourceModes": {"field": "LOCAL", "weather": "LOCAL"}, "page": "/overview"}
    created = request("POST", "/api/agent/runs", {"goal": "综合分析 A-12 地块叶片黄化原因并制定计划", "mode": "real", "context": context})
    run_id = created["runId"]
    for _ in range(600):
        time.sleep(0.25)
        run = request("GET", "/api/agent/runs/" + run_id)
        if run["status"] in ("WAITING_FOR_APPROVAL", "COMPLETED", "FAILED"):
            break
    if run["status"] != "WAITING_FOR_APPROVAL":
        raise RuntimeError("Agent did not reach approval gate: " + str(run.get("status")))
    if any(call.get("toolName") == "task" for call in run.get("toolCalls", [])):
        raise RuntimeError("TaskTool ran before approval")
    approved = request("POST", "/api/agent/runs/%s/actions/%s/approve" % (run_id, run["actions"][0]["id"]), {})
    task = approved.get("output", {}).get("task", {})
    if approved.get("status") != "COMPLETED" or not task.get("taskId"):
        raise RuntimeError("approval did not create an Operations task")
    operations = request("GET", "/api/operations/tasks").get("data", {}).get("items", [])
    if not any(item.get("agentRunId") == run_id for item in operations):
        raise RuntimeError("Operations backlink missing")
    review = request("POST", "/api/expert-review/a12-demo", {"decision": "CONFIRMED", "aiDiagnosis": context["diagnosis"], "expertDiagnosis": "确认根区积水风险", "evidence": [], "recommendations": []})
    if review.get("data", {}).get("status") != "CONFIRMED":
        raise RuntimeError("expert review state was not persisted")
    history = request("POST", "/api/historical-cases/ingest", [])
    if history.get("data", {}).get("status") != "READY":
        raise RuntimeError("historical dry-run is not ready")
    print(json.dumps({"status": "PASS", "runId": run_id, "taskId": task["taskId"], "expertStatus": review["data"]["status"], "historical": history["data"]["mode"]}, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(json.dumps({"status": "FAIL", "error": str(error)}, ensure_ascii=False))
        sys.exit(1)
