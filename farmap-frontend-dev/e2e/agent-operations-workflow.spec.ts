import { expect, test } from "@playwright/test";

test("agent restores a report-backed draft, confirms an operations task and saves execution feedback", async ({ page }) => {
  const day = "2026-11-21";
  const timestamp = `${day}T00:00:00Z`;
  const analysisRunId = "analysis-harvest-1";
  const conversationId = "harvest-conversation";
  const fieldId = "field-east";
  const taskId = "task-harvest-1";
  const report = {
    runId: analysisRunId, farmId: 42, farmName: "测试果园", kind: "MATURITY", mode: "FARM", status: "COMPLETED",
    observedAt: timestamp, summary: "一处有可判画面。", recommendations: ["建议先到东坡实地复核采收条件。"],
    parcels: [
      { fieldId, name: "东坡", stage: "RIPE", observations: [{ cameraId: "east", cameraName: "东坡相机", stage: "RIPE", status: "SUCCESS" }] },
      { fieldId: "field-west", name: "西坡", stage: "UNKNOWN", observations: [{ cameraId: "west", cameraName: "西坡相机", stage: "UNKNOWN", status: "OFFLINE" }] },
    ],
  };
  const block = (blockId: string, componentType: string, title: string, data: object, status = "ready") => ({
    blockId, componentType, title, data, status, schemaVersion: 1, runId: "chat-harvest",
    actions: [], provenance: [], createdAt: timestamp, updatedAt: timestamp,
  });
  const processed = block("progress", "FarmInspectionProgress", "农场巡检进度", { total: 2, processed: 2, captured: 1, valid: 1 }, "completed");
  const analysis = block("analysis", "FarmMaturityCard", "农场视觉分析", { runId: analysisRunId, farmId: 42, kind: "MATURITY", mode: "FARM" });
  const recommendation = block("recommendation", "FarmRecommendationCard", "采收建议与任务草稿", { analysisRunId });

  type Draft = {
    draftId: string; analysisRunId: string; farmId: number; fieldId: string; fieldName: string; title: string;
    detail: string; priority: string; version: number; scheduledFor?: string; status: "DRAFT" | "CONFIRMED"; taskId?: string;
  };
  let draft: Draft | undefined;
  let task: { taskId: string; title: string; status: string; fieldId: string; scheduledFor?: string } | undefined;
  let outcome: { outcomeId: string; taskId: string; summary: string; executionEvidence?: string; createdAt: string } | undefined;
  let confirmations = 0;

  await page.addInitScript(() => {
    localStorage.setItem("token", "operations-timeline-fixture");
    localStorage.setItem("user", JSON.stringify({ username: "体验账号", role: "user", farms: [{ id: 42, name: "测试果园" }] }));
  });
  await page.route("**/user/validate-token", (route) => route.fulfill({ json: { data: { isValid: true, isExpired: false } } }));
  await page.route("**/api/farms/42/**", (route) => route.fulfill({ json: { data: [] } }));
  await page.route("**/api/farms/42/fields", (route) => route.fulfill({ json: { data: [{ fieldId, name: "东坡" }] } }));
  await page.route("**/api/operations/tasks", (route) => route.fulfill({ json: { code: 1, data: { items: task ? [task] : [] } } }));
  await page.route("**/api/copilot/memories**", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/copilot/conversations?**", (route) => route.fulfill({ json: [] }));
  await page.route(`**/api/copilot/conversations/${conversationId}`, (route) => route.fulfill({ json: {
    conversationId, status: "ACTIVE", contextVersion: 1, summaryVersion: 0, turnCount: 1, recentTurns: [],
    historyTurns: [{ turnId: "answer", role: "ASSISTANT", message: "巡检已完成。", responseMetadata: { uiBlocks: [processed, analysis, recommendation] } }],
  } }));
  await page.route(`**/api/maturity/runs/${analysisRunId}`, (route) => route.fulfill({ json: { code: 1, data: report } }));
  await page.route("**/api/copilot/chat", (route) => route.fulfill({
    contentType: "text/event-stream",
    body: [
      ["route", { conversationId, contextVersion: 1, intent: "FARM_MATURITY_SCAN", resolutionStatus: "RESOLVED" }],
      ["ui.block.created", { block: { ...processed, status: "loading", data: { total: 2, processed: 0, captured: 0, valid: 0 } } }],
      ["content.delta", { delta: "巡检已完成。" }],
      ["ui.block.updated", { block: processed }],
      ["ui.block.created", { block: analysis }],
      ["ui.block.created", { block: recommendation }],
      ["done", { status: "COMPLETED" }],
    ].map(([event, value]) => `event: ${event}\ndata: ${JSON.stringify(value)}\n\n`).join(""),
  }));
  await page.route("**/api/operations/plan-drafts?**", (route) => route.fulfill({ json: { code: 1, data: draft ? [draft] : [] } }));
  await page.route("**/api/operations/plan-drafts/from-analysis", (route) => {
    expect(route.request().postDataJSON()).toMatchObject({ analysisRunId, fieldId });
    draft ??= { draftId: "draft-harvest-1", analysisRunId, farmId: 42, fieldId, fieldName: "东坡", title: "采收前复核",
      detail: "到东坡实地确认果实状态。", priority: "MEDIUM", version: 0, status: "DRAFT" };
    return route.fulfill({ json: { code: 1, data: draft } });
  });
  await page.route("**/api/operations/plan-drafts/draft-harvest-1**", (route) => {
    const pathname = new URL(route.request().url()).pathname;
    if (pathname.endsWith("/save") || pathname.endsWith("/confirm")) {
      const input = route.request().postDataJSON();
      expect(input.version).toBe(draft?.version);
      draft = { ...draft!, ...input, version: input.version + 1 };
      if (pathname.endsWith("/confirm")) {
        confirmations++;
        draft = { ...draft!, status: "CONFIRMED", taskId };
        task = { taskId, title: draft.title, fieldId, status: "OPEN", scheduledFor: draft.scheduledFor };
      }
    }
    return route.fulfill({ json: { code: 1, data: draft } });
  });
  await page.route(`**/api/operations/tasks/${taskId}**`, (route) => {
    const pathname = new URL(route.request().url()).pathname;
    if (pathname.endsWith("/outcome")) return route.fulfill({ json: { code: 1, data: outcome } });
    if (pathname.endsWith("/start")) task = { ...task!, status: "IN_PROGRESS" };
    if (pathname.endsWith("/complete")) {
      const input = route.request().postDataJSON();
      task = { ...task!, status: "COMPLETED" };
      outcome = { ...input, outcomeId: "outcome-harvest-1", taskId, createdAt: timestamp };
      return route.fulfill({ json: { code: 1, data: { task, outcome } } });
    }
    return route.fulfill({ json: { code: 1, data: task } });
  });

  await page.goto("workspace");
  await page.getByPlaceholder("描述你的农事需求，或上传作物图片……").fill("看看整个农场的成熟度并给出采收建议");
  await page.getByRole("button", { name: "发送消息" }).click();
  const progress = page.locator('[data-block-type="FarmInspectionProgress"]');
  const plan = page.locator('[data-block-type="FarmRecommendationCard"]');
  await expect(progress).toContainText("2 / 2");
  await expect(page.locator('[data-block-type="FarmMaturityCard"]')).toContainText("有效识别");
  await plan.getByRole("button", { name: /生成.*草[稿案]/ }).click();
  expect(task).toBeUndefined();
  await plan.getByLabel(/任务标题|任务名称/).fill("东坡采收复核");
  await plan.getByLabel(/计划日期/).fill("2026-11-25");
  await plan.getByRole("button", { name: /保存草稿/ }).click();
  await expect.poll(() => draft?.title).toBe("东坡采收复核");

  await page.reload();
  await expect(plan.getByLabel(/任务标题|任务名称/)).toHaveValue("东坡采收复核");
  await expect(plan.getByLabel(/计划日期/)).toHaveValue("2026-11-25");
  await plan.getByRole("button", { name: /确认.*(?:安排|任务|创建)/ }).click();
  await expect.poll(() => task?.status).toBe("OPEN");
  expect(confirmations).toBe(1);
  expect(task?.scheduledFor).toBe("2026-11-25");

  await plan.getByRole("button", { name: "查看任务详情" }).click();
  await plan.getByRole("button", { name: /开始执行|标记开始|标记为执行中/ }).click();
  await plan.getByRole("textbox", { name: "执行结果摘要" }).fill("已到东坡复核，发现仍有部分果实未达采收标准。");
  await plan.getByRole("button", { name: /保存.*反馈|提交.*反馈|完成任务|提交完成记录/ }).click();
  await expect(plan).toContainText("已到东坡复核，发现仍有部分果实未达采收标准。");
  await page.reload();
  await plan.getByRole("button", { name: "查看执行结果" }).click();
  await expect(plan).toContainText("已到东坡复核，发现仍有部分果实未达采收标准。");
  expect(confirmations).toBe(1);

  await page.goto("planning?view=records");
  const records = page.getByRole("tabpanel", { name: "执行记录" });
  await expect(records).toContainText("东坡采收复核");
  await expect(records).toContainText("已到东坡复核，发现仍有部分果实未达采收标准。");
});
