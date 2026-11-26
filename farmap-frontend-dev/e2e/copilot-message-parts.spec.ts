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
      ["message.part", { id: "partial", kind: "text", delta: "仍在查找资料…" }],
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
            + 'event: message.part\ndata: {"kind":"text","id":"first-part","delta":"正在读取现场信息。"}\n\n'));
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
      ? [["message.part", { id: "continued", kind: "text", delta: "续跑后的结果已返回。" }]]
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

test("ordered message.part protocol renders text, tools, activity, blocks and clickable evidence without private reasoning", async ({ page }) => {
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
      ["message.part", { id: "text-1", kind: "text", delta: "先检查果园。" }],
      ["message.part", { id: "tool-1", kind: "tool", toolName: "read_field_context", status: "running" }],
      ["message.part", { id: "activity-1", kind: "activity", text: "正在读取地块记录。" }],
      ["message.part", { id: "tool-1", kind: "tool", toolName: "read_field_context", status: "completed" }],
      ["message.part", { id: "text-2", kind: "text", delta: "[E1] 建议立即巡园并记录病果。" }],
      ["message.part", { id: "block-1", kind: "block", block }],
      ["message.part", { id: "evidence-1", kind: "evidence", evidenceId: "E1", title: "脆李褐腐病防治", summary: "及时清除病果并改善通风。", source: "农业知识库" }],
      ["message.part", { id: "private-1", kind: "reasoning", text: "PRIVATE_REASONING_MUST_NOT_RENDER" }],
      ["reasoning.delta", { delta: "PRIVATE_EVENT_MUST_NOT_RENDER" }],
      ["done", { status: "COMPLETED" }],
    ]),
  }));

  await ask(page, "脆李褐腐病如何防治？");

  const answer = page.locator(".farmap-copilot__message--assistant").last();
  await expect(answer.getByText("先检查果园。", { exact: true })).toBeVisible();
  await expect(answer.getByText("[E1] 建议立即巡园并记录病果。", { exact: true })).toBeVisible();
  await expect(answer.locator('[data-part-kind="tool"]')).toContainText("已完成");
  await expect(answer.locator('[data-part-kind="activity"]')).toContainText("正在读取地块记录。");
  await expect(answer.locator('[data-block-type="FarmDirectoryCard"]')).toContainText("巫山果园");
  await expect(answer.locator('[data-part-kind="evidence"]')).toContainText("脆李褐腐病防治");
  await expect(answer).not.toContainText("PRIVATE_REASONING_MUST_NOT_RENDER");
  await expect(answer).not.toContainText("PRIVATE_EVENT_MUST_NOT_RENDER");
  await expect(answer.locator('[data-part-kind="reasoning"]')).toHaveCount(0);

  const order = await answer.locator(".farmap-message-parts").evaluate((node) => Array.from(
    node.querySelectorAll<HTMLElement>("[data-part-kind], [data-block-type]"),
  ).map((element) => element.dataset.partKind ?? `block:${element.dataset.blockType}`));
  expect(order).toEqual(["text", "tool", "activity", "text", "block:FarmDirectoryCard", "evidence"]);

  await answer.getByRole("button", { name: "查看证据 E1" }).click();
  const evidence = answer.locator('[data-evidence-id="E1"]');
  await expect(evidence).toHaveClass(/is-active/);
  await expect(evidence).toBeFocused();
  expect(routeCalls).toBe(0);
});

test("legacy-only SSE remains compatible when no message.part events arrive", async ({ page }) => {
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
  await expect(answer.locator(".farmap-message-parts")).toHaveCount(0);
  expect(routeCalls).toBe(0);
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
      ["message.part", { id: "partial-text", kind: "text", delta: "已读取部分真实结果。" }],
      ["error", { message: "模型中断，部分结果已保留。" }],
    ]),
  }));

  await ask(page, "继续分析当前果园。");

  const answer = page.locator(".farmap-copilot__message--assistant").last();
  await expect(answer.getByText("已读取部分真实结果。", { exact: true })).toBeVisible();
  await expect(answer.getByRole("alert")).toContainText("模型中断，部分结果已保留。");
  expect(routeCalls).toBe(0);
});
