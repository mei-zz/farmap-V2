import { expect, test } from "@playwright/test";

import { openLiveWushanCopilot } from "./helpers/liveCopilot";

test("renders Milvus reference images in the Copilot chat", async ({ page }) => {
  const input = await openLiveWushanCopilot(page);
  await input.fill("褐腐病是什么样的？");
  await page.getByRole("button", { name: "发送消息" }).click();

  await expect(page.locator("[data-reference-image='true']")).toHaveCount(3, { timeout: 60_000 });
  await expect(page.locator("[data-reference-image='true'] img")).toHaveCount(3);
  await expect(page.locator("[data-reference-image='true']").first()).toContainText("褐腐病");
});
