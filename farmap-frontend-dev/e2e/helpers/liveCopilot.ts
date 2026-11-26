import { expect, type Page } from "@playwright/test";

export async function openLiveWushanCopilot(page: Page) {
  const username = process.env.FARMAP_PILOT_USERNAME;
  const password = process.env.FARMAP_PILOT_PASSWORD;
  if (!username || !password) throw new Error("Set FARMAP_PILOT_USERNAME and FARMAP_PILOT_PASSWORD for this browser smoke.");

  await page.goto("workspace");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  const login = page.getByRole("dialog", { name: "登录 FarMap" });
  await login.getByLabel("账号").fill(username);
  await login.getByLabel("密码").fill(password);
  await login.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page.getByRole("button", { name: "账号菜单" })).toBeVisible();
  const farm = page.locator(".agent-shell__farm-select");
  await farm.click();
  await page.locator(".ant-select-dropdown:visible .ant-select-item-option-content").getByText("巫山", { exact: true }).click();
  await expect(farm).toContainText("巫山");
  return page.getByPlaceholder("描述你的农事需求，或上传作物图片……");
}
