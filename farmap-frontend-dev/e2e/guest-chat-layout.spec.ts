import { expect, test } from "@playwright/test";

function sse(event: string, data: unknown) {
  return `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
}

const longAnswer = [
  "## 柑橘园近期管理建议",
  ...Array.from({ length: 30 }, (_, index) => [
    `### 第 ${index + 1} 项`,
    "先检查树势、土壤含水量和近期降雨，再决定灌溉与追肥节奏。避免在高温时段作业，并记录每次处理后的变化。",
  ].join("\n\n")),
  "参考链接：" + "https://example.com/" + "very-long-unbroken-path-".repeat(18),
  "长回答结束标记",
].join("\n\n");

test("guest long answer scrolls inside the transcript and leaves the composer reachable", async ({ page }) => {
  await page.route("**/api/copilot/guest/chat", (route) => route.fulfill({
    status: 200,
    contentType: "text/event-stream",
    body: [
      sse("agent.runtime", { type: "turn_start" }),
      sse("agent.runtime", { type: "thinking_start" }),
      sse("tool.started", { toolName: "read_agricultural_knowledge", toolCallId: "tool-1" }),
      sse("content.delta", { delta: longAnswer }),
      sse("tool.completed", { toolName: "read_agricultural_knowledge", toolCallId: "tool-1" }),
      sse("agent.runtime", { type: "thinking_end" }),
      sse("done", { status: "COMPLETED" }),
    ].join(""),
  }));

  await page.goto("");
  await expect(page.getByRole("heading", { name: "你的农事智能助手" })).toBeVisible();
  await page.getByRole("textbox", { name: "描述你的农事需求" }).fill("柑橘园现在需要做什么？");
  await page.getByRole("button", { name: "发送消息" }).click();

  await expect(page.getByText("长回答结束标记", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "发送消息" })).toBeVisible();

  const activity = page.locator(".farmap-chat-activity");
  await expect(activity).toBeVisible();
  await expect(activity).toContainText("执行过程");
  await expect(activity).toContainText("检索农业知识库");

  const transcript = page.locator(".farmap-guest__messages");
  await expect.poll(() => transcript.evaluate((node) => node.scrollHeight > node.clientHeight + 100)).toBe(true);
  await expect.poll(() => transcript.evaluate((node) => node.scrollHeight - node.scrollTop - node.clientHeight)).toBeLessThan(3);
  await transcript.hover();
  await page.mouse.wheel(0, -350);
  await expect(page.getByRole("button", { name: "回到最新回答" })).toBeVisible();

  const viewport = page.viewportSize();
  const composer = await page.locator(".farmap-guest__composer").boundingBox();
  const messages = await transcript.boundingBox();
  expect(viewport && composer && messages).toBeTruthy();
  expect(composer!.y + composer!.height).toBeLessThanOrEqual(viewport!.height + 1);
  expect(messages!.y + messages!.height).toBeLessThanOrEqual(composer!.y + 1);

  const user = await page.locator(".farmap-copilot__message--user").last().boundingBox();
  const assistant = await page.locator(".farmap-copilot__message--assistant").last().boundingBox();
  expect(user && assistant).toBeTruthy();
  expect(user!.x).toBeGreaterThan(assistant!.x + 60);

  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);

  await page.setViewportSize({ width: 390, height: 700 });
  await expect(transcript).toBeVisible();
  const narrowComposer = await page.locator(".farmap-guest__composer").boundingBox();
  const narrowMessages = await transcript.boundingBox();
  expect(narrowComposer && narrowMessages).toBeTruthy();
  expect(narrowComposer!.y + narrowComposer!.height).toBeLessThanOrEqual(701);
  expect(narrowMessages!.y + narrowMessages!.height).toBeLessThanOrEqual(narrowComposer!.y + 1);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});

test("guest reading position survives streaming and the jump control returns to latest", async ({ page }) => {
  const opening = Array.from({ length: 60 }, (_, index) => `${index + 1}. 这是第一段的长内容，用于把对话区域撑开并验证独立滚动。`).join("\n\n");
  await page.addInitScript(({ opening, tail }) => {
    const originalFetch = window.fetch.bind(window);
    const wait = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      if (!url.includes("/api/copilot/guest/chat")) return originalFetch(input, init);
      const encoder = new TextEncoder();
      const stream = new ReadableStream<Uint8Array>({
        async start(controller) {
          const send = (event: string, data: unknown) => controller.enqueue(encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`));
          send("agent.runtime", { type: "thinking_start" });
          send("content.delta", { delta: `第一段信号\n\n${opening}` });
          await wait(1200);
          for (let index = 0; index < 8; index += 1) {
            send("content.delta", { delta: `\n\n${tail}${index + 1}` });
            await wait(120);
          }
          send("content.delta", { delta: "\n\n最后一段信号" });
          send("agent.runtime", { type: "thinking_end" });
          send("done", { status: "COMPLETED" });
          controller.close();
        },
      });
      return Promise.resolve(new Response(stream, { status: 200, headers: { "Content-Type": "text/event-stream" } }));
    }) as typeof window.fetch;
  }, { opening, tail: "后续流式片段：" });

  await page.goto("");
  await page.getByRole("textbox", { name: "描述你的农事需求" }).fill("请给出一份很长的柑橘管理建议");
  await page.getByRole("button", { name: "发送消息" }).click();
  await expect(page.getByText("第一段信号", { exact: true })).toBeVisible();
  await expect(page.locator(".farmap-chat-activity")).toContainText("正在思考");

  const transcript = page.locator(".farmap-guest__messages");
  await expect.poll(() => transcript.evaluate((node) => node.scrollHeight > node.clientHeight + 100)).toBe(true);
  await transcript.evaluate((node) => {
    node.scrollTop = 0;
    node.dispatchEvent(new Event("scroll"));
  });
  const jump = page.getByRole("button", { name: "回到最新回答" });
  await expect(jump).toBeVisible();
  expect(await transcript.evaluate((node) => node.scrollTop)).toBeLessThan(20);

  await expect(page.getByText("最后一段信号", { exact: true })).toBeVisible();
  expect(await transcript.evaluate((node) => node.scrollTop)).toBeLessThan(40);
  await expect(jump).toBeVisible();

  await jump.click();
  await expect.poll(() => transcript.evaluate((node) => node.scrollHeight - node.scrollTop - node.clientHeight)).toBeLessThan(3);
  await expect(jump).toHaveCount(0);
});

test("guest welcome, suggestions and composer fit a narrow viewport", async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 640 });
  await page.goto("");
  await expect(page.getByRole("heading", { name: "你的农事智能助手" })).toBeVisible();
  await expect(page.getByRole("button", { name: "作物栽培" })).toBeVisible();
  const composer = await page.locator(".farmap-guest__composer").boundingBox();
  expect(composer).toBeTruthy();
  expect(composer!.y).toBeGreaterThanOrEqual(0);
  expect(composer!.y + composer!.height).toBeLessThanOrEqual(641);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});
