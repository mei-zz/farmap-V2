import { expect, test } from "@playwright/test";

test("agent workspace routes, streams, restores and fits a small viewport", async ({ page }) => {
  const block = { blockId: "weather-1", runId: "run-1", componentType: "WeatherCard", schemaVersion: 1, title: "当前天气", status: "ready", data: { metrics: [{ label: "气温", value: 26, unit: "°C" }] }, actions: [], provenance: [{ label: "气象服务" }], createdAt: "2026-11-20T00:00:00Z", updatedAt: "2026-11-20T00:00:00Z" };
  const turns = [{ turnId: "user-1", role: "USER", message: "今天天气如何？" }, { turnId: "assistant-1", role: "ASSISTANT", message: "当前天气多云。", responseMetadata: { uiBlocks: [block] } }];
  await page.addInitScript(() => {
    localStorage.setItem("token", "workspace-test-token");
    localStorage.setItem("user", JSON.stringify({ username: "测试用户", role: "user", farms: [{ id: 1, name: "测试农场" }] }));
  });
  await page.route("**/user/validate-token", route => route.fulfill({ json: { data: { isExpired: false } } }));
  await page.route("**/api/farms/1/fields", route => route.fulfill({ json: { data: [{ fieldId: "field-1", name: "一号地块", crop: "柑橘", area: 2 }] } }));
  await page.route("**/api/farms/1/current-weather", route => route.fulfill({ json: { data: { farmId: 1, status: "READY", condition: "多云", temperature: "26" } } }));
  await page.route("**/api/copilot/memories**", route => route.fulfill({ json: [] }));
  await page.route("**/api/copilot/conversations?**", route => route.fulfill({ json: [] }));
  await page.route("**/api/copilot/conversations/conversation-1", route => route.fulfill({ json: { conversationId: "conversation-1", status: "ACTIVE", contextVersion: 1, summaryVersion: 0, turnCount: 2, recentTurns: turns, historyTurns: turns } }));
  await page.route("**/api/copilot/chat", route => route.fulfill({ status: 200, contentType: "text/event-stream", body: [
    `event: route\ndata: ${JSON.stringify({ conversationId: "conversation-1", contextVersion: 1, intent: "GENERAL_QA", resolutionStatus: "RESOLVED" })}\n\n`,
    `event: content.delta\ndata: ${JSON.stringify({ delta: "当前天气多云。" })}\n\n`,
    `event: ui.block.created\ndata: ${JSON.stringify({ block })}\n\n`,
    `event: ui.block.created\ndata: ${JSON.stringify({ ...block, blockId: "invalid-1", componentType: "UnsafeHtml", title: "未登记组件" })}\n\n`,
    'event: done\ndata: {"status":"COMPLETED"}\n\n',
  ].join("") }));

  await page.goto("");
  await expect(page).toHaveURL(/\/workspace$/);
  await expect(page.getByRole("heading", { name: "你的农事智能助手" })).toBeVisible();
  for (const prompt of ["查看农场情况", "作物问题诊断", "制定农事计划", "农业气象分析"]) await expect(page.getByRole("button", { name: prompt })).toBeVisible();
  await expect(page.locator(".farmap-agent-launcher, .ant-drawer" )).toHaveCount(0);
  const welcome = await page.locator(".farmap-copilot-workspace__welcome").boundingBox();
  const workspace = await page.locator(".farmap-copilot-workspace").boundingBox();
  expect(welcome && workspace && Math.abs((welcome.x + welcome.width / 2) - (workspace.x + workspace.width / 2))).toBeLessThan(10);
  await page.screenshot({ path: "test-results/agent-workspace-desktop.png" });

  await page.getByPlaceholder("描述你的农事需求，或上传作物图片……").fill("今天天气如何？");
  await page.getByRole("button", { name: "发送" }).click();
  await expect(page.getByText("当前天气多云。", { exact: true })).toBeVisible();
  await expect(page.locator('[data-block-type="WeatherCard"]')).toContainText("26");
  await expect(page.getByText("未登记组件")).toHaveCount(0);
  await page.screenshot({ path: "test-results/agent-workspace-conversation.png" });
  await page.reload();
  await expect(page.locator('[data-block-type="WeatherCard"]')).toContainText("26");
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.locator('[data-block-type="WeatherCard"]')).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: "test-results/agent-workspace-mobile.png" });
});

