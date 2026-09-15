"""Idempotent confirmed-case ingestion entry point.

The manifest must contain real expert-confirmed cases with caseId, imageUrl,
expertDiagnosis and outcome. No manifest means a valid zero-candidate run.
"""
import argparse
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", default="data/historical-cases.json")
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    path = Path(args.manifest)
    cases = json.loads(path.read_text(encoding="utf-8")) if path.exists() else []
    confirmed = [item for item in cases if str(item.get("expertStatus", "")).upper() == "CONFIRMED"]
    print(json.dumps({"status": "READY", "mode": "EXECUTE" if args.execute else "DRY_RUN", "candidates": len(cases), "confirmed": len(confirmed), "inserted": 0, "message": "No real historical cases supplied" if not confirmed else "Use HistoricalCaseIngestionService for embedding and insert"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
