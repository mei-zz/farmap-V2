# FarMap Agent Phase 3：真实对话修复、多模态 RAG 与商业化交互改造

审计日期：2026-09-20  
审计范围：本地 FarMap 前后端、当前真实运行时、Copilot SSE 对话链路、Agent Runtime/Operations 链路。  
测试原则：使用现有百炼配置、现有知识向量索引、现有多模态图片索引和现有 COS 参考图链路；不重建向量、不审批动作、不写入生产农事任务。

## 1. 结论

Phase 3 的核心链路已经打通，但不能把“浏览器端完整 E2E 已验收”写成已完成：当前环境没有可用的浏览器自动化工具，本报告中的页面行为以真实 HTTP/SSE 和后端单测验证，浏览器视觉回归仍需人工打开当前前端地址验证。

已真实验证的关键结果：

| 场景 | 结果 | 证据 |
|---|---|---|
| 巫山脆李病害参考图 | 通过 | “脆李穿孔病长什么样”返回 3 张实际参考图，refId=plum-dis-bacterial_spot-01/02/03，label=细菌性穿孔病，match=direct |
| 巫山直接创建任务 | 通过（停在审批） | ACTION_REQUEST → START_AGENT_REQUIRES_APPROVAL → WAITING_FOR_APPROVAL；1 个 REQUIRED 动作，无执行写入 |
| 简单问候 | 通过 | 进入 SIMPLE，不触发知识检索、诊断、参考图或 Agent Run |
| 无图病害问题 | 通过 | 返回上下文感知澄清，只要求补充图片/发病时间，不直接诊断或推荐药剂 |
| 复杂管理回答卡片 | 代码和单测通过 | CopilotResponsePolicyTest 验证不再按每个知识块重复生成完整方案卡 |
| 多轮上下文 | 后端链路已保留，浏览器回归待人工确认 | 使用现有 conversation/session 状态和当前地块上下文；未伪造浏览器验收结果 |

## 2. 复现到的主要问题与根因

### 2.1 简单问候被错误推入重链路

原链路把普通问候交给完整 Copilot/知识链路，造成不必要延迟，甚至在模型或检索不可用时显示“Copilot 暂时不可用”。

修复位置：zhgy/zhgy/src/main/java/com/mei/zhgy/controller/CopilotController.java。

现在 GENERAL_QA + RESOLVED + casual greeting 直接返回短文本和 SIMPLE 元数据，cards、sources、reference images、actions 均为空，不调用诊断和 Agent。

### 2.2 无图片病害问题缺少可执行的证据澄清

原链路在没有图片时容易直接进入诊断或返回泛化提示，不能告诉用户下一步该补什么证据。

修复位置：CopilotController.java、CopilotContextResolver.java、CopilotResponsePolicy.java。

现在会先复用当前农场/地块/作物/物候上下文；缺图片时只澄清必要证据。叶片问题优先提示“粘贴叶片正反面照片”，症状问题再提示“说明症状从什么时候开始”；不在证据不足时给出确定病害和用药结论。

### 2.3 直接创建任务被错误要求诊断图片

原 Agent Planner 对没有 diagnosisId 的请求仍规划多模态诊断/RAG，最终出现 RAG_IMAGE_INPUT_EMPTY，导致“帮我创建明天的浇水任务”虽然产生了 Run，却没有形成可审批动作。

修复位置：zhgy/zhgy/src/main/java/com/mei/zhgy/service/agent/AgentPlanner.java、AgentRuntime.java、CopilotController.java。

直接任务请求现在走最小 Operations 计划：

    field-context -> weather -> gis -> task

它不强制要求诊断图片；只有通过现有 field-context 工具读到 fieldFactsStatus=READY 后，运行时才构造服务端拥有的 field_context 证据，并形成 ProposedAction。动作仍然是 REQUIRED，必须经过现有审批接口后才允许 Operations 写入。

### 2.4 意图规则误判任务和诊断