test("farm plan execution waits for approval in the conversation", async ({ page }) => {
  const stamp = "2026-11-20T00:00:00Z";
  const runId = "agent-test-1";
  const planBlock = { blockId: "plan-1", runId: "chat-plan-1", componentType: "FarmPlanCard", schemaVersion: 1,
    title: "农事计划", status: "needs-input", data: { runId }, actions: [], provenance: [{ label: "农事工作流" }], createdAt: stamp, updatedAt: stamp };
  const run = {
    runId, goal: "安排地块巡查", mode: "real", status: "WAITING_FOR_APPROVAL", approvalState: "REQUIRED",
    context: { imageUrls: [], weather: {}, diagnosis: {}, sourceModes: {} }, evidence: [], output: {}, metadata: {}, traces: [], toolCalls: [],
    steps: [{ id: "draft", title: "准备巡查计划", status: "COMPLETED" }],
    actions: [{ id: "approval-1", title: "巡查一号地块", detail: "检查作物情况并记录。", approvalState: "REQUIRED", approvalRequired: true }],
    createdAt: stamp, updatedAt: stamp,
  };
  let approvals = 0;
  await page.addInitScript(() => {
    localStorage.setItem("token", "workspace-test-token");
    localStorage.setItem("user", JSON.stringify({ username: "测试用户", role: "user", farms: [{ id: 1, name: "测试农场" }] }));
  });
  await page.route("**/user/validate-token", route => route.fulfill({ json: { data: { isExpired: false } } }));
  await page.route("**/api/farms/1/fields", route => route.fulfill({ json: { data: [{ fieldId: "field-1", name: "一号地块" }] } }));
  await page.route("**/api/farms/1/current-weather", route => route.fulfill({ json: { data: { status: "UNAVAILABLE" } } }));
  await page.route("**/api/copilot/memories**", route => route.fulfill({ json: [] }));
  await page.route("**/api/copilot/chat", route => route.fulfill({ contentType: "text/event-stream", body: [
    `event: route\ndata: ${JSON.stringify({ conversationId: "plan-conversation", contextVersion: 1, intent: "ACTION_REQUEST", resolutionStatus: "RESOLVED" })}\n\n`,
    `event: ui.block.created\ndata: ${JSON.stringify(planBlock)}\n\n`,
    'event: done\ndata: {"status":"RUN_CREATED"}\n\n',
  ].join("") }));
  const completed = { ...run, status: "COMPLETED", approvalState: "APPROVED", actions: [] };
  await page.route(`**/api/agent/runs/${runId}`, route => route.fulfill({ json: approvals ? completed : run }));
  await page.route(`**/api/agent/runs/${runId}/actions/approval-1/approve`, route => {
    expect(route.request().method()).toBe("POST");
    approvals++;
    return route.fulfill({ json: completed });
  });
  await page.goto("workspace");
  await page.getByPlaceholder("描述你的农事需求，或上传作物图片……").fill("安排一号地块巡查");
  await page.getByRole("button", { name: "发送" }).click();
  const plan = page.locator('[data-block-type="FarmPlanCard"]');
  await expect(plan).toContainText("等待确认");
  expect(approvals).toBe(0);
  await plan.getByRole("button", { name: "确认并执行" }).click();
  await expect(plan).toContainText("已完成");
  expect(approvals).toBe(1);
});

test("clarification selects an authorized field and continues the original question", async ({ page }) => {
  page.setDefaultTimeout(12_000);
  const prompt = "给地块制定农事计划";
  const stamp = "2026-11-20T00:00:00Z";
  const block = { blockId: "scope-form", runId: "chat-form", componentType: "ClarificationForm", schemaVersion: 1,
    title: "补充信息", status: "needs-input", actions: [], provenance: [], createdAt: stamp, updatedAt: stamp,
    data: { originalRequest: prompt, fields: [{ id: "fieldId", label: "选择地块", required: true,
      options: [{ label: "二号地块", value: "field-2" }] }] } };
  type ChatRequest = { message: string; conversationId?: string; pageContext: { fieldId?: string } };
  const sent: ChatRequest[] = [];
  await page.addInitScript(() => {
    localStorage.setItem("token", "workspace-test-token");
    localStorage.setItem("user", JSON.stringify({ username: "测试用户", role: "user", farms: [{ id: 1, name: "测试农场" }] }));
  });
  await page.route("**/user/validate-token", route => route.fulfill({ json: { data: { isExpired: false } } }));
  await page.route("**/api/farms/1/fields", route => route.fulfill({ json: { data: [
    { fieldId: "field-1", name: "一号地块" }, { fieldId: "field-2", name: "二号地块" },
  ] } }));
  await page.route("**/api/farms/1/current-weather", route => route.fulfill({ json: { data: { status: "UNAVAILABLE" } } }));
  await page.route("**/api/copilot/memories**", route => route.fulfill({ json: [] }));
  await page.route("**/api/copilot/chat", route => {
    sent.push(route.request().postDataJSON() as ChatRequest);
    const first = sent.length === 1;
    return route.fulfill({ contentType: "text/event-stream", body: [
      `event: route\ndata: ${JSON.stringify({ conversationId: "form-conversation", contextVersion: sent.length, intent: "ACTION_REQUEST", resolutionStatus: first ? "CLARIFICATION_REQUIRED" : "RESOLVED" })}\n\n`,
      first ? `event: ui.block.created\ndata: ${JSON.stringify(block)}\n\n` : 'event: content.delta\ndata: {"delta":"开始处理二号地块。"}\n\n',
      'event: done\ndata: {"status":"COMPLETED"}\n\n',
    ].join("") });
  });
  await page.goto("workspace");
  await page.getByPlaceholder("描述你的农事需求，或上传作物图片……").fill(prompt);
  await page.getByRole("button", { name: "发送" }).click();
  await page.getByLabel("选择地块", { exact: true }).selectOption("field-2");
  await page.getByRole("button", { name: "提交并继续" }).click();
  await expect(page.getByText("开始处理二号地块。", { exact: true })).toBeVisible();
  expect(sent[1].message).toContain(prompt);
  expect(sent[1].message).toContain("二号地块");
  expect(sent[1].conversationId).toBe("form-conversation");
  expect(sent[1].pageContext.fieldId).toBe("field-2");
});
