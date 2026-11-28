import { expect, test, type Locator, type Page } from "@playwright/test";

const card = (page: Page, type: string) => page.locator(`[data-block-type="${type}"]`);

async function expectPaintedCanvas(canvas: Locator) {
  await expect(canvas).toBeVisible();
  await expect.poll(() => canvas.evaluate((element: HTMLCanvasElement) => {
    const context = element.getContext("2d");
    if (!context || !element.width || !element.height) return false;
    const pixels = context.getImageData(0, 0, element.width, element.height).data;
    for (let index = 3; index < pixels.length; index += 4) if (pixels[index] !== 0) return true;
    return false;
  })).toBe(true);
}

test.beforeEach(async ({ page }) => {
  await page.goto("e2e/domain-cards.html");
  await expect(page.locator(".farmap-blocks")).toBeVisible();
});

test("renders domain cards with structured data and lazy charts", async ({ page }) => {
  const phenology = card(page, "PlumPhenologyTimeline").first();
  await expect(phenology.locator(".farmap-phenology__track > li")).toHaveCount(10);
  await expect(phenology.getByText("物候记录存在冲突", { exact: true })).toBeVisible();
  await expect(phenology.getByText("12 天", { exact: true })).toBeVisible();
  await expect(phenology.getByText("2450℃·d / 2800℃·d", { exact: true })).toBeVisible();
  await expect(phenology.getByText(/海拔850m推迟8天/)).toBeVisible();

  const spray = card(page, "SprayWeatherWindowCard");
  await expect(spray.getByText("未来三天农事适宜度", { exact: true })).toBeVisible();
  await expect(spray.locator(".farmap-spray-weather__matrix:not(.farmap-spray-weather__slot-matrix) tbody tr")).toHaveCount(3);
  await expect(spray.locator(".farmap-spray-weather__slot-matrix tbody tr")).toHaveCount(9);
  await expect(spray.locator('.farmap-spray-weather__slot-matrix td[data-level="suitable"]')).toHaveCount(3);

  const pest = card(page, "PestComparisonCard");
  await expect(pest.getByText("现场病果", { exact: false })).toBeVisible();
  await expect(pest.getByRole("button", { name: "下一张图鉴" })).toBeVisible();
  await expect(pest.getByText("安全间隔期拦截", { exact: true })).toBeVisible();
  await pest.getByRole("button", { name: "查看第 2 张现场图" }).click();
  await pest.getByRole("button", { name: "放大查看 病果近照" }).click();
  await expect(page.getByRole("dialog", { name: "病果近照" })).toBeVisible();
  await page.keyboard.press("Escape");

  const chart = card(page, "EChartsAnalyticsCard").filter({ hasText: "气象与积温趋势" });
  await expectPaintedCanvas(chart.locator(".farmap-analytics__chart canvas"));
  for (const type of ["CropGrowthChart", "SoilTrendChart", "FarmAnalyticsCard"]) {
    await expectPaintedCanvas(card(page, type).locator(".farmap-analytics__chart canvas"));
  }
  for (const type of ["WeatherTrendChart", "ParcelCompareChart"]) {
    await expectPaintedCanvas(card(page, type).locator(".farmap-evidence-chart canvas"));
  }

  const analyticsTable = card(page, "FarmAnalyticsCard").filter({ hasText: "农场地块档案" });
  await expect(analyticsTable.getByText("A-01", { exact: true })).toBeVisible();
  await expect(analyticsTable.getByText("3.2 ha", { exact: true })).toBeVisible();
  await expect(analyticsTable.locator("canvas")).toHaveCount(0);

  const emptyChart = card(page, "EChartsAnalyticsCard").filter({ hasText: "无数据图表" });
  await expect(emptyChart.getByText("暂无可绘制的核验数据。", { exact: true })).toBeVisible();
  await expect(emptyChart.locator("canvas")).toHaveCount(0);

  const directory = card(page, "FarmDirectoryCard");
  await expect(directory.getByText("云雾山示范园", { exact: true })).toBeVisible();
  await expect(card(page, "ParcelOverviewCard").locator(".farmap-parcel-overview")).toContainText("脆李");

  const matrix = card(page, "ComparisonMatrixCard");
  await expect(matrix.getByText("生物防治", { exact: true })).toBeVisible();
  await expect(matrix.getByRole("button", { name: "选用此方案生成任务草稿" })).toHaveCount(2);

  const checklist = card(page, "ActionChecklistCard");
  await expect(checklist.getByRole("checkbox")).toHaveCount(3);
  await expect(checklist.getByText("前置条件：佩戴手套", { exact: false })).toBeVisible();
});

