import { expect, test } from "@playwright/test";

test("Copilot compatibility routing still serves explicit data queries when SSE is unavailable", async ({ page }) => {
  let historicalSearchCalled = false;

  await page.addInitScript(() => {
    localStorage.setItem("token", "copilot-command-bar-e2e-token");
    localStorage.setItem("user", JSON.stringify({ username: "copilot-command-bar-e2e", role: "user", farms: [{ id: 1, name: "云阳" }] }));
  });
  await page.route("**/user/validate-token", async (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: { isExpired: false } }) }));
  await page.route("**/api/farms/1/fields", async (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ data: [{ fieldId: "field-a12", farmId: 1, name: "A-12", crop: "柑橘", area: 2.4, growthStage: "花期" }] }),
  }));
  await page.route("**/api/farms/1/current-weather", async (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: { farmId: 1, status: "READY", condition: "多云", temperature: "26" } }) }));
  await page.route("**/api/farms/1/agricultural-context**", async (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ data: { farmId: 1, cropObservations: { status: "READY" }, historicalTemperature: { status: "READY" }, guidance: { status: "READY", entries: [] }, recentDiagnoses: { status: "READY", items: [{ diagnosisId: "diagnosis-a12", fieldId: "field-a12", crop: "柑橘", riskLevel: "high", summary: "花期异常风险", status: "COMPLETED", createdAt: "2026-09-08T10:00:00Z" }] }, currentWeather: { farmId: 1, status: "READY", condition: "多云", temperature: "26" } } }),
  }));
  await page.route("**/api/operations/tasks", async (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ data: { items: [{ taskId: "task-b03", title: "B-03 病虫害巡查", fieldId: "field-a12", status: "OPEN", priority: "HIGH", updatedAt: "2026-09-08T09:00:00Z" }] } }),
  }));
  await page.route("**/api/knowledge/search**", async (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ data: { status: "READY", retrievalProvider: "qwen-knowledge", results: [{ chunkId: "chunk-citrus-001", documentTitle: "柑橘花期管理规范", sourceName: "云阳脆李与柑橘知识库", score: 0.86, content: "花期高温时应关注土壤水分、授粉条件和高温时段作业安排。" }] } }),
  }));
  await page.route("**/api/copilot/route", async (route) => {
    const body = route.request().postDataJSON() as { message?: string };
    const historical = body.message?.includes("历史病例");
    const farmStatus = body.message?.includes("农场怎么样");
    const knowledge = body.message?.includes("高温怎么办");
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        intent: historical ? "HISTORICAL_CASE_QUERY" : farmStatus ? "FARM_STATUS_QUERY" : knowledge ? "KNOWLEDGE_QUERY" : "PRIORITIES_QUERY",
        resolutionStatus: "RESOLVED",
        resolvedContext: { farmId: "1", fieldId: "field-a12" },
        capability: historical ? "HISTORICAL_CASE_READ" : farmStatus ? "FARM_CONTEXT_READ" : knowledge ? "KNOWLEDGE_SEARCH" : "PRIORITIES_READ",
        requiresClarification: false,
        traceId: historical ? "trace-history" : "trace-priorities",
        confidence: 0.99,
      }),
    });
  });
  await page.route("**/api/copilot/answer", async (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ data: { answer: "已根据当前返回的业务信息整理。", model: "e2e-model", grounded: true } }),
  }));
  await page.route("**/api/copilot/chat", (route) => {
    const body = route.request().postDataJSON() as { message?: string };
    if (!body.message?.includes("高温怎么办")) return route.abort("failed");
    return route.fulfill({ contentType: "text/event-stream", body: [
      'event: route\ndata: {"intent":"KNOWLEDGE_QUERY","resolutionStatus":"RESOLVED"}\n\n',
      'event: knowledge\ndata: {"count":1,"status":"READY"}\n\n',
      'event: content.delta\ndata: {"delta":"花期高温时应关注土壤水分。"}\n\n',
      'event: done\ndata: {"status":"COMPLETED"}\n\n',
    ].join("") });
  });
  await page.route("**/api/historical-cases/search", async (route) => {
    historicalSearchCalled = true;
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: { status: "READY", items: [{ caseId: "CASE-REAL-001", similarityScore: 87.2 }] } }) });
  });

  await page.goto("workspace");
  await page.locator(".agent-shell__field-select").click();
  await page.locator(".ant-select-dropdown:visible .ant-select-item-option-content").getByText("A-12", { exact: true }).click();
  const ask = async (message: string) => {
    await page.getByPlaceholder("描述你的农事需求，或上传作物图片……").fill(message);
    await page.getByRole("button", { name: "发送消息" }).click();
  };
  await ask("今天最需要处理什么？");
  await expect(page.locator(".farmap-copilot__command-result .ant-card-head-title").filter({ hasText: "今日优先事项" })).toBeVisible();
  await expect(page.getByText("花期异常风险", { exact: true })).toBeVisible();
  await expect(page.getByText("B-03 病虫害巡查", { exact: true })).toBeVisible();

  await ask("今天农场怎么样？");
  await expect(page.locator(".farmap-copilot__command-result .ant-card-head-title").filter({ hasText: "当前农场状态" })).toBeVisible();
  await expect(page.getByText("当前农场已接入 1 个地块", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "查看病虫害风险", exact: true })).toBeVisible();

  await ask("柑橘花期高温怎么办？");
  await expect(page.locator(".farmap-copilot__command-result .ant-card-head-title").filter({ hasText: "Knowledge RAG" })).toHaveCount(0);
  await expect(page.locator(".farmap-chat-activity").getByText("知识库返回 1 条信息")).toBeVisible();

  await ask("找一下和这次情况最像的历史病例");
  await expect(page.getByText("需要现场图片", { exact: true })).toBeVisible();
  expect(historicalSearchCalled).toBe(false);
});
