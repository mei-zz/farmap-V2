import { expect, test, type Page } from "@playwright/test";

const username = process.env.FARMAP_PILOT_USERNAME;
const password = process.env.FARMAP_PILOT_PASSWORD;

async function openWushanCopilot(page: Page) {
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
}

test("answers a normal plum knowledge question within the interactive deadline", async ({ page }) => {
  await openWushanCopilot(page);
  const assistantMessages = page.locator(".farmap-copilot__message--assistant");
  const initialCount = await assistantMessages.count();
  const input = page.getByPlaceholder(/输入农事问题/);
  await input.fill("脆李有什么病虫害");

  const startedAt = Date.now();
  await page.getByRole("button", { name: "发送消息" }).click();
  await expect(page.getByRole("button", { name: "停止生成" })).toBeVisible();
  await expect(assistantMessages).toHaveCount(initialCount + 1, { timeout: 30_000 });
  const answer = assistantMessages.last();
  await expect(answer).toContainText(/褐腐病|穿孔病|蚜虫|红蜘蛛|病虫害/);
  await expect(answer).not.toContainText(/未能完成|暂时不可用|Failed to fetch/);
  await expect(page.getByRole("button", { name: "发送消息" })).toBeVisible({ timeout: 30_000 });
  expect(Date.now() - startedAt).toBeLessThan(30_000);
});

test("lets the user stop an in-flight chat request", async ({ page }) => {
  await openWushanCopilot(page);
  let releaseRequest: (() => void) | undefined;
  const heldRequest = new Promise<void>((resolve) => { releaseRequest = resolve; });
  await page.route("**/api/copilot/chat", async (route) => {
    await heldRequest;
    try {
      await route.fulfill({
        status: 200,
        contentType: "text/event-stream",
        body: "event: done\ndata: {\"status\":\"COMPLETED\"}\n\n",
      });
    } catch {
      // The browser request is expected to be aborted by the Stop button.
    }
  });

  const requestStarted = page.waitForRequest((request) => request.url().includes("/api/copilot/chat"));
  await page.getByPlaceholder(/输入农事问题/).fill("请检索脆李病虫害资料");
  await page.getByRole("button", { name: "发送消息" }).click();
  await requestStarted;
  const stopButton = page.getByRole("button", { name: "停止生成" });
  await expect(stopButton).toBeVisible();
  await stopButton.click();
  await expect(page.getByText("已停止生成。本次请求不会继续执行，也没有触发农事写入。")).toBeVisible();
  await expect(page.getByRole("button", { name: "发送消息" })).toBeVisible();
  releaseRequest?.();
  await page.unrouteAll({ behavior: "ignoreErrors" });
});
