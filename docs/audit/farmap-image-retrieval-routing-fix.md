# FarMap Agent 全链路审计、图片检索修复与线上部署报告

日期：2026-09-22  
范围：本地项目 `E:\前后端项目\farmap`、线上 `https://map.archivemodel.cn/farmap/`。  
原则：只使用项目现有百炼/通义千问配置；不引入混元密钥、不重新嵌入已有数据、不清空生产数据库或 Milvus 数据。

## 一、最终结论

FarMap Agent 的核心商用演示链路已经恢复并部署到服务器：登录、三农场切换、地块上下文、规则/模型混合路由、SSE 流式回答、知识检索、参考图片检索、图片展示、会话持久化和停止生成均可用。

本次用户看到“只输出文字、不显示图片”的根因不是单一 Router 误判，而是三个真实断点叠加：

1. 线上 Milvus 缺失 `farmap_reference_images_v1`，导致后端只能返回 `reference.images: count=0, status=UNAVAILABLE`。
2. 前端 `requestUrl()` 将相对 API 基址 `/api` 直接传给 `new URL()`，浏览器报 `Invalid base URL`，SSE 请求未能真正到达后端。
3. Qwen 知识检索在作物过滤后候选为空时提前返回，未执行既有的“仅回退到无作物标签公共知识”逻辑，导致 Copilot 带巫山脆李上下文时知识条数错误显示为 0。

已完成修复：将本地已有 90 条参考图向量记录导入线上缺失集合；补齐百炼/COS 环境变量转发；让只选择农场且农场作物唯一时使用服务端农场字段主数据推导可信作物上下文；修复前端相对 URL 的构造；重新构建并替换线上 backend/frontend 容器。

本地代码还补上了 Qwen 检索的候选为空回退分支：只在明确作物过滤没有命中时重试一次无作物过滤，并丢弃所有带其他作物标签的结果。该修复已通过本地完整 Maven 回归，并已发布到线上 backend；线上巫山农场无地块请求复测为知识 `DEGRADED/count=5`、参考图片 `READY/count=3`。

## 二、问题链路的真实证据

使用线上管理员账号、巫山农场 `farmId=7` 和核心监测地块 `field-wushan-plum-core-east` 测试 `褐腐病是什么样的？`：

| 检查项 | 结果 |
|---|---|
| 规则路由 | `KNOWLEDGE_QUERY` |
| qwen-flash 路由 | 当前默认 `SHADOW`，本次规则置信度高，最终没有交给模型接管 |
| 最终意图 | `KNOWLEDGE_QUERY` |
| 图片需求 | `BENEFICIAL` |
| 图片检索入口 | `CopilotController.emitReferenceImages` → `ReferenceImageAttacher.forChatResult` → `ReferenceImageStore` |
| 文本知识检索 | 线上接口返回 5 条；Copilot 作物过滤回退后真实 SSE 返回 5 条 |
| Milvus 参考图检索 | 线上集合查询成功，褐腐病匹配 12 行 |
| 图片过滤 | 12 行全部通过真实 COS host 白名单 |
| SSE 图片事件 | `reference.images`，`status=READY`，`count=3` |
| React 展示 | Playwright 真实线上浏览器回归通过，页面显示 3 张图片 |

没有选择地块时，后端现在会读取当前农场的 `farm_fields`；只有作物唯一时才使用该上下文。混合种植农场仍要求用户选择具体地块，不会猜测作物或拿其他作物图片冒充。

## 三、必答问题

### 1. 「褐腐病是什么样的？」最终识别成什么意图？

`KNOWLEDGE_QUERY`。它不是创建任务，也不是直接执行农事动作。

### 2. 是否识别出图片需求？

是。普通外观问题为 `BENEFICIAL`；“给我看褐腐病参考图片”被识别为 `EXPLICIT`。

### 3. 是否调用了图片检索？

是。调用了既有参考图谱链路，没有让模型编造 URL。

### 4. 实际返回多少张相关图片？

当前限制为每个疾病最多 3 张；褐腐病真实返回 3 张，Milvus 过滤前匹配 12 行、过滤后接受 12 行。

### 5. 图片来自哪个真实知识记录？

来自参考图谱的稳定记录：

