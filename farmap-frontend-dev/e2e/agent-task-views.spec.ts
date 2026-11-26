import { expect, test } from "@playwright/test";

test("weekly tasks in Agent and the secondary ledger use the same persisted schedule", async ({ page }) => {
  const day = "2026-11-22";
  const stamp = `${day}T00:00:00Z`;
  const taskId = "task-schedule-test";
  let task = { taskId, title: "东坡巡查", fieldId: "field-east", fieldName: "东坡", status: "OPEN", scheduledFor: "2026-11-17", version: 2 };
  let scopedReads = 0;
  const block = (id: string, componentType: string, title: string, data: object) => ({
    blockId: id, runId: "operations-chat-1", componentType, schemaVersion: 1, title, status: "ready",
    createdAt: stamp, updatedAt: stamp, data, actions: [], provenance: [],
  });
  const summary = block("weekly", "OperationsQueryCard", "农事任务", { farmId: 42, taskView: "tasks", from: "2026-11-16", to: day });
  const detail = block("active-task", "OperationTaskCard", "执行反馈", { taskId });
  await page.addInitScript(() => {
    localStorage.setItem("token", "operation-views-test");
    localStorage.setItem("user", JSON.stringify({ username: "测试账号", role: "user", farms: [{ id: 42, name: "测试果园" }] }));
  });
  await page.route("**/user/validate-token", (route) => route.fulfill({ json: { data: { isValid: true, isExpired: false } } }));
  await page.route("**/api/farms/42/**", (route) => route.fulfill({ json: { data: [] } }));
  await page.route("**/api/copilot/memories**", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/copilot/conversations?**", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/operations/task-view?**", (route) => {
    const params = new URL(route.request().url()).searchParams;
    expect(params.get("farmId")).toBe("42");
    expect(params.get("view")).toBe("tasks");
    expect(params.get("from")).toBe("2026-11-16");
    expect(params.get("to")).toBe(day);
    scopedReads++;
    return route.fulfill({ json: { code: 1, data: { items: [task], counts: { total: 1, pending: 1, inProgress: 0, completed: 0 } } } });
  });
  await page.route(`**/api/operations/tasks/${taskId}`, (route) => route.fulfill({ json: { code: 1, data: task } }));
  await page.route(`**/api/operations/tasks/${taskId}/schedule`, (route) => {
    expect(route.request().postDataJSON()).toMatchObject({ scheduledFor: "2026-11-20", version: task.version });
    task = { ...task, scheduledFor: "2026-11-20", version: task.version + 1 };
    return route.fulfill({ json: { code: 1, data: task } });
  });
  await page.route("**/api/copilot/chat", (route) => {
    const body = route.request().postDataJSON();
    return route.fulfill({ contentType: "text/event-stream", body: [
      ["route", { conversationId: "operations-view-chat", contextVersion: 1, intent: "OPERATIONS_STATUS_QUERY", resolutionStatus: "RESOLVED" }],
      ["ui.block.created", body.message.includes(taskId) ? detail : summary],
      ["content.delta", { delta: "已打开任务数据。" }],
      ["done", { status: "COMPLETED" }],
    ].map(([name, data]) => `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`).join("") });
  });

  await page.goto("workspace");
  await expect(page.locator(".agent-shell__nav").getByRole("link", { name: "农事计划" })).toHaveCount(0);
  const views = page.getByRole("navigation", { name: "专业数据视图" });
  for (const name of ["任务台账", "任务日历", "执行档案"]) await expect(views.getByRole("link", { name })).toBeVisible();

  const textarea = page.getByPlaceholder("描述你的农事需求，或上传作物图片……");
  await textarea.fill("查看本周所有农事任务");
  await page.getByRole("button", { name: "发送消息" }).click();
  const taskList = page.locator('[data-block-type="OperationsQueryCard"]');
  await expect(taskList).toContainText("东坡巡查");
  await expect(taskList).toContainText("待执行");

  await textarea.fill(`查看任务 ${taskId} 的详情和执行反馈`);
  await page.getByRole("button", { name: "发送消息" }).click();
  const taskCard = page.locator('[data-block-type="OperationTaskCard"]');
  await expect(taskCard.getByRole("button", { name: "修改排期" })).toBeVisible();
  await expect(taskCard.getByLabel("计划日期", { exact: true })).toHaveCount(0);
  await expect(taskCard).not.toContainText(taskId);
  await taskCard.getByRole("button", { name: "修改排期" }).click();
  await taskCard.getByLabel("计划日期", { exact: true }).fill("2026-11-20");
  await taskCard.getByRole("button", { name: "保存排期" }).click();
  await expect(taskCard.locator('time[datetime="2026-11-20"]')).toBeVisible();

  await taskList.getByRole("button", { name: "打开任务台账" }).click();
  await expect(page.getByRole("heading", { name: "任务台账" })).toBeVisible();
  await expect(page).toHaveURL(/\/planning\?view=ledger.*from=2026-11-16/);
  await expect(page.getByText("东坡巡查", { exact: true })).toBeVisible();
  await expect.poll(() => scopedReads).toBeGreaterThan(1);
});

test("a weekly calendar card switches weeks by querying the new date range", async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem("token", "week-calendar-fixture");
    localStorage.setItem("user", JSON.stringify({ username: "测试账号", role: "user", farms: [{ id: 42, name: "测试果园" }] }));
  });
  await page.route("**/user/validate-token", (route) => route.fulfill({ json: { data: { isValid: true, isExpired: false } } }));
  await page.route("**/api/farms/42/**", (route) => route.fulfill({ json: { data: [] } }));
  await page.route("**/api/copilot/memories**", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/copilot/conversations?**", (route) => route.fulfill({ json: [] }));
  const ranges: string[] = [];
  await page.route("**/api/operations/task-view?**", (route) => {
    const params = new URL(route.request().url()).searchParams;
    ranges.push(`${params.get("from")}/${params.get("to")}`);
    return route.fulfill({ json: { code: 1, data: { items: [], counts: { total: 0, pending: 0, inProgress: 0, completed: 0 } } } });
  });
  const data = { schemaVersion: 1, blockId: "calendar-week", runId: "run-calendar", title: "任务日历", componentType: "OperationsQueryCard", status: "ready",
    createdAt: "2026-11-25T00:00:00Z", updatedAt: "2026-11-25T00:00:00Z", actions: [], provenance: [],
    data: { farmId: 42, taskView: "calendar", from: "2026-11-30", to: "2026-12-06" },
  };
  await page.route("**/api/copilot/chat", (route) => route.fulfill({ contentType: "text/event-stream",
    body: `event: ui.block.created\ndata: ${JSON.stringify(data)}\n\nevent: done\ndata: {"status":"COMPLETED"}\n\n`,
  }));
  await page.goto("workspace");
  await page.getByPlaceholder("描述你的农事需求，或上传作物图片……").fill("打开下周的农事日历");
  await page.getByRole("button", { name: "发送消息" }).click();
  await expect(page.getByRole("button", { name: "周视图" })).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("button", { name: "下一周" }).click();
  await expect.poll(() => ranges).toContain("2026-12-07/2026-12-13");
  expect(ranges).toContain("2026-11-30/2026-12-06");
});
