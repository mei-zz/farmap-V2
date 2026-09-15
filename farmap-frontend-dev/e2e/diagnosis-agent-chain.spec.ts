import { expect, test } from "@playwright/test";

test("AI diagnosis creates a diagnosis before creating an Agent Run", async ({ page }) => {
  let diagnosisRequest: Record<string, unknown> | undefined;
  let agentRequest: Record<string, unknown> | undefined;

  await page.addInitScript(() => {
    localStorage.setItem("token", "diagnosis-agent-e2e-token");
    localStorage.setItem("user", JSON.stringify({
      username: "diagnosis-agent-e2e",
      role: "user",
      farms: [{ id: 1, name: "云阳" }],
    }));
  });

  await page.route("**/user/validate-token", async (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: { isExpired: false } }) }));
  await page.route("**/api/farms/1/fields", async (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ data: [{ fieldId: "field-yunyang-a12", farmId: 1, name: "A-12", crop: "柑橘", area: 2.4, growthStage: "膨果期", latitude: 30.05, longitude: 103.83, status: "ACTIVE", sourceMode: "MYSQL" }] }),
  }));
  await page.route("**/api/farms/1/current-weather", async (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ data: { farmId: 1, status: "READY", condition: "晴", temperature: "26", rainfallForecast24h: 1.2, rainfallProbability24h: 30, observedAt: "2026-09-08T12:00:00Z" } }),
  }));
  await page.route("**/api/farms/1/agricultural-context**", async (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ data: {
      farmId: 1,
      cropObservations: { status: "READY", totalObservations: 12 },
      historicalTemperature: { status: "READY" },
      recentDiagnoses: { status: "READY", items: [] },
      currentWeather: { farmId: 1, status: "READY", condition: "晴", temperature: "26" },
    } }),
  }));
  await page.route("**/diagnosis/analyze", async (route) => {
    diagnosisRequest = route.request().postDataJSON() as Record<string, unknown>;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ data: {
        diagnosisId: "diagnosis-chain-e2e",
        run_id: "rag-run-chain-e2e",
        diagnosis: { title: "柑橘膨果期风险调查", risk_level: "medium", confidence: 0.86, summary: "需要结合天气与历史农情继续调查。" },
        claims: [], evidence: [], recommendations: [], retrieval_summary: {}, metadata: { mode: "real" },
      } }),
    });
  });
  await page.route("**/api/agent/runs", async (route) => {
    agentRequest = route.request().postDataJSON() as Record<string, unknown>;
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ runId: "agent-chain-e2e", status: "planning" }) });
  });
  await page.route("**/api/agent/runs/agent-chain-e2e", async (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({
      runId: "agent-chain-e2e",
      goal: "调查当前地块风险并生成可执行方案",
      mode: "real",
      context: { farmId: "1", farmName: "云阳", fieldId: "field-yunyang-a12", fieldName: "A-12", crop: "柑橘", imageUrls: [], weather: {}, diagnosis: { id: "diagnosis-chain-e2e", title: "柑橘膨果期风险调查", severity: "medium", confidence: 86 }, sourceModes: {} },
      status: "WAITING_FOR_APPROVAL",
      steps: [], toolCalls: [], evidence: [], output: {}, actions: [], approvalState: "NONE", traces: [], metadata: {},
      createdAt: "2026-09-08T12:00:00Z", updatedAt: "2026-09-08T12:00:00Z",
    }),
  }));

  await page.goto("diagnosis/new");
  await expect(page.getByRole("heading", { name: "AI 农情诊断" })).toBeVisible();
  await expect(page.getByText("当前农场：云阳", { exact: true })).toBeVisible();
  await expect(page.getByText("已选择地块", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "开始 AI 调查" }).click();

  await expect.poll(() => diagnosisRequest?.fieldId).toBe("field-yunyang-a12");
  expect(diagnosisRequest?.farmId).toBe(1);
  expect(diagnosisRequest?.imageUrls).toEqual([]);
  await expect.poll(() => agentRequest?.diagnosisId).toBe("diagnosis-chain-e2e");
  await expect(page).toHaveURL(/\/analysis\/agent-chain-e2e\?diagnosisId=diagnosis-chain-e2e$/);
  await expect(page.getByRole("heading", { name: "农业 AI 分析工作区" })).toBeVisible();
});

test("AI diagnosis navigation opens the investigation entry point", async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem("token", "diagnosis-navigation-e2e-token");
    localStorage.setItem("user", JSON.stringify({
      username: "diagnosis-navigation-e2e",
      role: "user",
      farms: [{ id: 1, name: "云阳" }],
    }));
  });

  await page.route("**/user/validate-token", async (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ data: { isExpired: false } }),
  }));
  await page.route("**/api/farms/1/fields", async (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ data: [] }),
  }));

  await page.goto("overview");
  await page.getByRole("link", { name: /AI 农情诊断/ }).click();
  await expect(page).toHaveURL(/\/diagnosis\/new$/);
  await expect(page.getByRole("heading", { name: "AI 农情诊断" })).toBeVisible();
});

