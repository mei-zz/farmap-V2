import { expect, test, type Page } from "@playwright/test";

const composerPlaceholder = "描述你的农事需求，或上传作物图片……";

function sse(events: Array<[string, unknown]>): string {
  return events.map(([event, data]) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`).join("");
}

async function openWorkspace(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem("token", "memory-navigation-e2e");
    localStorage.setItem("user", JSON.stringify({ username: "测试账号", role: "user", farms: [{ id: 7, name: "巫山果园" }] }));
  });
  await page.route("**/user/validate-token", (route) => route.fulfill({ json: { data: { isValid: true, isExpired: false } } }));
  await page.route("**/api/farms/7/**", (route) => route.fulfill({ json: { data: [] } }));
  await page.route("**/api/copilot/conversations?**", (route) => route.fulfill({ json: [] }));
  await page.goto("workspace");
  await expect(page.getByPlaceholder(composerPlaceholder)).toBeVisible();
}

async function ask(page: Page, prompt: string) {
  await page.getByPlaceholder(composerPlaceholder).fill(prompt);
  await page.getByRole("button", { name: "发送消息" }).click();
}

function memoryCard(status: "ready" | "completed", items: Array<{ label: string; value: string }>, id = "memory-preference") {
  return {
    schemaVersion: 1,
    blockId: id,
    runId: "memory-navigation-run",
    componentType: "MemoryPreferenceCard",
    title: status === "completed" ? "已保存长期偏好" : "已保存的长期记忆",
    status,
    data: { items },
    actions: [],
    provenance: [],
    createdAt: "2026-11-28T00:00:00Z",
    updatedAt: "2026-11-28T00:00:00Z",
  };
}

test("a resolved memory route refreshes once while later chunks and duplicate routes are ignored", async ({ page }) => {
  let memoryReads = 0;
  let memoryItems: unknown[] = [];
  await page.route("**/api/copilot/memories**", (route) => {
    memoryReads += 1;
    return route.fulfill({ json: memoryItems });
  });
  await openWorkspace(page);
  await expect.poll(() => memoryReads).toBeGreaterThan(0);
  await page.waitForTimeout(250);
  const baseline = memoryReads;

  memoryItems = [{
    memoryId: "memory-pref-1",
    scope: "USER",
    type: "COMMUNICATION_PREFERENCE",
    key: "pesticide_risk",
    value: "低农药风险",
    displayText: "更关注低农药风险",
    status: "ACTIVE",
  }];
  const routeEvent = {
    conversationId: "memory-route-conversation",
    contextVersion: 1,
    traceId: "memory-route-trace",
    intent: "MEMORY_WRITE_REQUEST",
    resolutionStatus: "RESOLVED",
    memoryStatus: "ACTIVE",
    memoryId: "memory-pref-1",
  };
  await page.route("**/api/copilot/chat", (route) => route.fulfill({
    contentType: "text/event-stream",
    body: sse([
      ["route", routeEvent],
      ["presentation.summary", { text: "已保存长期偏好。" }],
      ["ui.block.created", memoryCard("completed", [{ label: "偏好", value: "更关注低农药风险" }])],
      ["content.delta", { delta: "后续内容一。" }],
      ["content.delta", { delta: "后续内容二。" }],
      ["route", routeEvent],
      ["message.part", { type: "text", id: "memory-later", delta: "后续内容三。" }],
      ["done", { status: "COMPLETED" }],
    ]),
  }));

  await ask(page, "记住我更关注低农药风险");
  await expect(page.getByText("已保存长期偏好。", { exact: true })).toBeVisible();
  await expect(page.locator('[data-block-type="MemoryPreferenceCard"]').getByText("更关注低农药风险")).toBeVisible();
  await expect.poll(() => memoryReads).toBe(baseline + 1);
  await page.waitForTimeout(350);
  expect(memoryReads).toBe(baseline + 1);

  await expect(page.locator(".farmap-copilot__memory-row")).toContainText(["更关注低农药风险"]);
});

test("a Pi-direct memory.changed event refreshes once and uses the Chinese tool label", async ({ page }) => {
  let memoryReads = 0;
  let memoryItems: unknown[] = [];
  await page.route("**/api/copilot/memories**", (route) => {
    memoryReads += 1;
    return route.fulfill({ json: memoryItems });
  });
  await openWorkspace(page);
  await expect.poll(() => memoryReads).toBeGreaterThan(0);
  await page.waitForTimeout(250);
  const baseline = memoryReads;

  memoryItems = [{
    memoryId: "memory-pi-1",
    scope: "USER",
    type: "COMMUNICATION_PREFERENCE",
    key: "pesticide_risk",
    value: "优先低毒方案",
    displayText: "优先低毒方案",
    status: "ACTIVE",
  }];
  await page.route("**/api/copilot/chat", (route) => route.fulfill({
    contentType: "text/event-stream",
    body: sse([
      ["route", { conversationId: "memory-pi-conversation", contextVersion: 1, intent: "GENERAL_QA", resolutionStatus: "RESOLVED" }],
      ["agent.runtime", { type: "tool_execution_start", toolName: "manage_user_preference", toolCallId: "preference-call" }],
      ["memory.changed", { memoryId: "memory-pi-1", status: "SAVED" }],
      ["memory.changed", { memoryId: "memory-pi-1", status: "SAVED" }],
      ["agent.runtime", { type: "tool_execution_end", toolName: "manage_user_preference", toolCallId: "preference-call" }],
      ["message.part", { type: "text", id: "preference-answer", delta: "已保存你的偏好。" }],
      ["content.delta", { delta: "后续内容不会再次刷新记忆。" }],
      ["done", { status: "COMPLETED" }],
    ]),
  }));

  await ask(page, "记住我优先选择低毒方案");
  await expect(page.getByText("已保存你的偏好。", { exact: true })).toBeVisible();
  await expect.poll(() => memoryReads).toBe(baseline + 1);
  await page.waitForTimeout(350);
  expect(memoryReads).toBe(baseline + 1);
  await page.locator(".farmap-chat-activity summary").click();
  await expect(page.locator(".farmap-chat-activity li").filter({ hasText: "管理用户偏好" })).toBeVisible();
});

test("workspace navigation cards only open allowlisted routes and never auto-redirect", async ({ page }) => {
  await page.route("**/api/copilot/memories**", (route) => route.fulfill({ json: [] }));
  await openWorkspace(page);

  const destinations: Array<[string, string]> = [
    ["/operations", "农事任务"],
    ["/diagnosis", "农情诊断"],
    ["/analysis", "农场分析"],
    ["/knowledge", "农业知识"],
    ["/farm/map", "地块空间"],
    ["/overview", "农场总览"],
  ];
  const cards = destinations.map(([route, label], index) => ({
    schemaVersion: 1,
    blockId: `navigation-${index}`,
    runId: "navigation-run",
    componentType: "WorkspaceNavigationCard",
    title: label,
    status: "ready",
    data: { route },
    actions: [{ type: "open-detail", label: `打开${label}`, value: route }],
    provenance: [],
    createdAt: "2026-11-28T00:00:00Z",
    updatedAt: "2026-11-28T00:00:00Z",
  }));
  cards.push({
    ...cards[0],
    blockId: "navigation-forged",
    title: "伪造入口",
    data: { route: "https://evil.example/steal" },
    actions: [{ type: "open-detail", label: "伪造跳转", value: "https://evil.example/steal" }],
  });
  cards.push({
    ...cards[0],
    blockId: "navigation-forged-action",
    title: "伪造动作",
    data: { route: "/knowledge" },
    actions: [{ type: "open-detail", label: "外部跳转", value: "//evil.example/steal" }],
  });
  cards.push({
    ...cards[0],
    blockId: "navigation-unlisted",
    title: "未列出的页面",
    data: { route: "/planning" },
    actions: [{ type: "open-detail", label: "打开未列出的页面", value: "/planning" }],
  });

  await page.route("**/api/copilot/chat", (route) => route.fulfill({
    contentType: "text/event-stream",
    body: sse([
      ["route", { conversationId: "navigation-conversation", contextVersion: 1, intent: "NAVIGATION_REQUEST", resolutionStatus: "RESOLVED", safeRoute: "/knowledge" }],
      ["presentation.summary", { text: "可打开农业知识。" }],
      ...cards.map((card): [string, unknown] => ["ui.block.created", card]),
      ["done", { status: "COMPLETED" }],
    ]),
  }));

  await ask(page, "打开知识库");
  await expect(page.getByText("可打开农业知识。", { exact: true })).toBeVisible();
  await expect(page).toHaveURL(/\/farmap\/workspace/);
  for (const [, label] of destinations) {
    await expect(page.getByRole("button", { name: `打开${label}`, exact: true })).toBeVisible();
  }
  await expect(page.getByRole("button", { name: "伪造跳转", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "外部跳转", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "打开未列出的页面", exact: true })).toHaveCount(0);
  await expect(page.getByText("该页面入口未通过安全校验", { exact: false })).toHaveCount(3);

  await page.getByRole("button", { name: "打开农业知识", exact: true }).click();
  await expect(page).toHaveURL(/\/farmap\/knowledge/);
});
