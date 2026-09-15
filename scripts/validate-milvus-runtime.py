"""Read-only runtime audit. Never creates, loads, or modifies a collection."""
import json
import os
from pathlib import Path
from pymilvus import connections, utility, Collection

root = Path(__file__).resolve().parents[1]
host = os.getenv("MILVUS_HOST", "127.0.0.1")
port = os.getenv("MILVUS_PORT", "19530")
name = os.getenv("MILVUS_COLLECTION", "farmap_image_vectors_new")
report = {"endpoint": f"{host}:{port}", "collection": name}
try:
    connections.connect(alias="audit", host=host, port=port, timeout=8)
    names = utility.list_collections(using="audit", timeout=8)
    report.update(status="VERIFIED", collections=names, exists=name in names)
    if name in names:
        collection = Collection(name, using="audit")
        report.update(rows=collection.num_entities, schema=collection.schema.to_dict(),
                      indexes=[index.to_dict() for index in collection.indexes])
    else:
        report["rows"] = 0
    report["historical"] = "AVAILABLE" if report["rows"] > 0 else "HISTORICAL_VECTOR_DATA_EMPTY"
except Exception as error:
    report.update(status="BLOCKED", errorType=type(error).__name__)
finally:
    connections.disconnect("audit")
destination = root / ".runtime" / "milvus-validation.json"
destination.parent.mkdir(exist_ok=True)
destination.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(report, ensure_ascii=False))
