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

  const scopes: Array<{ farmId?: string; fieldId?: string }> = [];
  await page.route("**/api/copilot/chat", (route) => {
    scopes.push(route.request().postDataJSON().pageContext);
    return route.fulfill({ contentType: "text/event-stream", body: 'event: content.delta\ndata: {"delta":"已读取当前范围。"}\n\nevent: done\ndata: {"status":"COMPLETED"}\n\n' });
  });
  await page.goto("workspace");
  const select = async (kind: "farm" | "field", name: string) => {
    await page.locator(`.agent-shell__${kind}-select`).click();
    await page.locator(".ant-select-dropdown:visible .ant-select-item-option-content").getByText(name, { exact: true }).click();
  };
  const send = async (expectedTurns: number) => {
    await page.getByPlaceholder("描述你的农事需求，或上传作物图片……").fill("请简要说明当前作物的物候期。");
    await page.getByRole("button", { name: "发送消息" }).click();
    await expect(page.getByText("已读取当前范围。", { exact: true })).toHaveCount(expectedTurns);
  };

  await select("field", "A-12");
  await send(1);
  await select("field", "A-13");
  await send(2);
  await select("farm", "大足");
  await select("field", "B-03");
  await send(3);
  expect(scopes.map(({ farmId, fieldId }) => ({ farmId, fieldId }))).toEqual([
    { farmId: "1", fieldId: "field-yunyang-a12" },
    { farmId: "1", fieldId: "field-yunyang-a13" },
    { farmId: "8", fieldId: "field-dazu-b03" },
  ]);
});
