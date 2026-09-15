import { expect, test } from "@playwright/test";

test("Copilot context follows field selection and farm switching", async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem("token", "copilot-context-sync-e2e-token");
    localStorage.setItem("user", JSON.stringify({
      username: "copilot-context-sync-e2e",
      role: "user",
      farms: [{ id: 1, name: "云阳" }, { id: 8, name: "大足" }],
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
    body: JSON.stringify({ data: [
      { fieldId: "field-yunyang-a12", farmId: 1, name: "A-12", crop: "柑橘", area: 2.4, growthStage: "花期" },
      { fieldId: "field-yunyang-a13", farmId: 1, name: "A-13", crop: "柑橘", area: 1.8, growthStage: "坐果期" },
    ] }),
  }));
  await page.route("**/api/farms/8/fields", async (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ data: [{ fieldId: "field-dazu-b03", farmId: 8, name: "B-03", crop: "柑橘", area: 3.1, growthStage: "膨果期" }] }),
  }));
  await page.route("**/api/farms/1/current-weather", async (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ data: { farmId: 1, status: "READY", condition: "多云", temperature: "26" } }),
  }));
  await page.route("**/api/farms/8/current-weather", async (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ data: { farmId: 8, status: "READY", condition: "晴", temperature: "29" } }),
  }));
  await page.route("**/api/farms/1/agricultural-context**", async (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ data: { farmId: 1, cropObservations: { status: "READY" }, historicalTemperature: { status: "READY" }, recentDiagnoses: { status: "READY", items: [] } } }),
  }));
  await page.route("**/api/farms/8/agricultural-context**", async (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ data: { farmId: 8, cropObservations: { status: "READY" }, historicalTemperature: { status: "READY" }, recentDiagnoses: { status: "READY", items: [] } } }),
  }));

  await page.goto("diagnosis/new");
  await expect(page.getByText("A-12", { exact: true }).first()).toBeVisible();
  await page.getByRole("button", { name: "FarMap Copilot" }).click();

  const contextGrid = page.locator(".farmap-copilot__context-grid");
  await expect(contextGrid.getByText("云阳", { exact: true })).toBeVisible();
  await expect(contextGrid.getByText("A-12", { exact: true })).toBeVisible();
  await expect(contextGrid.getByText("柑橘", { exact: true })).toBeVisible();

  await page.locator(".ant-drawer-close").click();
  const fieldSelect = page.getByLabel("选择地块");
  await fieldSelect.click();
  await page.locator(".ant-select-item-option").filter({ hasText: "A-13" }).evaluate((element) => (element as HTMLElement).click());
  await expect(page.getByText("A-13", { exact: true }).first()).toBeVisible();

  await page.getByRole("button", { name: "FarMap Copilot" }).click();
  await expect(contextGrid.getByText("A-13", { exact: true })).toBeVisible();
  await expect(contextGrid.getByText("A-12", { exact: true })).toHaveCount(0);
  await page.locator(".ant-drawer-close").click();
  const farmSelect = page.getByLabel("当前农场");
  await farmSelect.click();
  await expect(page.locator(".ant-select-item-option").filter({ hasText: "大足" })).toBeVisible();
  await page.locator(".ant-select-item-option").filter({ hasText: "大足" }).evaluate((element) => (element as HTMLElement).click());
  await expect(page.getByText("B-03", { exact: true }).first()).toBeVisible();
  await page.getByRole("button", { name: "FarMap Copilot" }).click();
  await expect(contextGrid.getByText("大足", { exact: true })).toBeVisible();
  await expect(contextGrid.getByText("B-03", { exact: true })).toBeVisible();
  await expect(contextGrid.getByText("云阳", { exact: true })).toHaveCount(0);
  await expect(contextGrid.getByText("A-13", { exact: true })).toHaveCount(0);
});
