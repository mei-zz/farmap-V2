# FarMap Local AI Assets

This directory is the controlled integration boundary for local AI assets.

- `models/vision/`: explicitly approved local vision weights.
- `models/embedding/`: explicitly approved local embedding weights.
- `metadata/`: external asset manifests; large or un-copied assets stay outside the repository.
- `configs/`: non-secret runtime configuration examples.

No model weights were downloaded in Milestone 2.1. No `.env`, API Key, credential, database password, SSH key, personal file, or browser data belongs here.

The current FarMap implementation reuses the existing project CLIP ONNX asset at runtime. Hugging Face cache assets are inventoried but not copied because their adapters are not yet wired into the Spring service.
