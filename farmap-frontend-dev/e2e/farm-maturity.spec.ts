import { expect, test } from "@playwright/test";

test("camera maturity request renders spatial observations and unknown coverage", async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem("token", "maturity-e2e-token");
    localStorage.setItem("user", JSON.stringify({ username: "测试账号", role: "user", farms: [{ id: 42, name: "测试果园" }] }));
  });
  await page.route("**/user/validate-token", (route) => route.fulfill({ json: { data: { isValid: true, isExpired: false } } }));
  await page.route("**/api/farms/42/fields", (route) => route.fulfill({ json: { data: [{ fieldId: "field-east", name: "东区" }] } }));
  await page.route("**/api/farms/42/current-weather", (route) => route.fulfill({ json: { data: { status: "UNAVAILABLE" } } }));
  await page.route("**/api/copilot/memories**", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/copilot/conversations?**", (route) => route.fulfill({ json: [] }));
  const time = "2026-11-21T00:00:00Z";
  const block = {
    blockId: "scan-1", runId: "maturity-1", componentType: "FarmMaturityCard", schemaVersion: 1,
    title: "农场成熟度分布", status: "ready", actions: [], provenance: [{ label: "摄像头观测" }], createdAt: time, updatedAt: time,
    data: {
      farmName: "测试果园", observedAt: time, parcels: [
        { fieldId: "field-east", name: "东区", latitude: 31.08, longitude: 109.94, stage: "RIPE",
          observations: [{ cameraId: "camera-1", cameraName: "东区摄像头", stage: "RIPE", capturedAt: time, source: "BROWSER_CAMERA_FRAME", reason: "清晰可见果实外观" }] },
        { fieldId: "field-west", name: "西区", latitude: 31.06, longitude: 109.90, stage: "UNKNOWN", reason: "没有可用画面", observations: [] },
      ],
    },
  };
  await page.route("**/api/copilot/chat", (route) => {
    const body = route.request().postDataJSON();
    expect(body.pageContext.farmId).toBe("42");
    expect(body.message).toContain("摄像头");
    return route.fulfill({ contentType: "text/event-stream", body: [
      `event:route\ndata:${JSON.stringify({ conversationId: "maturity-c1", contextVersion: 1, intent: "FARM_MATURITY_SCAN", resolutionStatus: "RESOLVED" })}\n\n`,
      `event:ui.block.created\ndata:${JSON.stringify(block)}\n\n`,
      'event:content.delta\ndata:{"delta":"本次取得一个可判断画面。"}\n\n',
      'event:done\ndata:{"status":"COMPLETED"}\n\n',
    ].join("") });
  });
  await page.goto("workspace");
  await page.getByRole("button", { name: /成熟度分布/ }).click();
  await page.getByRole("button", { name: "发送消息" }).click();
  const card = page.locator('[data-block-type="FarmMaturityCard"]');
  await expect(card).toContainText("东区");
  await expect(card).toContainText("外观成熟");
  await expect(card).toContainText("西区");
  await expect(card).toContainText("没有可用画面");
  await expect(card.getByRole("img", { name: /地块相对位置/ })).toBeVisible();
  await card.locator("summary").filter({ hasText: "相机观测" }).click();
  await expect(card).toContainText("上传的监控截图");
  await card.getByRole("button", { name: "展开分析视图" }).click();
  await expect(page.getByRole("dialog", { name: "农场成熟度分布" }).getByRole("img", { name: /地块相对位置/ })).toBeVisible();
  await page.screenshot({ path: "../.runtime/design-qa/farm-maturity.png" });
});
