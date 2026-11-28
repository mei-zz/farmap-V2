import { expect, test, type Page } from "@playwright/test";

const composerPlaceholder = "描述你的农事需求，或上传作物图片……";

function sse(events: Array<[string, unknown]>): string {
  return events.map(([event, data]) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`).join("");
}

async function openWorkspace(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem("token", "message-parts-e2e");
    localStorage.setItem("user", JSON.stringify({ username: "测试账号", role: "user", farms: [{ id: 7, name: "巫山果园" }] }));
  });
  await page.route("**/user/validate-token", (route) => route.fulfill({ json: { data: { isValid: true, isExpired: false } } }));
  await page.route("**/api/farms/7/**", (route) => route.fulfill({ json: { data: [] } }));
  await page.route("**/api/copilot/memories**", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/copilot/conversations?**", (route) => route.fulfill({ json: [] }));
  await page.goto("workspace");
  await expect(page.getByPlaceholder(composerPlaceholder)).toBeVisible();
}

async function ask(page: Page, prompt: string) {
  await page.getByPlaceholder(composerPlaceholder).fill(prompt);
  await page.getByRole("button", { name: "发送消息" }).click();
}

const routeEvent: [string, unknown] = ["route", {
  conversationId: "message-parts-conversation",
  contextVersion: 1,
  intent: "GENERAL_QA",
  resolutionStatus: "RESOLVED",
}];

const block = {
  schemaVersion: 1,
  blockId: "message-parts-block",
  runId: "message-parts-run",
  componentType: "FarmDirectoryCard",
  title: "相关农场",
  status: "ready",
  data: { farms: [{ id: 7, name: "巫山果园" }] },
  actions: [],
  provenance: [],
  createdAt: "2026-11-26T00:00:00Z",
  updatedAt: "2026-11-26T00:00:00Z",
};

test("a dropped stream restores only the matching persisted assistant reply", async ({ page }) => {
  await openWorkspace(page);
  let recoveryReads = 0;
  await page.route("**/api/copilot/conversations/message-parts-conversation", async (route) => {
    recoveryReads++;
    await route.fulfill({ json: {
      conversationId: "message-parts-conversation", status: "ACTIVE", contextVersion: 1,
      summaryVersion: 1, turnCount: 2, recentTurns: [],
      historyTurns: [
        { turnId: "t-before", traceId: "older-turn", role: "ASSISTANT", message: "这条旧回复不能被当成本轮。" },
        { turnId: "t-recovered", traceId: "dropped-stream-trace", role: "ASSISTANT", message: "从本轮已保存回复恢复完成。" },
      ],
    } });
  });
  await page.route("**/api/copilot/chat", (route) => route.fulfill({
    contentType: "text/event-stream",
    body: sse([
      ["route", { ...routeEvent[1] as Record<string, unknown>, traceId: "dropped-stream-trace" }],
      ["message.part", { type: "text", id: "partial", delta: "仍在查找资料…" }],
    ]),
  }));
  await ask(page, "看看这块地的天气和物候");
  const assistant = page.locator(".farmap-copilot__message--assistant");
  await expect(assistant.getByText("从本轮已保存回复恢复完成。")).toBeVisible();
  await expect(assistant).not.toContainText("仍在查找资料");
  await expect(assistant).not.toContainText("这条旧回复");
  expect(recoveryReads).toBe(1);
});

test("the active stream supports queueing, abort and ordered continuation", async ({ page }) => {
  await page.addInitScript(() => {
    const original = window.fetch.bind(window);
    window.fetch = (input, init) => {
      const url = input instanceof Request ? input.url : String(input);
      if (!url.endsWith("/api/copilot/chat")) return original(input, init);
      const encoder = new TextEncoder();
      return Promise.resolve(new Response(new ReadableStream({
        start(controller) {
          controller.enqueue(encoder.encode('event: route\ndata: {"conversationId":"message-parts-conversation","contextVersion":1,"intent":"GENERAL_QA","resolutionStatus":"RESOLVED"}\n\n'
            + 'event: message.part\ndata: {"type":"text","id":"first-part","delta":"正在读取现场信息。"}\n\n'));
          init?.signal?.addEventListener("abort", () => controller.error(new DOMException("Aborted", "AbortError")), { once: true });
        },
      }), { headers: { "Content-Type": "text/event-stream" } }));
    };
  });
  await openWorkspace(page);
  const operations: string[] = [];
  await page.route("**/api/copilot/conversations/message-parts-conversation/agent-control", async (route) => {
    const body = route.request().postDataJSON() as { operation: string; contextVersion: number; message?: string };
    expect(body.contextVersion).toBe(1);
    operations.push(body.operation);
    if (body.operation === "followUp") expect(body.message).toBe("补充检查当前地块");
    const messages: Array<[string, unknown]> = body.operation === "continue"
      ? [["message.part", { type: "text", id: "continued", delta: "续跑后的结果已返回。" }]]
      : [];
    messages.push(["done", { status: "COMPLETED" }]);
    await route.fulfill({ contentType: "text/event-stream", body: sse(messages) });
  });
  await ask(page, "检查农场");
  await expect(page.getByText("正在读取现场信息。")).toBeVisible();
  await page.getByPlaceholder(composerPlaceholder).fill("补充检查当前地块");
  await page.getByRole("button", { name: "加入队列" }).click();
  await expect(page.getByText("已加入本次任务队列，当前工作完成后处理。")).toBeVisible();
  await page.getByRole("button", { name: "停止生成" }).click();
  await expect.poll(() => operations).toEqual(["followUp", "abort"]);
  await page.getByRole("button", { name: "继续执行" }).click();
  await expect(page.getByText("续跑后的结果已返回。")).toBeVisible();
  expect(operations).toEqual(["followUp", "abort", "continue"]);
});

test("ordered message.part protocol keeps IDs, upserts keyed blocks, opens citations and ignores private reasoning", async ({ page }) => {
  await openWorkspace(page);
  let routeCalls = 0;
  await page.route("**/api/copilot/route", (route) => {
    routeCalls += 1;
    return route.fulfill({ json: { ...routeEvent[1] as Record<string, unknown>, traceId: "unexpected-route", confidence: 1 } });
  });
  await page.route("**/api/copilot/chat", (route) => route.fulfill({
    contentType: "text/event-stream",
    body: sse([
      routeEvent,
      ["message.part", { type: "text", id: "text-1", delta: "先检查果园。" }],
      ["message.part", { type: "thinking", id: "thinking-1", status: "running", label: "正在读取地块记录。" }],
      ["message.part", { type: "tool_call", callId: "tool-1", toolName: "read_field_context", label: "核对地块与物候", status: "running" }],
      ["message.part", { type: "tool_call", callId: "tool-1", toolName: "read_field_context", label: "核对地块与物候", status: "done" }],
      ["message.part", { type: "ui_block", blockKey: "FarmDirectoryCard:farm:7", block: { ...block, evidenceIds: ["E2"] }, citations: [{
        index: 2,
        evidenceId: "E2",
        modality: "atlas",
        title: "病虫害图鉴证据",
        sourceName: "病虫害图鉴",
        snippet: "图鉴中的标准症状描述。",
      }] }],
      ["ui.block.upserted", { blockKey: "FarmDirectoryCard:farm:7", block: { ...block, title: "相关农场（已更新）", evidenceIds: ["E2"] } }],
      ["message.part", { type: "text", id: "text-2", delta: "[1] 建议立即巡园并记录病果。", citations: [{
        index: 1,
        evidenceId: "E1",
        modality: "knowledge",
        title: "脆李褐腐病防治",
        sourceName: "农业知识库",
        documentName: "脆李病虫害防治手册",
        snippet: "及时清除病果并改善通风。",
        score: 0.86,
        expertStatus: "已审核",
      }] }],
      ["message.part", { type: "follow_ups", items: [{ label: "查看天气", prompt: "查看当前地块天气" }] }],
      ["message.part", { type: "reasoning", id: "private-1", text: "PRIVATE_REASONING_MUST_NOT_RENDER" }],
      ["reasoning.delta", { delta: "PRIVATE_EVENT_MUST_NOT_RENDER" }],
      ["done", { status: "COMPLETED" }],
    ]),
  }));

  await ask(page, "脆李褐腐病如何防治？");

  const answer = page.locator(".farmap-copilot__message--assistant").last();
  await expect(answer.getByText("先检查果园。", { exact: true })).toBeVisible();
  await expect(answer.getByText("建议立即巡园并记录病果。")).toBeVisible();
  await expect(answer.locator('[data-part-kind="tool_call"]')).toHaveCount(1);
  await expect(answer.locator('[data-part-kind="tool_call"]')).toContainText("已完成");
  await expect(answer.locator('[data-part-kind="thinking"]')).toContainText("正在读取地块记录。");
  await expect(answer.locator('[data-block-type="FarmDirectoryCard"]')).toHaveCount(1);
  await expect(answer.locator('[data-block-type="FarmDirectoryCard"]')).toContainText("相关农场（已更新）");
  await expect(answer.locator('[data-part-kind="follow_ups"]')).toContainText("查看天气");
  await expect(answer.locator(".farmap-response__followups")).toHaveCount(1);
  await expect(answer).not.toContainText("PRIVATE_REASONING_MUST_NOT_RENDER");
  await expect(answer).not.toContainText("PRIVATE_EVENT_MUST_NOT_RENDER");
  await expect(answer.locator('[data-part-kind="reasoning"]')).toHaveCount(0);

  const order = await answer.locator(".farmap-message-parts").evaluate((node) => Array.from(
    node.querySelectorAll<HTMLElement>("[data-part-kind], [data-block-type]"),
  ).map((element) => element.dataset.partKind ?? `block:${element.dataset.blockType}`));
  expect(order).toEqual(["text", "thinking", "tool_call", "block:FarmDirectoryCard", "text", "follow_ups"]);

  const evidenceDrawer = page.locator(".farmap-copilot-evidence-drawer");
  await answer.locator('[data-block-type="FarmDirectoryCard"]').getByRole("button", { name: "查看证据 E2" }).click();
  await expect(evidenceDrawer).toContainText("病虫害图鉴证据");
  await evidenceDrawer.locator(".ant-drawer-close").click();
  await expect(evidenceDrawer).toBeHidden();

  await answer.getByRole("button", { name: "查看证据 E1" }).click();
  await expect(evidenceDrawer.getByText("证据溯源")).toBeVisible();
  await expect(evidenceDrawer).toContainText("脆李褐腐病防治");
  await expect(evidenceDrawer).toContainText("脆李病虫害防治手册");
  await expect(evidenceDrawer).toContainText("86%");
  await expect(evidenceDrawer).toContainText("及时清除病果并改善通风。");
  expect(routeCalls).toBe(0);
});

test("legacy-only SSE keeps its completed answer and cards visible", async ({ page }) => {
  await openWorkspace(page);
  let routeCalls = 0;
  await page.route("**/api/copilot/route", (route) => {
    routeCalls += 1;
    return route.fulfill({ json: { ...routeEvent[1] as Record<string, unknown>, traceId: "unexpected-route", confidence: 1 } });
  });
  await page.route("**/api/copilot/chat", (route) => route.fulfill({
    contentType: "text/event-stream",
    body: sse([
      routeEvent,
      ["content.delta", { delta: "旧版流式回答仍然可用。" }],
      ["ui.block.created", block],
      ["done", { status: "COMPLETED" }],
    ]),
  }));

  await ask(page, "农场状态不好是什么原因？");

  const answer = page.locator(".farmap-copilot__message--assistant").last();
  await expect(answer.getByText("旧版流式回答仍然可用。", { exact: true })).toBeVisible();
  await expect(answer.locator('[data-block-type="FarmDirectoryCard"]')).toContainText("巫山果园");
  expect(routeCalls).toBe(0);
});

test("legacy kind-based message parts normalize into the ordered protocol", async ({ page }) => {
  await openWorkspace(page);
  await page.route("**/api/copilot/chat", (route) => route.fulfill({
    contentType: "text/event-stream",
    body: sse([
      routeEvent,
      ["message.part", { id: "legacy-text", kind: "text", delta: "[E1] 旧版零件仍可读取。" }],
      ["message.part", { id: "legacy-tool", kind: "tool", toolName: "read_field_context", status: "completed" }],
      ["message.part", { id: "legacy-activity", kind: "activity", text: "正在核对旧版地块记录。" }],
      ["message.part", { id: "legacy-block", kind: "block", block }],
      ["message.part", { id: "legacy-evidence", kind: "evidence", evidenceId: "E1", title: "旧版知识证据", summary: "旧版证据正文。", source: "农业知识库" }],
      ["done", { status: "COMPLETED" }],
    ]),
  }));

  await ask(page, "旧版协议是否兼容？");

  const answer = page.locator(".farmap-copilot__message--assistant").last();
  await expect(answer.getByText("旧版零件仍可读取。")).toBeVisible();
  await expect(answer.locator('[data-part-kind="tool_call"]')).toContainText("已完成");
  await expect(answer.locator('[data-part-kind="thinking"]')).toContainText("正在核对旧版地块记录。");
  await expect(answer.locator('[data-block-type="FarmDirectoryCard"]')).toContainText("巫山果园");
  await answer.getByRole("button", { name: "查看证据 E1" }).click();
  await expect(page.locator(".farmap-copilot-evidence-drawer")).toContainText("旧版知识证据");
});

test("a draft-task card action posts one structured pending-approval draft", async ({ page }) => {
  await openWorkspace(page);
  const run = {
    runId: "draft-run-1",
    goal: "确认组件农事草稿",
    mode: "real",
    context: { farmId: "7", fieldId: "field-7", imageUrls: [], weather: {}, diagnosis: {}, sourceModes: {} },
    status: "WAITING_FOR_APPROVAL",
    steps: [],
    toolCalls: [],
    evidence: [],
    output: {},
    actions: [{ id: "draft-action-1", type: "CREATE_TASK", title: "巡园检查", detail: "检查褐腐病果并记录。", toolName: "create_operations_task", approvalRequired: true, approvalState: "REQUIRED" }],
    approvalState: "REQUIRED",
    traces: [],
    metadata: {},
    createdAt: "2026-11-27T00:00:00Z",
    updatedAt: "2026-11-27T00:00:00Z",
  };
  let draftPayload: Record<string, unknown> | undefined;
  let draftCalls = 0;
  let approveCalls = 0;
  await page.route("**/api/copilot/task-drafts", async (route) => {
    draftCalls += 1;
    draftPayload = route.request().postDataJSON() as Record<string, unknown>;
    await route.fulfill({ json: run });
  });
  await page.route("**/api/agent/runs/draft-run-1", (route) => route.fulfill({ json: run }));
  await page.route("**/api/agent/runs/draft-run-1/events", (route) => route.fulfill({
    contentType: "text/event-stream",
    body: sse([["done", { status: "WAITING_FOR_APPROVAL" }]]),
  }));
  await page.route("**/api/agent/runs/**/approve", (route) => {
    approveCalls += 1;
    return route.fulfill({ json: run });
  });
  const taskBlock = {
    ...block,
    blockId: "draft-task-block",
    title: "巡园建议",
    data: { ...block.data, fieldId: "field-7" },
    actions: [{
      type: "draft-task",
      label: "生成任务草稿",
      draftTask: {
        fieldId: "field-7",
        title: "巡园检查",
        detail: "检查褐腐病果并记录。",
        scheduledFor: "2026-12-01",
      },
    }],
  };
  await page.route("**/api/copilot/chat", (route) => route.fulfill({
    contentType: "text/event-stream",
    body: sse([
      routeEvent,
      ["message.part", { type: "ui_block", blockKey: "FarmDirectoryCard:draft:7", block: taskBlock }],
      ["done", { status: "COMPLETED" }],
    ]),
  }));

  await ask(page, "为巡园生成任务草稿");
  await page.getByRole("button", { name: "生成任务草稿" }).click();

  await expect.poll(() => draftCalls).toBe(1);
  expect(draftPayload).toEqual({
    fieldId: "field-7",
    title: "巡园检查",
    detail: "检查褐腐病果并记录。",
    scheduledFor: "2026-12-01",
  });
  await expect(page.getByText("多模态农业 Agent", { exact: true })).toBeVisible();
  expect(approveCalls).toBe(0);
});

test("a zero-event transport failure recovers through the route path without leaving a stale error bubble", async ({ page }) => {
  await openWorkspace(page);
  let routeCalls = 0;
  let answerCalls = 0;
  await page.route("**/api/copilot/chat", (route) => route.fulfill({
    status: 503,
    contentType: "application/json",
    body: JSON.stringify({ message: "Copilot 对话连接失败（503）。" }),
  }));
  await page.route("**/api/copilot/route", (route) => {
    routeCalls += 1;
    return route.fulfill({ json: { ...routeEvent[1] as Record<string, unknown>, traceId: "fallback-route", confidence: 1 } });
  });
  await page.route("**/api/copilot/answer", (route) => {
    answerCalls += 1;
    return route.fulfill({ json: { data: { answer: "备用路径回答完成。", model: "e2e-model", grounded: true } } });
  });

  await ask(page, "果园应该怎么排水？");

  await expect(page.getByText("备用路径回答完成。", { exact: true })).toBeVisible();
  expect(routeCalls).toBe(1);
  expect(answerCalls).toBe(1);
  await expect(page.locator(".farmap-copilot__message--assistant")).not.toContainText("Copilot 对话连接失败（503）");
  await expect(page.locator(".farmap-message-parts__error")).toHaveCount(0);
});

test("a server stream error stays visible after partial ordered text", async ({ page }) => {
  await openWorkspace(page);
  let routeCalls = 0;
  await page.route("**/api/copilot/route", (route) => {
    routeCalls += 1;
    return route.fulfill({ json: { ...routeEvent[1] as Record<string, unknown>, traceId: "unexpected-route", confidence: 1 } });
  });
  await page.route("**/api/copilot/chat", (route) => route.fulfill({
    contentType: "text/event-stream",
    body: sse([
      routeEvent,
      ["message.part", { type: "text", id: "partial-text", delta: "已读取部分真实结果。" }],
      ["error", { message: "模型中断，部分结果已保留。" }],
    ]),
  }));

  await ask(page, "继续分析当前果园。");

  const answer = page.locator(".farmap-copilot__message--assistant").last();
  await expect(answer.getByText("已读取部分真实结果。", { exact: true })).toBeVisible();
  await expect(answer.getByRole("alert")).toContainText("模型中断，部分结果已保留。");
  expect(routeCalls).toBe(0);
});
