"""Contract-level smoke test for the local Agent Runtime.

The script uses a read-only context and approves the proposed write action only
after verifying that no TaskTool call happened before approval.
"""
import json
import sys
import time
import urllib.error
import urllib.request


BASE = "http://127.0.0.1:8080"


def request(method, path, body=None):
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(BASE + path, data=data, method=method, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as response:
        return json.loads(response.read().decode("utf-8"))


def main():
    payload = {"goal": "查看 A-12 地块上下文", "mode": "real", "context": {
        "farmId": "1", "fieldId": "A-12", "crop": "柑橘", "imageUrls": [],
        "weather": {"rainfall14d": 58, "summary": "持续降雨"}, "sourceModes": {"field": "LOCAL", "weather": "LOCAL"}
    }}
    created = request("POST", "/api/agent/runs", payload)
    run_id = created["runId"]
    run = None
    for _ in range(600):
        time.sleep(0.25)
        run = request("GET", "/api/agent/runs/" + run_id)
        if run["status"] in ("WAITING_FOR_APPROVAL", "COMPLETED", "FAILED"):
            break
    if run["status"] != "WAITING_FOR_APPROVAL":
        raise RuntimeError("unexpected status: " + str(run["status"]))
    before = [call for call in run.get("toolCalls", []) if call.get("toolName") == "task"]
    if before:
        raise RuntimeError("TaskTool executed before approval")
    action_id = run["actions"][0]["id"]
    approved = request("POST", "/api/agent/runs/%s/actions/%s/approve" % (run_id, action_id), {})
    after = [call for call in approved.get("toolCalls", []) if call.get("toolName") == "task"]
    if approved["status"] != "COMPLETED" or not after or not approved.get("output", {}).get("task", {}).get("taskId"):
        raise RuntimeError("approval did not create an Operations task")
    print(json.dumps({"status": "PASS", "runId": run_id, "beforeApprovalTaskCalls": len(before), "afterApprovalTaskCalls": len(after), "finalStatus": approved["status"]}))


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, urllib.error.URLError, KeyError) as error:
        print(json.dumps({"status": "FAIL", "error": str(error)}))
        sys.exit(1)