test("Agent Workspace restores server-owned context and source states after refresh", async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem("token", "agent-workspace-refresh-e2e-token");
    localStorage.setItem("user", JSON.stringify({
      username: "agent-workspace-refresh-e2e",
      role: "user",
      farms: [{ id: 1, name: "云阳" }],
    }));
  });

  await page.route("**/user/validate-token", async (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ data: { isExpired: false } }),
  }));
  await page.route("**/api/farms/1/current-weather", async (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ data: { farmId: 1, status: "READY", condition: "多云", temperature: "33" } }),
  }));
  await page.route("**/api/agent/runs/agent-workspace-refresh-e2e", async (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({
      runId: "agent-workspace-refresh-e2e",
      goal: "验证真实上下文和来源状态能够在刷新后恢复",
      mode: "real",
      context: {
        farmId: "1",
        farmName: "云阳",
        fieldId: "field-c3c5c1de-67f7-4111-a263-a6e52d856e08",
        fieldName: "phase-e-runtime-20260907-031950",
        crop: "citrus",
        phenologyStage: "flowering",
        latitude: 31.1706,
        longitude: 108.1302,
        imageUrls: [],
        weather: { status: "READY", sourceMode: "GAODE_LIVE", summary: "多云", temperature: "33", rainfallForecastDays: 4, observedAt: "2026-09-09 16:01:42" },
        diagnosis: { id: "diagnosis-workspace-refresh-e2e", title: "柑橘花期风险调查", severity: "low", confidence: 0.75 },
        sourceModes: { field: "MYSQL", weather: "GAODE_LIVE", gis: "MYSQL", historical: "UNAVAILABLE", camera: "UNAVAILABLE" },
      },
      status: "COMPLETED",
      steps: [
        { id: "field", title: "读取地块上下文", description: "真实地块上下文已读取", toolName: "field-context", readOnly: true, dependsOn: [], status: "COMPLETED" },
        { id: "weather", title: "检查天气信息", description: "实时天气已读取", toolName: "weather", readOnly: true, dependsOn: ["field"], status: "COMPLETED" },
        { id: "history", title: "检查历史案例", description: "历史案例索引没有匹配结果", toolName: "historical-case", readOnly: true, dependsOn: ["field"], status: "COMPLETED" },
      ],
      toolCalls: [
        { id: "tool-field", toolName: "field-context", status: "COMPLETED", output: { status: "READY" } },
        { id: "tool-weather", toolName: "weather", status: "COMPLETED", output: { status: "READY" } },
        { id: "tool-history", toolName: "historical-case", status: "COMPLETED", output: { status: "EMPTY", count: 0 } },
      ],
      evidence: [{ id: "EVD-KNOWLEDGE-001", modality: "knowledge", title: "柑橘花期管理规范", summary: "知识库真实证据", score: 0.82, source: { name: "柑橘数据" }, metadata: { knowledgeStatus: "READY" } }],
      output: { evidenceIds: ["EVD-KNOWLEDGE-001"], historical_case: { status: "EMPTY", count: 0 } },
      actions: [],
      approvalState: "NONE",
      traces: [],
      metadata: { plannerModel: "qwen3.6-plus", "historical-case": { historicalStatus: "READY_EMPTY" } },
      createdAt: "2026-09-09T08:03:41.696Z",
      updatedAt: "2026-09-09T08:04:46.414Z",
    }),
  }));

  await page.goto("analysis/agent-workspace-refresh-e2e");
  await expect(page.getByText("云阳", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("phase-e-runtime-20260907-031950", { exact: true }).first()).toBeVisible();
  await expect(page.locator(".fm-agent-data-sources").getByText("READY_EMPTY", { exact: true })).toBeVisible();
  await page.getByText("Technical Details", { exact: true }).click();
  await expect(page.getByText("qwen3.6-plus", { exact: true })).toBeVisible();
  await expect(page.getByText("diagnosis-workspace-refresh-e2e", { exact: true })).toBeVisible();
  await expect(page.getByText("EVD-KNOWLEDGE-001", { exact: true })).toHaveCount(0);

  await page.reload();
  await expect(page.getByText("phase-e-runtime-20260907-031950", { exact: true }).first()).toBeVisible();
  await expect(page.locator(".fm-agent-data-sources").getByText("READY_EMPTY", { exact: true })).toBeVisible();
});
