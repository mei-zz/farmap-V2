

# 整体流程

1.用户上传图片 → 调用hunyuan-turbo-vision生成初始JSON → 存储到「业务数据库」（待修正池）   

​		1.上传作物图片，1-5；前端给出拍摄标准的建议；上传的拍照的经纬度；内部农场的id，外部使用者的id；把调用对话接口写在其他docker中；

2.专家通过前端界面获取待修正数据 → 提交修改后的JSON → 「业务数据库」记录修改历史  

3.当修改次数/专家共识达到阈值 → 提取图片特征 + 最终JSON → 存入「外部知识库」

#4.新用户上传相似图片 → 检索知识库中相似案例的专家JSON → 结合新模型结果生成增强回答

4.训练并行模型（新知识库的数据），外部模型和大模型一模一样的输入输出。并行回答，选择返回



1.1限制上传图片，必须是同一个物种（提示词，api），【拍摄不符合要求，返回结果，但不存入数据库】

模型返回的json，检索知识库!!，把综合建议替换为知识库中专业的建议，返回结果



# 详细过程

### 一、数据流转闭环（完整流程设计）

整个系统的核心是让 “用户请求→模型生成→专家修正→知识库沉淀→RAG 增强新请求” 形成闭环，具体步骤如下：

```plaintext
用户上传图片 → 调用hunyuan-turbo-vision生成初始JSON → 存储到「业务数据库」（待修正池）
   ↓
专家通过前端界面获取待修正数据 → 提交修改后的JSON → 「业务数据库」记录修改历史
   ↓
当修改次数/专家共识达到阈值 → 提取图片特征 + 最终JSON → 存入「外部知识库」
   ↓
新用户上传相似图片 → 检索知识库中相似案例的专家JSON → 结合新模型结果生成增强回答
```

### 二、存储架构设计（分库分表，职责明确）

需要两个核心数据库：**业务数据库**（记录全量过程数据）和**外部知识库**（存储权威沉淀数据）。

#### 1. 业务数据库

用于存储用户原始请求、模型初始结果、专家修改历史等 “过程数据”，核心表设计：

| 表名             | 核心字段                                                     | 作用                                 |
| ---------------- | ------------------------------------------------------------ | ------------------------------------ |
| user_requests    | request_id（主键）、user_id、image_urls（图片存储路径）、upload_time | 记录用户上传的图片及基本信息         |
| initial_results  | result_id（主键）、request_id（外键）、json_data（初始模型输出）、generate_time | 存储模型第一次生成的 JSON 结果       |
| expert_revisions | revision_id（主键）、request_id（外键）、expert_id、revised_json（修改后 JSON）、revision_time、is_agree（是否同意前序修改） | 记录每个专家的修改内容，支持追踪历史 |

**关键逻辑**：通过`request_id`关联三张表，可追溯某一用户请求的 “原始图片→初始结果→所有专家修改记录”。

#### 2. 外部知识库（混合存储：向量库 + 文档库）

用于存储 “权威沉淀数据”，供 RAG 检索使用，需同时存储 “可检索的特征” 和 “权威内容”：

| 存储类型   | 工具推荐     | 存储内容                                                     | 作用                                   |
| ---------- | ------------ | ------------------------------------------------------------ | -------------------------------------- |
| 向量数据库 | Milvus/FAISS | image_embedding（图片特征向量）、request_id（关联业务库）    | 用于快速检索相似图片对应的权威案例     |
| 文档数据库 | MongoDB      | request_id（外键）、final_json（专家共识 JSON）、metadata（元数据：树种、病虫害标签等） | 存储专家确认的最终 JSON 和辅助检索标签 |

**关键设计**：

- 图片不直接存知识库，而是通过预训练模型（如 CLIP）提取`image_embedding`（512 维向量），作为相似检索的 “钥匙”；
- `final_json`严格保留你提供的 JSON 结构，确保字段一致性（方便后续解析和对比）；
- `metadata`包含 “树种”“生长阶段”“主要病虫害” 等核心标签（如`["梨树", "幼果期", "黑星病"]`），辅助检索时快速过滤无关案例。

### 三、专家协同修正机制（如何确定 “权威数据”）

核心是通过 “多专家修改 + 阈值判断” 生成 “专家共识 JSON”，具体规则如下：

#### 1. 专家操作流程

