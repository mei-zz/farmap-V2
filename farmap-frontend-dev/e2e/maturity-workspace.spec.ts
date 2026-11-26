import { expect, test, type Page } from "@playwright/test";

const farmId = 42;
const farmName = "测试果园";
const observedAt = "2026-11-21T00:00:00Z";
const maturityRunId = "maturity-workspace-run-1";

type SentChat = {
  message: string;
  pageContext: {
    farmId?: string;
    fieldId?: string;
    analysisRunId?: string;
  };
};

async function primeWorkspace(page: Page) {
  await page.addInitScript(({ id, name }) => {
    localStorage.setItem("token", "maturity-workspace-e2e-token");
    localStorage.setItem("user", JSON.stringify({ username: "成熟度测试账号", role: "user", farms: [{ id, name }] }));
  }, { id: farmId, name: farmName });
  await page.route("**/user/validate-token", (route) => route.fulfill({ json: { data: { isValid: true, isExpired: false } } }));
  await page.route(`**/api/farms/${farmId}/fields`, (route) => route.fulfill({
    json: { data: [{ fieldId: "field-east", name: "东区" }] },
  }));
  await page.route(`**/api/farms/${farmId}/current-weather`, (route) => route.fulfill({ json: { data: { status: "UNAVAILABLE" } } }));
  await page.route("**/api/copilot/memories**", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/copilot/conversations?**", (route) => route.fulfill({ json: [] }));
}

function event(name: string, data: unknown) {
  return `event:${name}\ndata:${JSON.stringify(data)}\n\n`;
}

test("farm scan opens the analysis workspace and carries its run into follow-up context", async ({ page }) => {
  const sent: SentChat[] = [];
  let maturityRunPath: string | undefined;
  const report = {
    runId: maturityRunId,
    farmId,
    farmName,
    mode: "FARM",
    topology: "DEMO",
    status: "READY",
    observedAt,
    summary: "东区画面可见部分成熟果实，西区没有可用画面。",
    parcels: [
      {
        fieldId: "field-east",
        name: "东区",
        latitude: 31.08,
        longitude: 109.94,
        stage: "RIPE",
        reason: "单帧画面可见果实外观转色。",
        observations: [{
          cameraId: "camera-east",
          cameraName: "东区摄像头",
          stage: "RIPE",
          capturedAt: observedAt,
          source: "BROWSER_CAMERA_FRAME",
          reason: "画面清晰可见果实外观",
        }],
      },
      {
        fieldId: "field-west",
        name: "西区",
        latitude: 31.06,
        longitude: 109.90,
        stage: "UNKNOWN",
        reason: "没有可用画面",
        observations: [],
      },
    ],
    recommendations: [{ title: "人工复核", detail: "请在采收前现场确认东区果实状态。" }],
  };

  await primeWorkspace(page);
  await page.route(`**/api/maturity/runs/${maturityRunId}`, (route) => {
    maturityRunPath = new URL(route.request().url()).pathname;
    return route.fulfill({ json: { data: report } });
  });
  await page.route(`**/api/maturity/farms/${farmId}/runs`, (route) => route.fulfill({ json: { data: [report] } }));
  await page.route(`**/api/farms/${farmId}/map-data`, (route) => route.fulfill({ json: { data: {
    farmId, farmName, center: "109.92,31.07", zoom: 12, sourceMode: "E2E_FIXTURE", coordinateReferenceSystem: "GCJ-02", status: "READY", crops: [],
    locations: [[109.89, 31.05], [109.95, 31.05], [109.95, 31.09], [109.89, 31.09]]
      .map(([longitude, latitude], index) => ({ pointId: `fixture-${index}`, longitude, latitude })),
  } } }));
  await page.route("**/api/copilot/chat", (route) => {
    const body = route.request().postDataJSON() as SentChat;
    sent.push(body);
    if (sent.length === 1) {
      return route.fulfill({ contentType: "text/event-stream", body: [
        event("route", { conversationId: "maturity-workspace-conversation", contextVersion: 1, intent: "FARM_MATURITY_SCAN", resolutionStatus: "RESOLVED" }),
        event("analysis.workspace.pending", { farmId }),
        event("analysis.workspace", { runId: maturityRunId, farmId, fieldId: "field-east", view: "ranking" }),
        event("content.delta", { delta: "已打开农场成熟度分析工作区。" }),
        event("done", { status: "COMPLETED" }),
      ].join("") });
    }
    return route.fulfill({ contentType: "text/event-stream", body: [
      event("route", { conversationId: "maturity-workspace-conversation", contextVersion: 2, intent: "GENERAL_QA", resolutionStatus: "RESOLVED" }),
      event("content.delta", { delta: "已结合当前成熟度报告补充说明。" }),
      event("done", { status: "COMPLETED" }),
    ].join("") });
  });

  await page.goto("workspace");
  await expect(page).toHaveURL(/\/workspace$/);
  await expect(page.locator(".agent-shell__farm-select")).toContainText(farmName);
  await page.getByRole("button", { name: /成熟度分布/ }).click();
  await page.getByRole("button", { name: "发送消息" }).click();

  await expect.poll(() => sent.length).toBe(1);
  expect(sent[0].message).toContain("成熟度");
  expect(sent[0].pageContext).toMatchObject({ farmId: String(farmId) });

  const shell = page.locator(".farmap-analysis-shell");
  const chatPane = shell.getByRole("region", { name: "Agent 对话" });
  const reportPane = shell.getByRole("region", { name: "空间分析工作区" });
  await expect(shell).toBeVisible();
  await expect(chatPane).toBeVisible();
  await expect(chatPane).toContainText("已打开农场成熟度分析工作区。");
  await expect(reportPane).toBeVisible();
  await expect(reportPane.getByTestId("maturity-workspace")).toBeVisible();
  await expect(reportPane.getByRole("heading", { name: farmName, exact: true })).toBeVisible();
  await expect(reportPane.getByText("外观成熟", { exact: true }).first()).toBeVisible();
  await expect(reportPane.getByText(/演示分区.*根据登记位置切分/)).toBeVisible();
  await reportPane.getByRole("heading", { name: "区域空间分布", exact: true }).scrollIntoViewIfNeeded();
  await page.screenshot({ path: "../.runtime/design-qa/maturity-demo-workspace.png" });
  await expect.poll(() => maturityRunPath).toBe(`/api/maturity/runs/${maturityRunId}`);

  const [chatBox, reportBox] = await Promise.all([chatPane.boundingBox(), reportPane.boundingBox()]);
  expect(chatBox).not.toBeNull();
  expect(reportBox).not.toBeNull();
  expect(reportBox!.x).toBeGreaterThan(chatBox!.x);

  await page.getByPlaceholder("描述你的农事需求，或上传作物图片……").fill("请说明这次成熟度报告的判断依据。");
  await page.getByRole("button", { name: "发送消息" }).click();
  await expect(page.getByText("已结合当前成熟度报告补充说明。", { exact: true })).toBeVisible();
  await expect.poll(() => sent.length).toBe(2);
  expect(sent[1].pageContext).toMatchObject({ farmId: String(farmId), analysisRunId: maturityRunId });
});

test("single camera maturity result stays inline without opening an analysis workspace", async ({ page }) => {
  const sent: SentChat[] = [];
  let maturityRunLoads = 0;
  const block = {
    blockId: "camera-maturity-1",
    runId: "camera-maturity-run-1",
    componentType: "FarmMaturityCard",
    schemaVersion: 1,
    title: "摄像头成熟度观测",
    status: "ready",
    actions: [],
    provenance: [{ label: "摄像头观测" }],
    createdAt: observedAt,
    updatedAt: observedAt,
    data: {
      runId: "camera-maturity-run-1",
      farmId,
      farmName,
      fieldId: "field-east",
      mode: "CAMERA",
      observedAt,
      summary: "本次只取得一张可判断画面。",
      parcels: [{
        fieldId: "field-east",
        name: "东区",
        stage: "RIPE",
        reason: "单帧画面可见果实外观。",
        observations: [{
          cameraId: "camera-east",
          cameraName: "东区摄像头",
          stage: "RIPE",
          capturedAt: observedAt,
          source: "BROWSER_CAMERA_FRAME",
          reason: "画面清晰可见果实外观",
          quality: "清晰",
        }],
      }],
    },
  };

  await primeWorkspace(page);
  await page.route("**/api/maturity/runs/**", (route) => {
    maturityRunLoads += 1;
    return route.fulfill({ status: 500, json: { message: "inline result must not open the run workspace" } });
  });
  await page.route("**/api/copilot/chat", (route) => {
    const body = route.request().postDataJSON() as SentChat;
    sent.push(body);
    return route.fulfill({ contentType: "text/event-stream", body: [
      event("route", { conversationId: "camera-inline-conversation", contextVersion: 1, intent: "FARM_MATURITY_SCAN", resolutionStatus: "RESOLVED" }),
      event("ui.block.created", { block }),
      event("content.delta", { delta: "已识别单张摄像头画面，保持内联展示。" }),
      event("done", { status: "COMPLETED" }),
    ].join("") });
  });

  await page.goto("workspace");
  await expect(page.locator(".agent-shell__farm-select")).toContainText(farmName);
  await page.getByPlaceholder("描述你的农事需求，或上传作物图片……").fill("用东区摄像头抓拍一张画面判断成熟度。");
  await page.getByRole("button", { name: "发送消息" }).click();

  await expect(page.getByText("已识别单张摄像头画面，保持内联展示。", { exact: true })).toBeVisible();
  await expect.poll(() => sent.length).toBe(1);
  expect(sent[0].message).toContain("摄像头");
  const card = page.locator('[data-block-type="FarmMaturityCard"]');
  await expect(card).toBeVisible();
  await expect(card.getByRole("region", { name: "图像分析结果" })).toBeVisible();
  await expect(card.locator(".farmap-maturity__single-observation")).toHaveCount(1);
  await expect(card).toContainText("东区摄像头");
  await expect(card).toContainText("外观成熟");
  await expect(card).toContainText("画面清晰可见果实外观");
  await expect(page.locator(".farmap-analysis-shell")).toHaveCount(0);
  await expect(page.getByRole("region", { name: "空间分析工作区" })).toHaveCount(0);
  expect(maturityRunLoads).toBe(0);
});
