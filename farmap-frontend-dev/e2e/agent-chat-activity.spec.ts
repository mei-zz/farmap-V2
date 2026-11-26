import { expect, test, type Page } from "@playwright/test";

async function openSignedInWorkspace(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem("token", "chat-activity-test");
    localStorage.setItem("user", JSON.stringify({ username: "测试账号", role: "user", farms: [{ id: 42, name: "测试果园" }] }));
  });
  await page.route("**/user/validate-token", (route) => route.fulfill({ json: { data: { isValid: true, isExpired: false } } }));
  await page.route("**/api/farms/42/**", (route) => route.fulfill({ json: { data: [] } }));
  await page.route("**/api/copilot/memories**", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/copilot/conversations?**", (route) => route.fulfill({ json: [] }));
}

test("signed-in chat displays runtime activity with its answer and keeps the composer visible", async ({ page }) => {
  await openSignedInWorkspace(page);
  const response: [string, unknown][] = [
    ["route", { conversationId: "activity-test", contextVersion: 1, intent: "GENERAL_QA", resolutionStatus: "RESOLVED" }],
    ["agent.runtime", { type: "thinking_start" }],
    ["agent.runtime", { type: "thinking_end" }],
    ["agent.runtime", { type: "tool_execution_start", toolName: "read_knowledge", toolCallId: "call_1" }],
    ["tool.started", { toolName: "read_knowledge", toolCallId: "call_1" }],
    ["tool.completed", { toolName: "read_knowledge", toolCallId: "call_1" }],
    ["tool.started", { toolName: "web_search", toolCallId: "call_2" }],
    ["tool.completed", { toolName: "web_search", toolCallId: "call_2", isError: true }],
    ["content.delta", { delta: `${Array.from({ length: 40 }, (_, i) => `管理建议 ${i + 1}`).join("\n\n")}\n\n最后一条建议` }],
    ["done", { status: "COMPLETED" }],
  ];
  await page.route("**/api/copilot/chat", (route) => route.fulfill({
    contentType: "text/event-stream",
    body: response.map(([name, value]) => `event: ${name}\ndata: ${JSON.stringify(value)}\n\n`).join(""),
  }));

  await page.goto("workspace");
  const composer = page.locator(".farmap-copilot__composer");
  await composer.locator("textarea").fill("怎样进行果园灌溉？");
  await composer.getByRole("button", { name: "发送消息" }).click();
  const question = page.locator(".farmap-copilot__message--user");
  const answer = page.locator(".farmap-copilot__message--assistant").filter({ hasText: "最后一条建议" });
  await expect(answer.getByText("最后一条建议")).toBeInViewport();

  const transcript = page.locator(".farmap-copilot__transcript");
  await transcript.evaluate((node) => { node.scrollTop = 0; });
  const jump = page.getByRole("button", { name: "回到最新消息" });
  await expect(jump).toBeVisible();
  const jumpBounds = (await jump.boundingBox())!;
  const transcriptBounds = (await transcript.boundingBox())!;
  expect(jumpBounds.y).toBeGreaterThanOrEqual(transcriptBounds.y + transcriptBounds.height - 1);
  const questionBounds = (await question.boundingBox())!;
  const answerBounds = (await answer.boundingBox())!;
  expect(questionBounds.x).toBeGreaterThan(answerBounds.x);
  expect(Math.abs(questionBounds.x + questionBounds.width - answerBounds.x - answerBounds.width)).toBeLessThan(2);
  await answer.getByText("执行过程").click();
  await expect(answer.getByText("模型思考")).toBeVisible();
  await expect(answer.getByText("检索农业知识库")).toHaveCount(1);
  await expect(answer.getByRole("listitem").filter({ hasText: "网页搜索" })).toContainText("失败");
  await expect(composer).toBeInViewport();

  await jump.click();
  await expect(answer.getByText("最后一条建议")).toBeInViewport();
  await expect(jump).toHaveCount(0);
});

