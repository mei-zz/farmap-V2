import { expect, test } from "@playwright/test";

const username = process.env.FARMAP_PILOT_USERNAME;
const password = process.env.FARMAP_PILOT_PASSWORD;

test("renders Milvus reference images in the Copilot chat", async ({ page }) => {
  if (!username || !password) throw new Error("Set FARMAP_PILOT_USERNAME and FARMAP_PILOT_PASSWORD for this browser smoke.");

  await page.goto("login");
  await page.getByLabel("账号").fill(username);
  await page.getByLabel("密码").fill(password);
  await page.getByRole("button", { name: "登录控制台" }).click();
  await expect(page).toHaveURL(/\/farmap\/overview$/);

  await page.getByRole("combobox", { name: "当前农场" }).click();
  await page.locator(".ant-select-item-option-content").filter({ hasText: "巫山" }).click();
  await expect(page.getByRole("button", { name: "打开 FarMap 多模态农业 Agent" })).toContainText("巫山");
  await page.getByRole("button", { name: /FarMap (Copilot|Agent)/ }).first().click();
  const input = page.getByPlaceholder(/输入农事问题/);
  await input.fill("褐腐病是什么样的？");
  await page.getByRole("button", { name: "发送" }).click();

  await expect(page.locator("[data-reference-image='true']")).toHaveCount(3, { timeout: 60_000 });
  await expect(page.locator("[data-reference-image='true'] img")).toHaveCount(3);
  await expect(page.locator("[data-reference-image='true']").first()).toContainText("褐腐病");
});
