import { expect, test, type Page } from "@playwright/test";
import path from "node:path";

type ApiResult = { status: number; body: unknown };

const apiBase = process.env.FARMAP_API_BASE_URL || "http://localhost:8080";
const username = process.env.FARMAP_PILOT_USERNAME;
const password = process.env.FARMAP_PILOT_PASSWORD;
const farmId = Number(process.env.FARMAP_PILOT_FARM_ID || "9");
const screenshots = path.resolve(process.cwd(), "..", "docs", "pilot-e2e", "screenshots");

function requireEnv(name: string, value: string | undefined): string {
  if (!value) throw new Error(`Missing ${name}; browser smoke never embeds credentials.`);
  return value;
}

async function browserApi(page: Page, route: string, method = "GET", body?: unknown): Promise<ApiResult> {
  return page.evaluate(async ({ apiBase: base, route: requestRoute, method: requestMethod, body: requestBody }) => {
    const token = localStorage.getItem("token");
    const response = await fetch(`${base}${requestRoute}`, {
      method: requestMethod,
      headers: {
        Authorization: `Bearer ${token ?? ""}`,
        ...(requestBody === undefined ? {} : { "Content-Type": "application/json" }),
      },
      body: requestBody === undefined ? undefined : JSON.stringify(requestBody),
    });
    const text = await response.text();
    let parsed: unknown = text;
    try { parsed = text ? JSON.parse(text) : undefined; } catch { /* keep plain error text */ }
    return { status: response.status, body: parsed };
  }, { apiBase, route, method, body });
}

function data<T extends object>(result: ApiResult): T {
  const body = result.body as { data?: T };
  if (!body.data) throw new Error(`Expected API data, got HTTP ${result.status}`);
  return body.data;
}

function sameOriginCoreErrors(errors: string[]): string[] {
  return errors.filter((message) => /localhost:8080|127\.0\.0\.1:8080/.test(message)
    && /diagnos|agent|operation|field|farm|user/i.test(message));
}