修复位置：zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotIntentRouter.java。

任务创建词增加明确动作表达，并增加否定保护；泛化的“为什么/原因”不再单独把普通知识问题判成诊断。复杂管理问题保留知识/指导路径，直接任务请求才进入 ACTION_REQUEST。

### 2.5 知识块导致重复方案卡片

修复位置：zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/CopilotResponsePolicy.java。

GUIDED 回答只保留上下文方案卡，知识来源折叠到 provenance；KNOWLEDGE 最多生成两个紧凑证据卡，其余内容通过来源列表呈现。这样不会因为多个 chunk 把相同的肥水管理/病虫害管理卡片重复渲染。

## 3. 多模态 RAG 现状与真实验证

本阶段没有新建向量，也没有替换现有模型或数据源。当前后端健康检查读到的索引状态为：

- 知识向量集合：farmap_knowledge_chunks_qwen3_v1，维度 1024，RRF 融合，996 条；
- 多模态图片资产集合：farmap_multimodal_image_assets_v1，维度 512，13 条；
- 旧/历史图片向量集合：当前为空；
- 知识运行时：READY；
- 多模态视觉网关：CONFIGURED，使用现有 Qwen VL 配置；
- COS/参考图链路：由现有 ReferenceImageStore 和 ReferenceImageAttacher 负责，返回前经过既有允许列表/参考图协议。

### 3.1 真实参考图请求

测试上下文：巫山农场、L-01 巫山脆李核心监测区、脆李、当前物候上下文。请求：

    脆李穿孔病长什么样

真实 SSE 结果：

    reference.images.count = 3
    plum-dis-bacterial_spot-01  细菌性穿孔病  match=direct
    plum-dis-bacterial_spot-02  细菌性穿孔病  match=direct
    plum-dis-bacterial_spot-03  细菌性穿孔病  match=direct
    response.meta.cards = 2
    response.meta.sources = 5
    done = COMPLETED

测试只核对了返回数量、稳定引用、标签和来源类型，没有把签名 URL 写入报告，也没有伪造图片地址。浏览器端应继续确认 reference.images 被 Image.PreviewGroup 正常显示；接口侧已经证明图片不是空数组。

## 4. Agent 任务链真实验证

请求：

    帮我创建明天的浇水任务

真实上下文为巫山 L-01 脆李地块。结果：

    intent = ACTION_REQUEST
    allowedNextAction = START_AGENT_REQUIRES_APPROVAL
    run.status = WAITING_FOR_APPROVAL
    approvalState = REQUIRED
    actionCount = 1
    action.title = 明天浇水 · L-01 巫山脆李核心监测区
    action.approvalState = REQUIRED
    evidenceCount = 1
    evidence.modality = field_context
    evidence.id = task-context-<runId>

本次没有调用批准接口，没有创建或修改 Operations 生产任务。前端应把 run.created 展示为“任务草案已进入 Agent”，等待用户显式确认；只有用户批准后，才允许进入现有 Operations 写入链路。

## 5. SSE/前端协议检查

后端已保留并补齐以下前端可消费事件：

- route：意图、解析状态、下一步能力；
- response.meta：响应模式、卡片、来源、建议和动作；
- reference.images：参考图列表；
- run.created：runId、可选 diagnosisId、工作流和状态；
- done：本次 SSE 结束状态。

直接任务的 run.created 不再要求必须有 diagnosisId。前端 farmap-frontend-dev/src/components/copilot/CopilotDrawer.tsx 也只在有 diagnosisId 时拼接诊断查询参数，避免直接任务被前端误判为诊断 Agent。

## 6. 已执行的自动化检查

### 后端

通过：

    CopilotIntentHybridTest
    CopilotResponsePolicyTest
    AgentPlannerTest
    AgentEvidenceSufficiencyTest
    mvn -q -DskipTests compile -Dstyle.color=never

新增/调整断言覆盖：

