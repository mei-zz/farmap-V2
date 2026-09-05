"""Safe Milvus bootstrap for FarMap historical cases.

Dry-run is the default. --execute may create the collection and index, but never
drops or mutates an existing collection. Existing schemas are validated before
the script exits.
"""
import argparse
import os
import sys


COLLECTION = "farmap_image_vectors_new"
DIMENSION = 512


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--host", default=os.getenv("MILVUS_HOST", "127.0.0.1"))
    parser.add_argument("--port", default=int(os.getenv("MILVUS_PORT", "19530")))
    args = parser.parse_args()
    try:
        from pymilvus import Collection, CollectionSchema, FieldSchema, DataType, connections, utility
    except ImportError:
        print("MILVUS_BOOTSTRAP_BLOCKED pymilvus is required", file=sys.stderr)
        return 2
    connections.connect(alias="default", host=args.host, port=args.port)
    exists = utility.has_collection(COLLECTION)
    if exists:
        collection = Collection(COLLECTION)
        vectors = [field for field in collection.schema.fields if field.name == "vector"]
        if not vectors or int(vectors[0].params.get("dim", 0)) != DIMENSION:
            raise SystemExit("VECTOR_DIMENSION_MISMATCH: existing collection vector dimension is not 512")
        print("HISTORICAL_COLLECTION_VERIFIED collection=%s dimension=%d mode=%s" % (COLLECTION, DIMENSION, "EXECUTE" if args.execute else "DRY_RUN"))
        return 0
    print("HISTORICAL_COLLECTION_PLAN collection=%s dimension=%d metric=L2 index=IVF_FLAT nlist=128 mode=%s" % (COLLECTION, DIMENSION, "EXECUTE" if args.execute else "DRY_RUN"))
    if not args.execute:
        print("HISTORICAL_VECTOR_DATA_EMPTY candidates=0")
        return 0
    fields = [FieldSchema(name="request_id", dtype=DataType.VARCHAR, is_primary=True, auto_id=False, max_length=128), FieldSchema(name="vector", dtype=DataType.FLOAT_VECTOR, dim=DIMENSION)]
    collection = Collection(COLLECTION, CollectionSchema(fields, description="Confirmed expert historical cases"))
    collection.create_index("vector", {"index_type": "IVF_FLAT", "metric_type": "L2", "params": {"nlist": 128}})
    collection.load()
    print("HISTORICAL_COLLECTION_CREATED collection=%s dimension=%d" % (COLLECTION, DIMENSION))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