test.describe("FarMap Commercial MVP controlled pilot", () => {
  test("real browser loop, refreshes, mobile smoke, and console gate", async ({ page }) => {
    const pilotUser = requireEnv("FARMAP_PILOT_USERNAME", username);
    const pilotPassword = requireEnv("FARMAP_PILOT_PASSWORD", password);
    const consoleErrors: string[] = [];
    const pageErrors: string[] = [];
    const failedCoreResponses: string[] = [];
    page.on("console", (message) => { if (message.type() === "error") consoleErrors.push(message.text()); });
    page.on("pageerror", (error) => pageErrors.push(error.message));
    page.on("response", (response) => {
      if (response.status() >= 500 && /localhost:8080|127\.0\.0\.1:8080/.test(response.url())) {
        failedCoreResponses.push(`${response.status()} ${response.url()}`);
      }
    });

    await page.goto("login");
    await page.getByLabel("账号").fill("missing-pilot-browser-user");
    await page.getByLabel("密码").fill("invalid-pilot-password");
    await page.getByRole("button", { name: "登录控制台" }).click();
    await expect(page.locator(".farmap-auth__error")).toHaveText("用户或密码错误");

    await page.getByLabel("账号").fill(pilotUser);
    await page.getByLabel("密码").fill(pilotPassword);
    await page.getByRole("button", { name: "登录控制台" }).click();
    await expect(page).toHaveURL(/\/farmap\/overview$/);
    await expect(page.getByText("农场智能总览")).toBeVisible();
    await expect(page.getByText(/DEMO/)).toHaveCount(0);
    await page.screenshot({ path: path.join(screenshots, "01-login.png"), fullPage: true });

    // Controlled multi-farm restoration smoke: the selector and map must be
    // backed by canonical map-data, not a frontend farm list or fixture.
    const restoredFarms = await page.evaluate(() => {
      const user = JSON.parse(localStorage.getItem("user") ?? "{}");
      return (user.farms ?? []) as Array<{ id: number; name: string }>;
    });
    expect(restoredFarms.map((farm) => farm.name)).toEqual(expect.arrayContaining(["云阳", "大足", "巫山"]));
    await page.goto("farm/map");
    await expect(page.getByText("地图详情")).toBeVisible();
    const mapFarmSelect = page.locator(".resp-dashboard__selects .ant-select").first();
    for (const farm of restoredFarms.filter((item) => ["云阳", "大足", "巫山"].includes(item.name))) {
      const mapDataResponse = page.waitForResponse((response) => response.url().includes(`/api/farms/${farm.id}/map-data`) && response.request().method() === "GET");
      await mapFarmSelect.click();
      await page.locator(".ant-select-dropdown").getByRole("option", { name: farm.name, exact: true }).click();
      const mapData = await mapDataResponse;
      expect(mapData.status()).toBe(200);
      const body = await mapData.json() as { data?: { farmId: number; sourceMode: string; locations: unknown[]; crops: Array<{ crop?: string }> } };
      expect(body.data?.farmId).toBe(farm.id);
      expect(body.data?.sourceMode).toBe("MYSQL");
      expect((body.data?.locations.length ?? 0) + (body.data?.crops.length ?? 0)).toBeGreaterThan(0);
      expect((body.data?.crops ?? []).every((point) => !point.crop || point.crop.length > 0)).toBe(true);
    }
    await page.goto("overview");
    await expect(page.getByText("农场智能总览")).toBeVisible();

    // Phase 1.4 Copilot smoke: the overview supplies the current persisted field context.
    await page.getByRole("button", { name: "FarMap Copilot" }).click();
    await expect(page.getByText("FarMap Copilot").last()).toBeVisible();
    await page.getByPlaceholder("例如：A-12 地块近期需要关注什么？").fill("这个地块有什么问题？");
    await page.getByRole("button", { name: "发送" }).click();
    await expect(page.getByText(/DIAGNOSIS_REQUEST/)).toBeVisible({ timeout: 30_000 });
    await page.getByPlaceholder("例如：A-12 地块近期需要关注什么？").fill("那应该怎么办？");
    await page.getByRole("button", { name: "发送" }).click();
    await expect(page.getByText(/RECOMMENDATION_QUERY|请先打开一条诊断/)).toBeVisible({ timeout: 30_000 });
    const conversationId = await page.evaluate(() => sessionStorage.getItem("farmap.copilot.conversation-id"));
    expect(conversationId).toMatch(/^conversation-/);
    const beforeRefresh = await browserApi(page, `/api/copilot/conversations/${encodeURIComponent(conversationId ?? "")}`);
    expect(beforeRefresh.status).toBe(200);
    expect((beforeRefresh.body as { recentTurns?: unknown[] }).recentTurns?.length).toBeGreaterThanOrEqual(4);

    await page.reload();
    await expect(page.getByText("农场智能总览")).toBeVisible();
    await page.getByRole("button", { name: "FarMap Copilot" }).click();
    await expect(page.getByText("请求已完成服务端上下文解析。")).toBeVisible({ timeout: 30_000 });
    await page.getByPlaceholder("例如：A-12 地块近期需要关注什么？").fill("安排第一条建议。");
    await page.getByRole("button", { name: "发送" }).click();
    await expect(page.getByText(/AGENT_START|请明确要执行哪条建议|人工批准/)).toBeVisible({ timeout: 30_000 });
    const afterRefresh = await browserApi(page, `/api/copilot/conversations/${encodeURIComponent(conversationId ?? "")}`);
    expect(afterRefresh.status).toBe(200);
    expect((afterRefresh.body as { conversationId?: string }).conversationId).toBe(conversationId);
    await page.screenshot({ path: path.join(screenshots, "01-copilot-conversation-refresh.png"), fullPage: true });

    // Phase 1.6 selective long-term memory smoke: explicit write, new conversation retrieval, revoke.
    await Promise.all([
      page.waitForResponse((response) => response.url().includes("/api/copilot/conversations") && response.request().method() === "POST"),
      page.getByRole("button", { name: "新建 Copilot 对话" }).click(),
    ]);
    await page.getByPlaceholder("例如：A-12 地块近期需要关注什么？").fill("记住，这个农场优先低残留处理。");
    await page.getByRole("button", { name: "发送" }).click();
    await expect(page.getByText(/已记住这项偏好/)).toBeVisible({ timeout: 30_000 });

    await Promise.all([
      page.waitForResponse((response) => response.url().includes("/api/copilot/conversations") && response.request().method() === "POST"),
      page.getByRole("button", { name: "新建 Copilot 对话" }).click(),
    ]);
    await page.getByPlaceholder("例如：A-12 地块近期需要关注什么？").fill("你记得我对这个农场有什么要求吗？");
    await page.getByRole("button", { name: "发送" }).click();
    await expect(page.getByText(/低残留/)).toBeVisible({ timeout: 30_000 });
    await page.getByPlaceholder("例如：A-12 地块近期需要关注什么？").fill("忘掉这个农场的低残留偏好。");
    await page.getByRole("button", { name: "发送" }).click();
    await expect(page.getByText(/已撤销这项长期偏好/)).toBeVisible({ timeout: 30_000 });

    await page.goto("farm/map");
    await expect(page.getByText("地图详情")).toBeVisible();
    await expect(page.getByText(/DEMO/)).toHaveCount(0);

    const fieldName = `PILOT_E2E_${Date.now()}`;
    const fieldResult = await browserApi(page, `/api/farms/${farmId}/fields`, "POST", {
      name: fieldName,
      crop: "citrus",
      area: 1.25,
      growthStage: "fruiting",
      latitude: 30.051,
      longitude: 103.832,
    });
    expect(fieldResult.status).toBe(200);
    const field = data<{ fieldId: string; name: string }>(fieldResult);
    const contextResult = await browserApi(page, `/api/fields/${encodeURIComponent(field.fieldId)}/context`);
    expect(contextResult.status).toBe(200);
    const fieldContext = data<{ status: string; sourceMode: string }>(contextResult);
    expect(fieldContext.status).toBe("READY");
    expect(fieldContext.sourceMode).toBe("MYSQL");

    const diagnosisResult = await browserApi(page, "/diagnosis/analyze", "POST", {
      fieldId: field.fieldId,
      farmId,
      imageUrls: [],
      query: "PILOT_E2E：分析当前柑橘地块的养分与病害风险，并给出可执行建议",
      topK: 5,
      enabledModalities: ["knowledge"],
    });
    expect(diagnosisResult.status).toBe(200);
    const diagnosis = data<{ diagnosisId: string; diagnosis: { title: string }; recommendations: Array<{ id: string }> }>(diagnosisResult);
    expect(diagnosis.diagnosisId).toMatch(/^diagnosis-/);
    expect(diagnosis.recommendations.length).toBeGreaterThan(0);

    await page.goto(`diagnosis/${diagnosis.diagnosisId}`);
    await expect(page.getByText("REAL RAG")).toBeVisible({ timeout: 180_000 });
    await expect(page.getByText(diagnosis.diagnosis.title)).toBeVisible();
    await expect(page.getByText("农业知识").first()).toBeVisible();
    await expect(page.getByText(/DEMO/)).toHaveCount(0);
    await page.screenshot({ path: path.join(screenshots, "02-diagnosis.png"), fullPage: true });

    await page.reload();
    await expect(page.getByText("REAL RAG")).toBeVisible();
    await expect(page.getByText(diagnosis.diagnosis.title)).toBeVisible();

    await page.getByRole("button", { name: /让 AI 分析处理方案/ }).click();
    await expect(page).toHaveURL(/\/farmap\/analysis\/agent-/ , { timeout: 180_000 });
    await expect(page.getByText("REAL AGENT RUN")).toBeVisible({ timeout: 180_000 });
    await expect(page.getByRole("button", { name: "批准并创建任务" })).toBeVisible({ timeout: 180_000 });
    await expect(page.getByText("等待人工确认").first()).toBeVisible();
    await expect(page.getByText(/DEMO/)).toHaveCount(0);
    await page.screenshot({ path: path.join(screenshots, "03-agent-approval.png"), fullPage: true });

    const runIdMatch = page.url().match(/\/analysis\/([^/?]+)/);
    if (!runIdMatch) throw new Error(`Could not resolve Agent run from ${page.url()}`);
    const runId = runIdMatch[1];
    await page.getByRole("button", { name: "批准并创建任务" }).click();
    await expect(page.getByText("任务已创建", { exact: true })).toBeVisible({ timeout: 60_000 });
    await page.reload();
    await expect(page.getByText("REAL AGENT RUN")).toBeVisible();
    await expect(page.getByText("任务已创建", { exact: true })).toBeVisible();

    const run = await browserApi(page, `/api/agent/runs/${encodeURIComponent(runId)}`);
    expect(run.status).toBe(200);
    const runBody = run.body as { output?: { task?: { taskId?: string } } };
    const taskId = runBody.output?.task?.taskId;
    if (!taskId) throw new Error("Approved Agent run did not return a persisted taskId");

    await page.getByRole("button", { name: /查看 Operations/ }).click();
    await expect(page).toHaveURL(/\/farmap\/operations\/tasks$/);
    await expect(page.getByText("REAL OPERATIONS")).toBeVisible({ timeout: 60_000 });
    await expect(page.getByText(field.fieldId, { exact: false }).first()).toBeVisible();
    await page.reload();
    await expect(page.getByText("REAL OPERATIONS")).toBeVisible();
    await expect(page.getByText(field.fieldId, { exact: false }).first()).toBeVisible();

    expect((await browserApi(page, `/api/operations/tasks/${encodeURIComponent(taskId)}/assign`, "POST", { assignee: "pilot-browser-operator" })).status).toBe(200);
    expect((await browserApi(page, `/api/operations/tasks/${encodeURIComponent(taskId)}/start`, "POST", {})).status).toBe(200);
    const completion = await browserApi(page, `/api/operations/tasks/${encodeURIComponent(taskId)}/complete`, "POST", {
      summary: "PILOT_E2E browser completion",
      executionEvidence: "PILOT_E2E browser evidence",
    });
    expect(completion.status).toBe(200);
    expect((await browserApi(page, `/api/operations/tasks/${encodeURIComponent(taskId)}/outcome`)).status).toBe(200);

    await page.reload();
    await expect(page.getByText("REAL OPERATIONS")).toBeVisible();
    await expect(page.getByText("已完成").first()).toBeVisible();
    await expect(page.getByText("PILOT_E2E browser completion")).toBeVisible();
    await expect(page.getByText(/DEMO/)).toHaveCount(0);
    await page.screenshot({ path: path.join(screenshots, "04-operations-outcome.png"), fullPage: true });

    const mobile = await page.context().newPage();
    const mobileErrors: string[] = [];
    mobile.on("console", (message) => { if (message.type() === "error") mobileErrors.push(message.text()); });
    mobile.on("pageerror", (error) => mobileErrors.push(error.message));
    await mobile.setViewportSize({ width: 390, height: 844 });
    await mobile.goto("login");
    await mobile.getByLabel("账号").fill(pilotUser);
    await mobile.getByLabel("密码").fill(pilotPassword);
    await mobile.getByRole("button", { name: "登录控制台" }).click();
    await expect(mobile).toHaveURL(/\/farmap\/overview$/);
    await expect(mobile.getByText("农场智能总览")).toBeVisible();
    const overflow = await mobile.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 80);
    expect(overflow).toBe(false);
    await mobile.close();

    expect(sameOriginCoreErrors([...consoleErrors, ...mobileErrors])).toEqual([]);
    expect(pageErrors).toEqual([]);
    expect(failedCoreResponses).toEqual([]);
  });
});