- **获取待修正列表**：专家通过接口（如`/api/expert/pending`）获取业务库中 “未达成共识” 的案例（含图片和初始 JSON）；
- **提交修改**：专家在前端界面直接编辑 JSON 字段（如修正 “病虫害诊断” 中的病害类型），通过接口（如`/api/expert/revise`）提交，业务库`expert_revisions`表新增一条记录；
- **查看历史**：专家可通过接口（如`/api/expert/history?request_id=xxx`）查看该案例的所有前序修改，辅助判断。

#### 2. 共识阈值规则（避免单一专家主观偏差）

设置双重阈值，满足其一即可将案例纳入知识库：

- **修改次数阈值**：同一案例被≥3 位专家修改（数量保证）；
- **共识度阈值**：≥2 位专家对核心字段（如 “树种识别”“病虫害诊断”）的修改完全一致（质量保证）。

**示例**：

- 案例 A 被 3 位专家修改，其中 2 位专家将 “病虫害诊断” 统一为 “梨黑星病”（排除褐斑病），则判定达成共识；
- 案例 B 被 5 位专家修改，但 “树种识别” 仍有 “梨树”“苹果树” 两种意见，则需标记为 “待进一步审核”，不入库。

#### 3. 最终 JSON 生成逻辑

当达到阈值后，系统自动生成`final_json`：

- 核心字段（树种、病虫害等）取 “专家共识值”；
- 非核心字段（如 “拍摄建议”）取 “修改次数最多的值”；
- 若存在未达成共识的非核心字段，保留 “出现次数最多的前 2 个选项”（供 RAG 时参考）。

### 四、RAG 检索增强实现（核心功能落地）

![image-20250821122519751](C:\Users\Administrator\AppData\Roaming\Typora\typora-user-images\image-20250821122519751.png)

当新用户上传图片时，通过 “相似检索 + 知识融合” 生成更权威的回答，具体步骤：

#### 1. 新请求处理流程

```plaintext
用户上传新图片 → 步骤1：提取图片特征 → 步骤2：检索相似权威案例 → 步骤3：融合知识生成回答
```

#### 2. 关键步骤实现

##### 步骤 1：提取图片特征

用与知识库一致的预训练模型（推荐 CLIP，支持跨模态检索）提取新图片的特征向量：

```python
# 示例代码（Python）：用CLIP提取图片特征
import torch
from transformers import CLIPModel, CLIPProcessor

model = CLIPModel.from_pretrained("openai/clip-vit-base-patch32")
processor = CLIPProcessor.from_pretrained("openai/clip-vit-base-patch32")

def get_image_embedding(image_path):
    image = Image.open(image_path).convert("RGB")
    inputs = processor(images=image, return_tensors="pt")
    with torch.no_grad():
        embedding = model.get_image_features(**inputs).squeeze()  # 输出512维向量
    return embedding.numpy()
```

##### 步骤 2：检索相似权威案例

- 在向量数据库中，计算新图片特征与库中`image_embedding`的余弦相似度，取 Top3 最相似案例；
- 结合`metadata`过滤（如新图片被初始模型判断为 “梨树”，则只检索知识库中 “树种 = 梨树” 的案例），提升精准度；
- 从文档数据库中获取这 3 个案例的`final_json`（专家共识数据）。

##### 步骤 3：融合知识生成回答

将 “新图片 + 初始模型输出 + 检索到的专家 JSON” 作为输入，再次调用 hunyuan-turbo-vision，通过提示词引导模型优先采用专家知识：

```markdown
提示词示例：
"基于用户上传的图片，你已生成初始诊断结果。现在提供3个相似案例的专家验证结论：[专家JSON1]、[专家JSON2]、[专家JSON3]。
请遵循以下规则优化结果：
1. 若你的初始结果与专家结论冲突（如病虫害类型、施肥建议），以专家结论为准；
2. 若专家结论之间有差异，优先选择出现次数更多的结论；
3. 保留合理的新增信息（如专家未提及的细节），但需标注"参考初始判断"。
最终输出格式保持原JSON结构。"
```

### 五、接口设计（核心 API 清单）

| 接口用途               | 示例路径                | 输入参数                             | 输出参数                                     |
| ---------------------- | ----------------------- | ------------------------------------ | -------------------------------------------- |
| 用户上传图片           | POST /api/user/upload   | images（多图文件）、user_id          | request_id、initial_json（初始结果）         |
| 专家获取待修正列表     | GET /api/expert/pending | expert_id（用于过滤已处理案例）      | 列表：{request_id, image_urls, initial_json} |
| 专家提交修改           | POST /api/expert/revise | request_id、revised_json、expert_id  | 修正成功 / 失败、当前修改次数                |
| 知识库检索（内部用）   | POST /api/rag/retrieve  | image_embedding、metadata_filters    | 相似案例列表：{final_json, similarity_score} |
| 生成增强回答（内部用） | POST /api/rag/enhance   | initial_json、retrieved_expert_jsons | enhanced_json（最终回答）                    |

