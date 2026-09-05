# FarMap Local AI Asset Inventory

扫描日期：2026-09-05  
扫描范围：可访问的 `C:\`、`D:\`、`E:\` 文件系统，以及 FarMap 相关目录。  
扫描原则：仅读取路径、文件名、文件大小、包版本和服务元数据；未读取 API Key、私人文档正文、浏览器数据、聊天记录、邮件、密码数据库、SSH 私钥或个人照片内容。

## Summary

| Asset | Type | Original Path / Endpoint | Framework | Size / Status | FarMap Use | Reuse Decision |
| --- | --- | --- | --- | --- | --- | --- |
| CLIP image encoder | Visual embedding | `D:\前后端项目\farmap\zhgy\zhgy\src\main\resources\models\clip-image-encoder.onnx` | ONNX Runtime / Java | 335.5 MB / available | Historical Case Retriever、相似图像检索 | REUSE（已在项目内） |
| BAAI bge-small-zh-v1.5 | Text embedding candidate | `C:\Users\Administrator\.cache\huggingface\hub\models--BAAI--bge-small-zh-v1.5` | Hugging Face cache / `llm` | 91.4 MB 权重 / offline probe dimension 512 | 中文农业知识向量化 | DEFER（已在 `llm` 离线加载；缓存缺少完整 SentenceTransformer 配置，探测采用通用 mean pooling，尚非生产 adapter） |
| facebook/dinov2-small | Vision embedding candidate | `C:\Users\Administrator\.cache\huggingface\hub\models--facebook--dinov2-small` | Hugging Face cache | 84.2 MB 权重 / available | 视觉相似度实验 | DEFER（当前服务仍以项目 CLIP 为主） |
| bert-base-chinese | Text encoder candidate | `C:\Users\Administrator\.cache\huggingface\hub\models--bert-base-chinese` | Hugging Face cache | 392.5 MB 权重 / available | 中文文本基线 | DEFER（避免重复复制） |
| Anaconda `llm` | Python runtime | `D:\anaconda\envs\llm` | Conda / Python 3.10.15 | available | 本地 AI / Embedding 运行环境 | REUSE（按用户要求使用） |
| Ollama | Local model runtime | `C:\Users\Administrator\AppData\Local\Programs\Ollama` | Ollama 0.15.4 | installed / no model list returned | Local LLM fallback candidate | DEFER |
| Milvus | Vector database | configured `milvus:19530` | Milvus Java SDK 2.4.0 | endpoint not reachable from local probe | `farmap_image_vectors_new` | REUSE（不改集合） |

## Model File Metadata

| Model / file | Path | Size | Metadata-only finding |
| --- | --- | ---: | --- |
| `clip-image-encoder.onnx` | `D:\前后端项目\farmap\zhgy\zhgy\src\main\resources\models\clip-image-encoder.onnx` | 335.5 MB | Existing Java CLIP image encoder; legacy code declares 512-dim vector |
| `model.safetensors` | `C:\Users\Administrator\.cache\huggingface\hub\models--BAAI--bge-small-zh-v1.5\snapshots\7999e1d3359715c523056ef9478215996d62a620` | 91.4 MB | BGE-small-zh cache snapshot |
| `model.safetensors` | `C:\Users\Administrator\.cache\huggingface\hub\models--facebook--dinov2-small\snapshots\ed25f3a31f01632728cabb09d1542f84ab7b0056` | 84.2 MB | DINOv2-small cache snapshot |
| `model.safetensors` | `C:\Users\Administrator\.cache\huggingface\hub\models--bert-base-chinese\snapshots\8f23c25b06e129b6c986331a13d8d025a92cf0ea` | 392.5 MB | BERT Chinese cache snapshot |

扫描到的其他 `.onnx` 文件均为 ONNX Runtime 自带的测试模型或项目构建输出，没有复制到 FarMap。

## Python Environment

用户指定运行环境：`conda activate llm`。当前 Codex PowerShell 未初始化 Conda hook，因此使用该环境的等价解释器 `D:\anaconda\envs\llm\python.exe` 进行核对与运行。

`llm` 环境当前可见的目标包：

- `torch==2.5.1`
- `torchvision==0.20.1`
- `transformers==4.57.3`
- `sentence-transformers==3.3.1`
- `modelscope==1.12.0`
- `pymilvus==2.6.5`
- `chromadb==1.3.5`
- `qdrant-client==1.17.0`
- `onnxruntime==1.20.1`
- `numpy==1.26.4`
- `Pillow==11.3.0`
- `scikit-learn==1.5.2`

未发现 `faiss`、`ultralytics` 或 OpenCLIP 包。没有升级或重装环境。

## Vector Infrastructure

- Milvus 配置：`milvus:19530`，由现有 Java SDK 连接。
- 现有集合名：`farmap_image_vectors_new`。
- 已知 schema：`request_id` 主键 + `vector` FloatVector。
- 向量维度：512。
- 已知索引：`IVF_FLAT`，距离：`L2`，`nlist=128`。
- 本机 `localhost:19530` 探测失败；Docker daemon 当前不可用。
- `llm` 环境虽有 `pymilvus`、`chromadb`、`qdrant-client`，但本轮没有启动或改写任何向量服务。
- 未执行 collection 查询、删除、drop、重建或索引改动。

## Bailian / Secrets

- 当前 shell 中 `DASHSCOPE_API_KEY` 和兼容的 `BAILIAN_API_KEY` 均为未配置状态；只检查了 SET/UNSET，不读取任何 Key 内容。
- 未把本地 Key 写入代码、Demo Data、Inventory 或 Git。
- 三个 Qwen 模型的真实可用性必须在配置 Key 后通过 availability probe 验证；本次不能把未配置 Key 误报成 available。

## Copy Decision

本轮没有复制 Hugging Face cache 模型：项目已有 CLIP ONNX，BGE/DINO/BERT 当前没有与 FarMap 服务匹配的生产运行适配器，复制会产生重复副本。BGE 已在 `llm` 环境完成离线加载探测，但探测使用通用 mean pooling，仅作为候选验证。外部资产路径保留在本文件，后续运行时可通过配置引用。