```text
plum-dis-brown_rot-01
plum-dis-brown_rot-02
plum-dis-brown_rot-03
```

标签为 `褐腐病`，`diseaseKey=brown_rot`，URL 来自既有腾讯 COS `image_url` 字段。

### 6. SSE 是否传输了图片？

是。真实事件包含 `reference.images`，其中含 `refId`、`label`、`imageUrl`、`match`、`mediaNeed`、`status`、匹配行数等字段。

### 7. 前端是否成功显示？

是。线上 Playwright 测试 `copilot-reference-images.spec.ts` 通过，验证了 3 个 `[data-reference-image='true']` 元素及 3 个图片节点，并验证标签包含“褐腐病”。

### 8. 当前知识库是否真的存在褐腐病图片？

是。本地参考图清单共 90 条，其中褐腐病 12 条；线上已导入同一批 90 条记录，并通过真实 SSE 查询返回上述记录。没有对应疾病时返回 `NO_MATCH`，不会使用其他病害图片替代。

## 四、其他真实回归结果

| 问题 | 最终意图 | 图片策略 | 线上结果 |
|---|---|---|---|
| 褐腐病是什么样的？ | `KNOWLEDGE_QUERY` | `BENEFICIAL` | `READY`，3 张，`brown_rot` |
| 给我看褐腐病参考图片。 | `GENERAL_QA` | `EXPLICIT` | `READY`，3 张，`brown_rot` |
| 脆李穿孔病是什么样的？ | `KNOWLEDGE_QUERY` | `BENEFICIAL` | `READY`，3 张，`bacterial_spot` |
| 脆李萌芽期图片 | `GENERAL_QA` | `EXPLICIT` | `READY`，3 张，`germination` |
| 什么是叶面肥？ | `KNOWLEDGE_QUERY` | `NONE` | 不触发图片检索 |
| 葡萄霜霉病是什么样的？ | `KNOWLEDGE_QUERY` | `BENEFICIAL` | `NO_MATCH`，不冒充图片 |

## 五、当前各模块状态

| 模块 | 状态 | 说明 |
|---|---|---|
| 登录与权限 | 可用 | 线上正式登录成功，管理员可看到云阳、大足、巫山三农场 |
| 农场/地块上下文 | 可用 | 巫山 6 个地块，核心地块作物为 `plum` |
| 意图识别 | 可用 | 规则路由稳定；模型路由处于既有 `SHADOW` 观测模式，没有改生产默认模式 |
| Copilot SSE | 可用 | 路由、知识、参考图、回答、完成事件均可正常传输 |
| 参考图片 RAG | 可用 | 线上 90 条参考图记录已恢复，COS URL 受白名单保护 |
| 知识库 RAG | 可用 | 线上 MySQL 有 15 个知识版本，Qwen 向量集合有 42 行；公共检索和 Copilot 均可返回真实知识，作物过滤回退不会混入其他作物标签 |
| 用户上传图片诊断 | 已实现 | 上传、现场图片与参考图分离、百炼视觉模型网关均存在；本轮未用生产账号提交新的真实农事诊断写入 |
| Agent Runtime | 可用 | 线上持久化方式为 MySQL，状态 `READY`；审批闸门和工具调用代码已通过后端测试 |
| Operations | 可用 | 数据库任务服务状态 `READY`，写操作仍需要人工确认 |
| 会话历史/恢复 | 已实现 | 会话按登录用户隔离，可列出、恢复并继续上下文；系统路由确认事件不再污染用户可见助手消息 |
| 语音/粘贴图片 | 已实现前端入口 | 本轮重点验证的是 SSE 与参考图展示，浏览器麦克风权限和实际语音识别供应商仍需在目标浏览器人工验收 |
| 历史病例库 | 空但可用 | 线上 `READY_EMPTY`，没有历史病例时明确返回空，不伪造相似病例 |

## 六、部署记录