test("knowledge retrieval appears only as a count before and after the streamed answer", async ({ page }) => {
  await openSignedInWorkspace(page);
  const legacyBlock = {
    blockId: "knowledge-card", runId: "knowledge-count-test", componentType: "EvidenceCard",
    schemaVersion: 1, title: "知识依据", status: "ready", data: { items: [{ label: "来源", value: "knowledge-version-internal-id" }] },
    createdAt: "2026-11-21T00:00:00Z", updatedAt: "2026-11-21T00:00:00Z",
  };
  await page.route("**/api/copilot/conversations/knowledge-count-test", (route) => route.fulfill({ json: {
    conversationId: "knowledge-count-test", status: "ACTIVE", summaryVersion: 0, contextVersion: 2, turnCount: 2, recentTurns: [],
    historyTurns: [
      { turnId: "legacy", role: "ASSISTANT", message: "以前的回答", responseMetadata: { uiBlocks: [legacyBlock] } },
      { turnId: "current", role: "ASSISTANT", message: "观察果面是否形成霉层。", responseMetadata: { knowledgeCount: 5, knowledgeStatus: "READY" } },
    ],
  } }));
  await page.addInitScript((block) => {
    const originalFetch = window.fetch.bind(window);
    window.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      if (!url.endsWith("/api/copilot/chat")) return originalFetch(input, init);
      const stream = new ReadableStream<Uint8Array>({
        start(controller) {
          const send = (event: string, data: unknown) => controller.enqueue(new TextEncoder().encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`));
          send("route", { conversationId: "knowledge-count-test", contextVersion: 1, intent: "KNOWLEDGE_QUERY", resolutionStatus: "RESOLVED" });
          send("knowledge", { count: 5, status: "READY", items: [{ title: "私有知识片段", content: "内部原文" }] });
          send("response.meta", {
            responseType: "KNOWLEDGE",
            sources: [{ id: "knowledge-version-internal-id", title: "私有知识片段", quote: "内部原文" }],
            suggestions: [{ id: "reveal", text: "查看知识依据" }, { id: "field", text: "结合当前地块解释" }],
          });
          send("ui.block.created", { block });
          window.addEventListener("test-first-answer-token", () => {
            send("agent.runtime", { type: "turn_start" });
            send("content.delta", { delta: "褐腐病常引起果实褐变。" });
          }, { once: true });
          window.addEventListener("test-last-answer-token", () => {
            send("content.delta", { delta: "\n\n观察果面是否形成霉层。" });
            send("done", { status: "COMPLETED" });
            controller.close();
          }, { once: true });
        },
      });
      return new Response(stream, { headers: { "Content-Type": "text/event-stream" } });
    }) as typeof window.fetch;
  }, legacyBlock);

  await page.goto("workspace");
  const composer = page.locator(".farmap-copilot__composer");
  await composer.locator("textarea").fill("褐腐病是什么样的？");
  await composer.getByRole("button", { name: "发送消息" }).click();
  const answer = page.locator(".farmap-copilot__message--assistant");
  const count = answer.locator(".farmap-chat-activity").getByText("知识库返回 5 条信息", { exact: true });
  await expect(count).toBeVisible();
  await expect(answer.locator(".farmap-copilot__markdown-message")).toHaveCount(0);
  await expect(answer.locator('[data-block-type="EvidenceCard"]')).toHaveCount(0);

  await page.evaluate(() => window.dispatchEvent(new Event("test-first-answer-token")));
  await expect(answer.getByText("褐腐病常引起果实褐变。")).toBeVisible();
  await expect(count).toBeVisible();
  await page.evaluate(() => window.dispatchEvent(new Event("test-last-answer-token")));
  await expect(answer.getByText("观察果面是否形成霉层。")).toBeVisible();
  await expect(composer.getByRole("button", { name: "发送消息" })).toBeVisible();
  await expect(count).toHaveCount(1);
  await expect(answer).not.toContainText(/知识依据|结合当前地块解释|私有知识片段|内部原文|knowledge-version-internal-id/);

  await page.reload();
  await expect(answer.filter({ hasText: "观察果面是否形成霉层。" })).toContainText("知识库返回 5 条信息");
  const oldAnswer = answer.filter({ hasText: "以前的回答" });
  await expect(oldAnswer).toContainText("知识库返回 1 条信息");
  await expect(oldAnswer).not.toContainText(/知识依据|knowledge-version-internal-id/);
});
