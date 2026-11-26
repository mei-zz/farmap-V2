import { expect, test } from "@playwright/test";
import { openLiveWushanCopilot } from "./helpers/liveCopilot";

test("answers a normal plum knowledge question within the interactive deadline", async ({ page }) => {
  const input = await openLiveWushanCopilot(page);
  const assistantMessages = page.locator(".farmap-copilot__message--assistant");
  const initialCount = await assistantMessages.count();
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
  const input = await openLiveWushanCopilot(page);
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
  await input.fill("请检索脆李病虫害资料");
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