# 查看特定容器日志

腾讯云上创建的向量数据库和文本数据库的docker容器

docker logs milvus-etcd
docker logs milvus-minio
docker logs milvus-standalone
docker logs mongodb

```dockerfile
# 创建网络
sudo docker network create milvus

# 创建数据卷目录
mkdir -p volumes/{etcd,minio,milvus,mongo}

# 启动etcd
sudo docker run -d \
  --name milvus-etcd \
  --network milvus \  # 加入 milvus 网络
  -p 2379:2379 \
  -v $PWD/volumes/etcd:/etcd \
  -e ETCD_AUTO_COMPACTION_MODE=revision \
  -e ETCD_AUTO_COMPACTION_RETENTION=1000 \
  -e ETCD_QUOTA_BACKEND_BYTES=4294967296 \
  -e ETCD_SNAPSHOT_COUNT=50000 \
  quay.io/coreos/etcd:v3.5.0 \
  # 关键修正：广告地址设为容器名（milvus-etcd），让其他容器能通过此地址访问
  etcd -advertise-client-urls=http://milvus-etcd:2379 -listen-client-urls http://0.0.0.0:2379 --data-dir /etcd

# 启动minio
sudo docker run -d \
  --name milvus-minio \
  --network milvus \  # 加入同一网络
  -p 9000:9000 \
  -p 9001:9001 \
  -v $PWD/volumes/minio:/minio_data \
  -e MINIO_ACCESS_KEY=minioadmin \
  -e MINIO_SECRET_KEY=minioadmin \
  minio/minio:RELEASE.2023-03-20T20-16-18Z \
  minio server /minio_data --console-address ":9001"

# 等待etcd和minio启动完成（约30秒）
sleep 30

# 启动milvus
sudo docker run -d \
  --name milvus-standalone \
  --network milvus \  # 加入同一网络
  -p 19530:19530 \
  -p 9091:9091 \
  -v $PWD/volumes/milvus:/var/lib/milvus \
  # 关键：通过容器名访问 etcd（同一网络内可解析）
  -e ETCD_ENDPOINTS=milvus-etcd:2379 \  
  # 关键：通过容器名访问 minio
  -e MINIO_ADDRESS=milvus-minio:9000 \
  # 可选：显式指定 minio 密钥（与 minio 配置一致）
  -e MINIO_ACCESS_KEY=minioadmin \
  -e MINIO_SECRET_KEY=minioadmin \
  milvusdb/milvus:v2.4.0 \
  milvus run standalone

# 启动mongo
sudo docker run -d \
  --name mongodb \
  --network milvus \
  -p 27017:27017 \
  -v $PWD/volumes/mongo:/data/db \
  -e MONGO_INITDB_ROOT_USERNAME=root \
  -e MONGO_INITDB_ROOT_PASSWORD=123456 \
  mongo:4.4.6
```

## docker部署milvus向量数据库