- 线上新发布目录：`/home/sym/mei/releases/farmap-20260922b`。
- 线上 Farmap Compose 服务已使用新 backend/frontend 镜像；没有执行 `down -v`，没有删除 MySQL、Mongo、Milvus、MinIO、etcd 数据卷。
- 原部署备份：`/home/sym/mei/backups/farmap-predeploy-20260922T122346Z`。
- 已导入线上 Milvus 集合：`farmap_reference_images_v1`，90 条记录，未重新调用嵌入模型。
- 线上入口：<https://map.archivemodel.cn/farmap/>。
- 根域名入口：<https://map.archivemodel.cn/>；HTTP 入口也可访问并由现有网关接管。
- 百炼配置在运行时显示为 `QWEN_MODEL_STUDIO`，视觉模型为 `qwen3-vl-32b-thinking`，没有切换到混元。
- 最新后端发布后，`farmap-backend-1` 重启次数为 0；MySQL、Mongo、Milvus、MinIO、etcd 数据服务未重建，Milvus `/healthz` 返回 `OK`。

## 七、验证记录

### 后端

本地 Maven 测试汇总：226 tests，0 failures，0 errors，18 skipped。跳过项为依赖外部服务或数据库环境的测试，不是断言失败。

### 前端

- `pnpm exec tsc -b`：通过。
- `VITE_API_URL=/api pnpm exec vite build`：通过。
- 线上 `copilot-reference-images.spec.ts`：1 passed，验证真实图片显示。
- 线上 `copilot-responsiveness.spec.ts`：2 passed，验证普通问答交互时限和取消正在发送的请求。
- 最新线上 SSE 复测（2026-09-22）：巫山农场 `farmId=7`、不指定地块时，`给我一张果实膨大期的照片` 识别为 `KNOWLEDGE_QUERY`，参考图 `READY/count=3`，3 张物候图来自 `fruit_development`；`褐腐病是什么样的？` 识别为 `KNOWLEDGE_QUERY`，参考图 `READY/count=3`，3 张图片的 `diseaseKey=brown_rot`。抽查的 COS 图片 URL HEAD 均返回 `200`。

## 八、仍需如实说明的边界

1. 线上历史病例集合目前为空，这是数据状态，不是接口故障。
2. 线上健康信息中的旧 512 维历史/默认知识集合仍显示缺失；当前 Copilot 使用的是 Qwen 1024 维知识集合和独立参考图谱，二者不要混淆。Qwen 运行健康检查显示集合存在、42 行、百炼 embedding READY。
3. 本轮没有执行会真实创建农事任务、分配负责人并完成任务的线上浏览器写入验收，避免污染服务器业务数据；相关后端单元/集成测试已通过，正式演示时应使用专门试验农场账号。
4. 线上知识版本数为 15，而本地开发目录存在更多测试/导入记录。本次没有把本地测试数据库整体覆盖到服务器，避免破坏服务器现有三农场和业务数据；如需同步剩余知识，应按稳定 `source/document/version/chunk` 做增量导入。

## 九、主要修复文件

- `zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/reference/ReferenceImageStore.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/reference/ReferenceImageAttacher.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotMediaPolicy.java`
- `farmap-frontend-dev/src/utils/sse.ts`
- `farmap-frontend-dev/src/components/copilot/CopilotDrawer.tsx`
- `deployment/docker-compose.yml`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/knowledge/QwenKnowledgeVectorStore.java`

## 十、本地 Agent 意图理解增强（2026-09-22）

针对“给我一张果实膨大期的照片”被误判为需要诊断上下文的问题，本地代码已补充以下规则：

- 参考图请求与现场诊断分离：`给我看/给我一张/参考图/展示` 等请求进入 `KNOWLEDGE_QUERY`；“上传/现场/这张照片/分析这张”等仍进入现场诊断路径。
- 物候阶段别名统一：`果实膨大期`、`膨果期`、`膨大期`、`果实发育期` 均归一为 `fruit_development`，复用现有 6 张物候参考图，不重新嵌入。
- 增加受控文本归一化：兼容全角符号、空白、繁体“果實”，以及明确的语音/输入法误写“脆里/翠李”；未知词不做猜测。
- 带用户上传图片的省略式问题（如“这是什么病？”）在聊天路由中提升为 `DIAGNOSIS_REQUEST`；明确要求参考图时不会被提升。

新增回归用例覆盖上述路由、媒体策略、阶段别名和作物误写。该部分修改已在本地后端测试中验证，并已于 2026-09-22 替换线上 `farmap-backend-1` 容器；线上 SSE 已重新验证对应的果实膨大期图片事件。
