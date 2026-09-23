# FarMap Agent 流式回答时序与 RAG 知识卡片体验修复

日期：2026-09-22

## 1. 结论

本次修复聚焦于“流式回答时序、知识依据展示、滚动体验和异常收口”，没有改动模型供应商、Hybrid Router、Agent Runtime、RAG 检索算法、数据库表结构或 Milvus 集合。

核心结果：回答区域现在先出现处理中状态，收到第一个 `content.delta` 后才创建助手回答气泡；`response.meta`、知识检索结果和参考图先保存在本次请求的内存缓冲中，收到 `done` 后再挂载到已完成回答。这样不会再出现“知识卡片/来源先跳出来，正文随后才开始”的错位体验。

## 2. 根因与真实事件顺序

后端 `CopilotController.streamChat` 的普通知识问答实际发送顺序为：

```text
status(结合农场情况)
route
status(检索农业知识库)
knowledge
reference.images
answer.source
response.meta
status(生成建议)
content.delta × N
done
```

这不是后端事件缺失，而是前端原先在收到 `response.meta` 时立即调用消息更新逻辑。由于此时正文还没有 token，前端会先把知识卡片、建议或来源渲染到回答区域；后续 `content.delta` 才补正文，造成用户看到的时序与“先回答、后依据”不一致。

## 3. 修改后的生命周期

```text
发送问题
  ↓
处理中状态（检索 / 生成阶段）
  ↓ 首个 content.delta
创建助手回答气泡并持续追加正文
  ↓ done
挂载回答模式、农情卡片、可折叠知识依据、参考图和追问
```

`error` 会终止当前请求并保留已经收到的部分正文；如果正文尚未开始，则展示安全错误提示。`done`、`error` 之后的迟到 SSE 事件会被忽略，取消请求会通过 request sequence 和 AbortController 使旧请求失效。

## 4. 前端改动

文件：`farmap-frontend-dev/src/components/copilot/CopilotDrawer.tsx`

- `startLightweightChat` 增加请求级 `responseMeta`、`knowledgeCount`、`referenceImages` 和完成标记。
- `upsertStreamMessage` 在没有正文时不创建回答消息；流式阶段不挂载元数据，`done` 后一次性挂载。
- 增加可读的处理中状态，展示当前“检索/生成”阶段。
- 增加滚动跟随判断：用户在底部时自动跟随新 token，用户向上查看历史时不强行拉回底部；发送新问题时重新回到底部。
- 回答图片仍属于用户消息并显示在对话中；参考图保持横向滚动、缩略图和页内预览。
- 依据区域使用原生 `details`，默认折叠；不再把每个 RAG chunk 生成重复的回答卡片。

文件：`farmap-frontend-dev/index.css`

- 增加处理中状态、依据折叠区、依据条目的紧凑样式。
- 保持消息区 `flex: 1` 和 `overflow-y: auto`，输入区继续固定在抽屉底部。

## 5. Answer / Evidence / Advice 的职责边界

- Answer：只承载模型流式正文，由 `content.delta` 驱动。
- Evidence：只承载后端返回的真实来源引用，默认折叠；不重新生成回答，不伪造来源 ID。
- Advice：只在已有的响应模式或 Agent 结构化结果中展示。GUIDED 模式可以保留当前地块上下文卡片，KNOWLEDGE 模式不再把检索片段复制成第二组知识卡片。

后端 `CopilotResponsePolicy` 的来源引用优先使用 `chunkId → documentId → versionId → sourceId`，没有稳定 ID 的检索行不进入可核验来源列表；引用文本统一压缩到短摘要并最多展示 5 条。

## 6. SSE 边界处理

- `response.meta` 在正文前到达：只缓存，不渲染。
- `reference.images` 在正文前或后到达：只缓存，完成后挂载。
- 只有来源、没有正文：不创建空回答气泡。
- `error`：保留已收到正文并标记“生成未完成”，没有正文则展示安全错误。
- `done`：只处理一次，终止后忽略迟到事件。
- 用户取消或发送新问题：旧请求失效，不得覆盖新会话的消息和状态。
- 历史消息：仍由现有会话恢复链路负责；本次只改变流式消息的生成时序，不改变会话持久化协议。

## 7. 验证结果

后端定向回归：

```text
CopilotIntentHybridTest       7 passed
CopilotResponsePolicyTest     9 passed
总计                         16 passed, 0 failed
```

覆盖了响应模式选择、澄清不提前暴露知识来源、知识依据不重复生成卡片、GUIDED 上下文卡片、诊断澄清、稳定来源 ID 和长引用压缩。

前端构建产物已更新到 `farmap-frontend-dev/dist`，产物中可检索到处理中状态和 `response-sources` 逻辑。由于本机 Docker Linux 引擎当前不可用，尚未完成依赖 Milvus 的浏览器端真实知识命中验收；这不影响前端构建和不依赖 Milvus 的流式协议验收，但会影响“真实 RAG 来源数量”和向量检索结果的验证。

本轮真实本地 SSE 冒烟已通过：

```text
问候请求：status → route → response.meta → content.delta → done
农业知识请求：status → route → knowledge → reference.images → answer.source
             → response.meta → content.delta → done
农业知识请求正文增量：55 个
```

`pnpm build` 的 TypeScript 检查和 Vite 7426 模块转换均通过；默认压缩阶段受当前 Windows 无分页文件环境的系统提交内存限制（`errno=1455`）失败。已经生成的预览 `dist` 可正常被 Vite preview 提供服务；生产机或配置分页文件后应重新执行 `pnpm build`。

## 8. 浏览器验收清单

启动 MySQL、后端和前端后，至少验证：

1. 输入“你好”：先出现处理中状态，正文先出现，结束后不出现无关知识卡片。
2. 输入“什么是叶面肥？”：正文流式出现；结束后如有真实来源，`查看 N 条可核验知识依据` 默认折叠，展开后能看到稳定 ID。
3. 输入“脆李采后应该如何管理？”：正文后显示当前农情上下文/追问；检索片段不再重复成为第二份答案。
4. 连续快速发送两个不同问题：旧问题的迟到 token 不得写入新问题的回答。
5. 回答较长时，停留在底部应自动跟随；向上滚动阅读历史时不应被强制拉到底部。
6. 中途点击停止：回答保留已产生正文，旧请求不再继续更新界面。
7. 粘贴图片并提问：用户图片应先显示在用户气泡中；没有图片或模型不可用时，显示明确的安全提示，不生成伪诊断结论。

## 9. 尚未完成的外部条件

当前机器的 Docker Linux 引擎无法连接（`dockerDesktopLinuxEngine` pipe 不存在），因此 Milvus 容器未能启动。本次没有删除或重建任何 Milvus 容器/卷，也没有改写 Milvus 集合。恢复 Docker 引擎后，应先启动既有 `milvus-etcd`、`milvus-minio`、`milvus-standalone`，再重启后端，最后按第 8 节执行真实 RAG 验收。

## 10. 回滚

本次代码改动集中在 `CopilotDrawer.tsx`、`index.css`、`CopilotResponsePolicy.java` 及其测试；按 Git 工作区逐文件回退即可，不需要数据库回滚、Milvus 数据回滚或模型配置回滚。