```
# 1. 先删除旧的同名网络（若存在，避免冲突）
sudo docker network rm mei-milvus-network 2>/dev/null

# 2. 创建专属网络（统一命名为 mei-milvus-network，确保所有容器在同一网络）
sudo docker network create mei-milvus-network

# 3. 启动 Etcd（端口24001）
sudo docker run -d \
  --name mei-milvus-etcd \  # 容器名加前缀，避免与他人冲突
  --network mei-milvus-network \
  -p 24001:2379 \  # 主机24001 → 容器2379（无冲突）
  -v /home/mei/volumes/etcd:/etcd \  # 复用原数据卷，保留数据
  -e ETCD_AUTO_COMPACTION_MODE=revision \
  -e ETCD_AUTO_COMPACTION_RETENTION=1000 \
  -e ETCD_QUOTA_BACKEND_BYTES=4294967296 \
  -e ETCD_SNAPSHOT_COUNT=50000 \
  quay.io/coreos/etcd:v3.5.0 \
  etcd -advertise-client-urls=http://mei-milvus-etcd:2379 -listen-client-urls http://0.0.0.0:2379 --data-dir /etcd

# 4. 启动 MinIO（端口24002-24003）
sudo docker run -d \
  --name mei-milvus-minio \
  --network mei-milvus-network \
  -p 24002:9000 \  # 主机24002 → 容器9000（存储API）
  -p 24003:9001 \  # 主机24003 → 容器9001（控制台）
  -v /home/mei/volumes/minio:/minio_data \  # 复用原数据卷
  -e MINIO_ACCESS_KEY=minioadmin \
  -e MINIO_SECRET_KEY=minioadmin \
  minio/minio:RELEASE.2023-03-20T20-16-18Z \
  minio server /minio_data --console-address ":9001"

# 5. 等待Etcd/MinIO初始化（40秒，确保服务就绪）
sleep 40

# 6. 启动 Milvus（端口24004-24005）—— 关键：修正网络为mei-milvus-network
sudo docker run -d \
  --name mei-milvus-standalone \  # 加前缀，避免与他人冲突
  --network mei-milvus-network \  # 原命令错用my-milvus-network，此处修正
  -p 24004:19530 \  # 主机24004 → 容器19530（Milvus服务）
  -p 24005:9091 \   # 主机24005 → 容器9091（Milvus控制台）
  -v /home/mei/volumes/milvus:/var/lib/milvus \  # 复用原数据卷
  -e ETCD_ENDPOINTS=mei-milvus-etcd:2379 \  # 对应Etcd容器名，同一网络可解析
  -e MINIO_ADDRESS=mei-milvus-minio:9000 \  # 对应MinIO容器名
  -e MINIO_ACCESS_KEY=minioadmin \
  -e MINIO_SECRET_KEY=minioadmin \
  milvusdb/milvus:v2.4.0 \
  milvus run standalone

# 7. 启动 MongoDB（端口24006）
sudo docker run -d \
  --name mei-mongodb \  # 加前缀，避免与他人冲突
  --network mei-milvus-network \  # 加入同一网络，便于后续与Milvus联动
  -p 24006:27017 \  # 主机24006 → 容器27017（MongoDB服务）
  -v /home/mei/volumes/mongo:/data/db \  # 复用原数据卷，保留历史数据
  -e MONGO_INITDB_ROOT_USERNAME=root \  # 原用户名不变
  -e MONGO_INITDB_ROOT_PASSWORD=123456 \  # 原密码不变
  mongo:4.4.6

# 8. 验证启动结果（查看关键容器状态）
echo -e "\n=== 已启动的关键容器列表 ==="
sudo docker ps | grep -E "mei-milvus|mei-mongodb"
echo -e "\n=== 端口映射情况 ==="
sudo docker port mei-milvus-etcd
sudo docker port mei-milvus-minio
sudo docker port mei-milvus-standalone
sudo docker port mei-mongodb
```

```
# 1. 删除旧网络（若存在）
sudo docker network rm mei-milvus-network 2>/dev/null

# 2. 创建专属网络
sudo docker network create mei-milvus-network

# 3. 启动 Etcd（修正格式：确保每行末尾\后无空格）
sudo docker run -d \
  --name mei-milvus-etcd \
  --network mei-milvus-network \
  -p 24001:2379 \
  -v /home/mei/volumes/etcd:/etcd \
  -e ETCD_AUTO_COMPACTION_MODE=revision \
  -e ETCD_AUTO_COMPACTION_RETENTION=1000 \
  -e ETCD_QUOTA_BACKEND_BYTES=4294967296 \
  -e ETCD_SNAPSHOT_COUNT=50000 \
  quay.io/coreos/etcd:v3.5.0 \
  etcd -advertise-client-urls=http://mei-milvus-etcd:2379 -listen-client-urls http://0.0.0.0:2379 --data-dir /etcd

# 4. 启动 MinIO
sudo docker run -d \
  --name mei-milvus-minio \
  --network mei-milvus-network \
  -p 24002:9000 \
  -p 24003:9001 \
  -v /home/mei/volumes/minio:/minio_data \
  -e MINIO_ACCESS_KEY=minioadmin \
  -e MINIO_SECRET_KEY=minioadmin \
  minio/minio:RELEASE.2023-03-20T20-16-18Z \
  minio server /minio_data --console-address ":9001"

# 5. 等待初始化
sleep 40

# 6. 启动 Milvus
sudo docker run -d \
  --name mei-milvus-standalone \
  --network mei-milvus-network \
  -p 24004:19530 \
  -p 24005:9091 \
  -v /home/mei/volumes/milvus:/var/lib/milvus \
  -e ETCD_ENDPOINTS=mei-milvus-etcd:2379 \
  -e MINIO_ADDRESS=mei-milvus-minio:9000 \
  -e MINIO_ACCESS_KEY=minioadmin \
  -e MINIO_SECRET_KEY=minioadmin \
  milvusdb/milvus:v2.4.0 \
  milvus run standalone

# 7. 启动 MongoDB
sudo docker run -d \
  --name mei-mongodb \
  --network mei-milvus-network \
  -p 24006:27017 \
  -v /home/mei/volumes/mongo:/data/db \
  -e MONGO_INITDB_ROOT_USERNAME=root \
  -e MONGO_INITDB_ROOT_PASSWORD=123456 \
  mongo:4.4.6

# 8. 验证启动结果
echo -e "\n=== 已启动的关键容器列表 ==="
sudo docker ps | grep -E "mei-milvus|mei-mongodb"
echo -e "\n=== 端口映射情况 ==="
sudo docker port mei-milvus-etcd
sudo docker port mei-milvus-minio
sudo docker port mei-milvus-standalone
sudo docker port mei-mongodb

```