test("normalizes legacy ripening and expansion phenology labels", async ({ page }) => {
  const legacy = card(page, "PlumPhenologyTimeline").first();
  await expect(legacy.locator('li[aria-current="step"] strong')).toHaveText("转色成熟期");

  const expansion = card(page, "PlumPhenologyTimeline").nth(1);
  await expect(expansion.locator('li[aria-current="step"] strong')).toHaveText("果实发育期");
});

test("routes card actions through typed draft-task and evidence callbacks without prompt injection", async ({ page }) => {
  await card(page, "PlumPhenologyTimeline").first().getByRole("button", { name: "物候校准草稿" }).click();
  expect(await page.evaluate(() => window.cardTestDrafts)).toEqual([{
    fieldId: "field-1",
    title: "物候校准草稿",
    detail: "核对登记物候与推算物候。",
    scheduledFor: "2026-11-30",
  }]);

  await card(page, "SprayWeatherWindowCard").getByRole("button", { name: "查看气象证据" }).click();
  expect(await page.evaluate(() => window.cardTestEvidence)).toEqual(["E1"]);

  expect(await page.evaluate(() => window.cardTestPrompts)).toEqual([]);
});

test("calibrates the registered stage with the verified server target", async ({ page }) => {
  let submitted: unknown;
  await page.route("**/api/copilot/fields/field-1/phenology/calibrate", async (route) => {
    submitted = route.request().postDataJSON();
    await route.fulfill({ json: { fieldId: "field-1", growthStage: "harvest" } });
  });
  const phenology = card(page, "PlumPhenologyTimeline").first();
  await phenology.getByRole("button", { name: "一键校准地块物候" }).click();
  await expect(phenology.getByText("地块登记物候已更新。")).toBeVisible();
  expect(submitted).toEqual({ expectedRegisteredStage: "ripening", targetStage: "harvest" });
  expect(await page.evaluate(() => window.cardTestDrafts)).toEqual([]);
});

test("builds task drafts from comparison columns and selected checklist items", async ({ page }) => {
  let submitted: unknown;
  const runs = ["清理病果", "复查树冠"].map((title, index) => ({
    runId: `review-${index + 1}`, status: "WAITING_FOR_APPROVAL", steps: [],
    actions: [{ id: `action-${index + 1}`, title, approvalRequired: true, approvalState: "REQUIRED" }],
  }));
  await page.route("**/api/copilot/task-drafts/batch", async (route) => {
    submitted = route.request().postDataJSON();
    await route.fulfill({ json: runs });
  });
  await page.route("**/api/agent/runs/review-*", async (route) => {
    const run = runs.find((item) => route.request().url().endsWith(item.runId));
    await route.fulfill({ json: run });
  });
  await card(page, "ComparisonMatrixCard").getByRole("button", { name: "选用此方案生成任务草稿" }).first().click();
  const comparisonDraft = await page.evaluate(() => window.cardTestDrafts.at(-1));
  expect(comparisonDraft).toMatchObject({
    fieldId: "field-1",
    title: "执行方案：生物防治",
  });
  expect(comparisonDraft?.detail).toContain("适用条件：发病初期");
  expect(comparisonDraft?.detail).toContain("安全间隔期：3 天");

  const checklist = card(page, "ActionChecklistCard");
  await checklist.getByRole("checkbox", { name: "选择 清理病果" }).check();
  await checklist.getByRole("checkbox", { name: "选择 复查树冠" }).check();
  await expect(checklist.getByText("已选 2 / 3 项", { exact: true })).toBeVisible();
  await checklist.getByRole("button", { name: "转为待审批农事任务" }).click();
  await expect(checklist.getByRole("region", { name: "待审批农事任务草稿" })).toBeVisible();
  expect(submitted).toEqual({ tasks: [
    { fieldId: "field-1", title: "清理病果", detail: "带出园外无害化处理" },
    { fieldId: "field-2", title: "复查树冠", detail: "记录病斑扩展", scheduledFor: "2026-11-28" },
  ] });

  expect(await page.evaluate(() => window.cardTestPrompts)).toEqual([]);
});

test("ignoring an orchard memory proposal persists through reload", async ({ page }) => {
  let status = "PENDING";
  await page.route(/\/api\/copilot\/memories\/proposals\/proposal-plum-123(?:\/(?:ignore|confirm))?$/, async (route) => {
    if (route.request().method() === "POST" && route.request().url().endsWith("/ignore")) status = "IGNORED";
    await route.fulfill({ json: { status } });
  });
  await page.reload();
  await expect(page.locator(".farmap-blocks")).toBeVisible();
  const memory = card(page, "MemoryProposalCard");
  await memory.getByRole("button", { name: "忽略" }).click();
  await expect(memory.getByText("已忽略这条资料提案。")).toBeVisible();
  await page.reload();
  await expect(memory.getByText("已忽略这条资料提案。")).toBeVisible();
  await expect(memory.getByRole("button", { name: "确认保存" })).toHaveCount(0);
});
