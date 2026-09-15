import { expect, test } from "@playwright/test";

test("Copilot can start the same Diagnosis and Agent chain", async ({ page }) => {
  let diagnosisRequest: Record<string, unknown> | undefined;
  let agentRequest: Record<string, unknown> | undefined;
  let copilotPageContext: Record<string, unknown> | undefined;

  await page.addInitScript(() => {
    localStorage.setItem("token", "copilot-diagnosis-e2e-token");
    localStorage.setItem("user", JSON.stringify({
      username: "copilot-diagnosis-e2e",
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
    body: JSON.stringify({ data: [{ fieldId: "field-yunyang-a12", farmId: 1, name: "A-12", crop: "柑橘", area: 2.4, growthStage: "花期", sourceMode: "MYSQL" }] }),
  }));
  await page.route("**/api/farms/1/current-weather", async (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ data: { farmId: 1, status: "READY", condition: "多云", temperature: "26", observedAt: "2026-09-08T12:00:00Z" } }),
  }));
  await page.route("**/api/farms/1/agricultural-context**", async (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ data: {
      farmId: 1,
      cropObservations: { status: "READY", totalObservations: 18 },
      historicalTemperature: { status: "READY" },
      recentDiagnoses: { status: "READY", items: [] },
      currentWeather: { farmId: 1, status: "READY", condition: "多云", temperature: "26" },
    } }),
  }));
  await page.route("**/api/copilot/route", async (route) => {
    const body = route.request().postDataJSON() as { pageContext?: Record<string, unknown> };
    copilotPageContext = body.pageContext;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        intent: "DIAGNOSIS_REQUEST",
        resolutionStatus: "RESOLVED",
        resolvedContext: { farmId: "1", fieldId: "field-yunyang-a12" },
        capability: "DIAGNOSIS_CREATE",
        requiresClarification: false,
        traceId: "trace-copilot-diagnosis-e2e",
        confidence: 0.98,
      }),
    });
  });
  await page.route("**/diagnosis/analyze", async (route) => {
    diagnosisRequest = route.request().postDataJSON() as Record<string, unknown>;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ data: {
        diagnosisId: "diagnosis-copilot-e2e",
        run_id: "rag-copilot-e2e",
        diagnosis: { title: "花期异常风险调查", risk_level: "medium", confidence: 0.84, summary: "需要结合天气与历史农情继续调查。" },
        claims: [], evidence: [], recommendations: [], retrieval_summary: {}, metadata: { mode: "real" },
      } }),
    });
  });
  await page.route("**/api/agent/runs", async (route) => {
    agentRequest = route.request().postDataJSON() as Record<string, unknown>;
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ runId: "agent-copilot-e2e", status: "planning" }) });
  });
  await page.route("**/api/agent/runs/agent-copilot-e2e", async (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({
      runId: "agent-copilot-e2e",
      goal: "帮我看看这个地块最近为什么花期异常",
      mode: "real",
      context: { farmId: "1", farmName: "云阳", fieldId: "field-yunyang-a12", fieldName: "A-12", crop: "柑橘", diagnosis: { id: "diagnosis-copilot-e2e", title: "花期异常风险调查", severity: "medium", confidence: 84 }, sourceModes: {} },
      status: "WAITING_FOR_APPROVAL",
      steps: [], toolCalls: [], evidence: [], output: {}, actions: [], approvalState: "NONE", traces: [], metadata: {},
      createdAt: "2026-09-08T12:00:00Z", updatedAt: "2026-09-08T12:00:00Z",
    }),
  }));

  await page.goto("diagnosis/new");
  await expect(page.getByRole("heading", { name: "AI 农情诊断" })).toBeVisible();
  await expect(page.getByText("已选择地块", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "FarMap Copilot" }).click();
  await expect(page.getByText("A-12", { exact: true }).first()).toBeVisible();
  await page.getByPlaceholder("例如：A-12 地块近期需要关注什么？").fill("帮我看看这个地块最近为什么花期异常");
  await page.getByRole("button", { name: "发送" }).click();
  await expect.poll(() => copilotPageContext?.fieldId).toBe("field-yunyang-a12");
  expect(copilotPageContext?.farmId).toBe("1");

  await expect(page.getByText("已准备一次 AI 调查", { exact: true })).toBeVisible();
  expect(diagnosisRequest).toBeUndefined();
  expect(agentRequest).toBeUndefined();
  await page.getByRole("button", { name: "添加图片 / 高级设置" }).click();
  await expect(page).toHaveURL(/\/diagnosis\/new\?farmId=1&fieldId=field-yunyang-a12&goal=/);
  await expect(page.locator('textarea[placeholder="描述这次希望 AI 调查的问题"]')).toHaveValue("帮我看看这个地块最近为什么花期异常");

  await page.goto("diagnosis/new");
  await page.getByRole("button", { name: "FarMap Copilot" }).click();
  await page.getByPlaceholder("例如：A-12 地块近期需要关注什么？").fill("帮我看看这个地块最近为什么花期异常");
  await page.getByRole("button", { name: "发送" }).click();
  await expect(page.getByText("已准备一次 AI 调查", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "立即开始" }).click();

  await expect.poll(() => diagnosisRequest?.fieldId).toBe("field-yunyang-a12");
  expect(diagnosisRequest?.farmId).toBe(1);
  expect(diagnosisRequest?.query).toBe("帮我看看这个地块最近为什么花期异常");
  await expect.poll(() => agentRequest?.diagnosisId).toBe("diagnosis-copilot-e2e");
  await expect(page.getByText("FarMap Agent 已开始调查", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "查看完整调查" })).toBeVisible();
  await page.getByRole("button", { name: "查看完整调查" }).click();
  await expect(page).toHaveURL(/\/analysis\/agent-copilot-e2e\?diagnosisId=diagnosis-copilot-e2e$/);
  await expect(page.getByRole("heading", { name: "农业 AI 分析工作区" })).toBeVisible();
});