- 普通知识问题不误判为动作；
- 否定任务请求不进入动作创建；
- 直接任务不强制多模态 RAG；
- 直接任务只能利用已验证的地块上下文形成审批前动作；
- GUIDED 不重复生成完整证据卡；
- 诊断澄清建议与缺失证据一致。

测试输出只有已有的 SLF4J 多绑定警告，没有失败。

### 前端

通过：

    pnpm lint
    pnpm build

构建只有既有 chunk size 提示，没有构建失败。前端保留现有 Markdown、图片粘贴、语音入口、参考图预览、会话历史和 SSE 事件消费改动。

## 7. 当前运行状态与限制

- 本地后端已启动在 http://localhost:8080；
- 本地前端开发服务仍在 http://localhost:5173；
- 运行模式为真实模式，百炼 Qwen Model Studio 已配置；健康检查显示模型配置已加载，但具体模型可用性仍由实际调用验证，健康接口本身不会逐模型探测；
- MongoDB 在本地启动日志中不可用，但本阶段核心 Copilot、知识向量、图片索引和 Agent Run 持久化链路未因此失败；
- 没有执行浏览器自动化，因此没有声称页面截图级验收；
- 没有执行审批，也没有写入生产 Operations 任务；
- 当前参考图测试证明接口返回 3 张图，但缩略图加载、点击大图、连续多轮换图等仍需在浏览器手工回归；
- 当前多模态图片向量集合只有 13 条，不能据此声称覆盖全部脆李病害或全部农场。

## 8. 浏览器手工回归清单

在当前前端页面完成以下检查：

1. 选择“巫山”和 L-01 巫山脆李核心监测区，输入“你好”：应立即得到简短问候，不出现知识卡、诊断卡或参考图。
2. 输入“脆李穿孔病长什么样”：应看到 3 张细菌性穿孔病参考图；点击缩略图应在当前页预览，不跳转、不下载。
3. 输入“我的李子叶片发黄了，该打什么药？”但不上传图片：应先询问叶片正反面照片和发病时间，不应直接给药剂结论。
4. 输入“结合当前 L-01 地块，制定脆李采后管理方案”：应显示一张主方案卡，来源可展开，不能出现按 chunk 重复的多张同内容方案卡。
5. 输入“帮我创建明天的浇水任务”：应显示任务草案和待审批状态；未点批准前，Operations 列表不应新增任务。
6. 点击“新对话”，再从“历史”切回旧会话：应恢复原会话消息和地块上下文；切换用户后不能看到前一用户的会话。
7. 粘贴图片和使用语音输入：消息气泡应显示图片预览，语音转写内容应进入输入框；发送失败时不得吞掉图片或文字。

## 9. 回滚边界

本阶段改动集中在上面列出的 Copilot、Agent Runtime/Planner、Response Policy、前端 CopilotDrawer.tsx 和对应测试文件。若需要回滚，应按 Git diff 精确回退这些 Phase 3 自有 hunks；不要使用 reset --hard、整目录覆盖或删除运行数据。已有的 Phase 1/2、用户未提交改动和运行环境配置必须保留。

## 10. 最终状态表

| 项目 | 状态 |
|---|---|
| 简单问候降级 | 已完成并通过真实接口验证 |
| 无图诊断澄清 | 已完成并通过真实接口验证 |
| 病害参考图返回 | 已完成并通过巫山真实接口验证，返回 3 张 |
| 复杂回答去重卡片 | 已完成，后端单测通过；浏览器视觉回归待人工确认 |
| 直接任务草案 | 已完成并通过巫山真实接口验证 |
| 审批闸门 | 已验证停在 REQUIRED，未执行写入 |
| 多轮/多用户会话隔离 | 代码链路保留，需浏览器人工回归 |
| 图片粘贴/语音输入 | 代码已接入，需浏览器人工回归 |
| 前端构建 | 通过 |
| 后端编译与目标单测 | 通过 |
| 浏览器完整 E2E | 未宣称完成，受当前工具环境限制 |