```
# 停止并删除所有相关的容器
sudo docker stop milvus-etcd milvus-minio milvus-standalone mongodb
sudo docker rm milvus-etcd milvus-minio milvus-standalone mongodb

# 删除网络（如果存在）
sudo docker network rm milvus


```



外部知识库,密码：

```markdown
# 连接到MongoDB
docker exec -it mongodb-temp mongo

# 在MongoDB shell中执行以下命令
use admin
db.createUser({user: "root", pwd: "123456", roles: [{role: "root", db: "admin"}]})

use zhgy
db.createUser({user: "root", pwd: "123456", roles: [{role: "readWrite", db: "zhgy"}]})

# 查询数据：
show collections

# 退出MongoDB shell
exit
```

create_milvus_collection.py

```python
from pymilvus import (
    connections,
    FieldSchema,
    CollectionSchema,
    DataType,
    Collection,
    utility
)

# 1. 连接Docker中的Milvus（关键：host填localhost，端口19530）
# 因为脚本在服务器本地运行，且Milvus容器的19530端口已映射到服务器
#SERVER_IP="129.211.214.104"

connections.connect(
    alias="default",
    host="localhost",  # 服务器本地连接，固定为localhost
    port="19530",       # Milvus默认端口（容器已映射到服务器）
    timeout=30
    )

# 2. 检查连接是否成功
try:
    server_version = utility.get_server_version()
    print(f"✅ 成功连接Milvus，版本：{server_version}")
except Exception as e:
    print(f"❌ 连接Milvus失败：{e}")
    exit()

# 3. 定义Collection的字段（根据你的需求：request_id + vector）
fields = [
    # 主键：request_id（与MongoDB关联）
    FieldSchema(
        name="request_id",
        dtype=DataType.VARCHAR,
        max_length=64,  # 足够存储UUID等标识
        is_primary=True
    ),
    # 向量字段：图片特征向量（假设2048维，根据你的模型调整）
    FieldSchema(
        name="vector",
        dtype=DataType.FLOAT_VECTOR,
        dim=512  # 维度必须与特征提取模型输出一致
    )
]

# 4. 定义集合Schema
schema = CollectionSchema(
    fields=fields,
    description="存储农事图片向量+request_id，用于RAG检索"
)

# 5. 集合名称（自定义，如"farm_image_vectors"）
collection_name = "farmap_image_vectors_new"

# 6. 若集合已存在则删除（首次创建可跳过，更新时使用）
if utility.has_collection(collection_name):
    utility.drop_collection(collection_name)
    print(f"已删除旧集合：{collection_name}")

# 7. 创建新集合
collection = Collection(
    name=collection_name,
    schema=schema,
    using="default"
)
print(f"✅ 成功创建集合：{collection_name}")

# 8. 为向量字段创建索引（必须步骤，否则无法搜索）
index_params = {
    "index_type": "IVF_FLAT",  # 适合中小数据量
    "metric_type": "IP",       # 欧式距离（图片向量常用）
    "params": {"nlist": 128}   # 聚类数量，数据量增长后可调大
}
collection.create_index(
    field_name="vector",  # 为vector字段创建索引
    index_params=index_params
)
print(f"✅ 成功创建索引，参数：{index_params}")

# 9. 验证集合是否可用
if utility.has_collection(collection_name):
    print(f"\n🎉 操作完成，集合 {collection_name} 已就绪")
else:
    print("❌ 集合创建失败")
```



## milvus操作：

```markdown
# 登录
connect -uri http://127.0.0.1:19530

# 首先查看集合的详细信息
show collection -c farmap_image_vectors

# 查询条件 通过query交互式查看
The query expression:request_id != ""
```





