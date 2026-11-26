import { expect, test, type Page } from "@playwright/test";

const block = {
  schemaVersion: 1, blockId: "presentation-card", runId: "presentation-run", componentType: "FarmDirectoryCard", title: "农场列表",
  status: "ready", data: { farms: [{ id: 7, name: "巫山果园" }] }, actions: [], provenance: [],
  createdAt: "2026-11-25T00:00:00Z", updatedAt: "2026-11-25T00:00:00Z",
};

async function openWithStages(page: Page, stages: Array<Array<[string, unknown]>>, prompt = "当前有哪些农场？") {
  await page.addInitScript((frames) => {
    localStorage.setItem("token", "presentation-e2e");
    localStorage.setItem("user", JSON.stringify({ username: "测试账号", role: "user", farms: [{ id: 7, name: "巫山果园" }] }));
    const original = window.fetch.bind(window);
    window.fetch = (input, init) => {
      const url = input instanceof Request ? input.url : String(input);
      if (!url.endsWith("/api/copilot/chat")) return original(input, init);
      const encoder = new TextEncoder();
      return Promise.resolve(new Response(new ReadableStream({
        start(controller) {
          let stage = 0;
          const send = () => {
            for (const [name, data] of frames[stage] ?? []) controller.enqueue(encoder.encode(`event: ${name}\ndata: ${JSON.stringify(data)}\n\n`));
            stage++;
            if (stage === frames.length) {
              window.removeEventListener("presentation-next", send);
              controller.close();
            }
          };
          window.addEventListener("presentation-next", send);
          send();
        },
      }), { headers: { "Content-Type": "text/event-stream" } }));
    };
  }, stages);
  await page.route("**/user/validate-token", (route) => route.fulfill({ json: { data: { isValid: true, isExpired: false } } }));
  await page.route("**/api/farms/7/**", (route) => route.fulfill({ json: { data: [] } }));
  await page.route("**/api/copilot/memories**", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/copilot/conversations?**", (route) => route.fulfill({ json: [] }));
  await page.goto("workspace");
  await page.getByPlaceholder("描述你的农事需求，或上传作物图片……").fill(prompt);
  await page.getByRole("button", { name: "发送消息" }).click();
}

const routeFrame: [string, unknown] = ["route", { intent: "FARM_STATUS_QUERY", resolutionStatus: "RESOLVED" }];
const blockFrame: [string, unknown] = ["ui.block.created", block];
const doneFrame: [string, unknown] = ["done", { status: "COMPLETED" }];
const advance = (page: Page) => page.evaluate(() => window.dispatchEvent(new Event("presentation-next")));

test("structured replies reveal a stable conclusion before cards and stream analysis beneath them", async ({ page }) => {
  await openWithStages(page, [
    [routeFrame, ["tool.started", { toolName: "read_farm_directory" }], blockFrame],
    [["presentation.summary", { text: "当前账号可访问一个农场。" }], ["content.delta", { delta: "兼容客户端的重复文本" }]],
    [["presentation.analysis.delta", { delta: "农场权限来自当前登录账号。" }]],
    [["presentation.analysis.delta", { delta: "可以继续查看所选农场的地块。" }], ["presentation.followups", { items: [{ label: "查看地块", prompt: "当前农场有哪些地块？" }] }], doneFrame],
  ]);
  const turn = page.locator(".farmap-copilot__message--assistant");
  const card = turn.locator('[data-block-type="FarmDirectoryCard"]');
  await expect(turn.getByText("正在读取农场列表")).toBeVisible();
  await expect(card).toHaveCount(0);
  await advance(page);
  await expect(turn.getByText("当前账号可访问一个农场。")).toBeVisible();
  await expect(card.getByText("巫山果园")).toBeVisible();
  await advance(page);
  const analysis = turn.getByRole("region", { name: "补充分析" });
  await expect(analysis).toContainText("农场权限来自当前登录账号。");
  await advance(page);
  const followup = turn.getByRole("button", { name: "查看地块" });
  await expect(followup).toBeVisible();
  await expect(turn).not.toContainText("兼容客户端的重复文本");
  expect(await turn.evaluate((node) => {
    const order = [".farmap-response__summary", "[data-block-type='FarmDirectoryCard']", ".farmap-response__analysis", ".farmap-response__followups"];
    const items = order.map((selector) => node.querySelector(selector)!);
    return items.slice(0, -1).every((item, index) => Boolean(item.compareDocumentPosition(items[index + 1]) & Node.DOCUMENT_POSITION_FOLLOWING));
  })).toBe(true);
  await followup.click();
  await expect(page.getByPlaceholder("描述你的农事需求，或上传作物图片……")).toHaveValue("当前农场有哪些地块？");
});

test("legacy early cards stay queued until the ordinary text stream ends", async ({ page }) => {
  await openWithStages(page, [[routeFrame, blockFrame, ["content.delta", { delta: "先读取你的农场。" }]],
    [["content.delta", { delta: " 结果已经整理。" }]], [doneFrame]]);
  const card = page.locator('[data-block-type="FarmDirectoryCard"]');
  await expect(page.getByText("先读取你的农场。", { exact: true })).toBeVisible();
  await expect(card).toHaveCount(0);
  await advance(page);
  await expect(page.getByText(/结果已经整理/)).toBeVisible();
  await expect(card).toHaveCount(0);
  await advance(page);
  await expect(card).toBeVisible();
});

test("weather uses the specialty layout with real units and keeps measured zero distinct from absent data", async ({ page }) => {
  const weather = {
    farmName: "巫山果园", observedAt: "2026-11-25 17:31:00", temperature: "0", condition: "阴", humidity: "63", source: "测试气象服务",
    forecast: [{ date: "2026-11-26", condition: "晴", temperatureMin: 0, temperatureMax: 8, precipitationProbability: 0 }],
  };
  await openWithStages(page, [[
    routeFrame, ["presentation.summary", { text: "巫山果园目前天气为阴。" }],
    ["ui.block.created", { ...block, componentType: "WeatherCard", title: "当前天气", data: { weather } }], doneFrame,
  ]], "当前天气如何？");
  const card = page.locator('[data-block-type="WeatherCard"]');
  await expect(card.locator(".farmap-weather__temperature")).toContainText("0°C");
  await expect(card.getByText("63%", { exact: true })).toBeVisible();
  await expect(card.getByText("0%", { exact: false })).toBeVisible();
  await expect(card.getByText("预报暂无降水量", { exact: true })).toBeVisible();
  await expect(card.getByText("17:31", { exact: false })).toBeVisible();
  await expect(card.getByText("测试气象服务", { exact: false })).toBeVisible();
});

test("ordered orchard profile proposal is persisted only after its explicit confirmation", async ({ page }) => {
  const proposalId = "proposal-demo-orchard";
  const memoryBlock = { ...block, blockId: "profile-proposal", componentType: "MemoryProposalCard", title: "确认保存果园资料",
    status: "needs-input", data: { memoryProposal: { proposalId, scope: "FIELD", displayText: "海拔 850 米", status: "PENDING" } } };
  await openWithStages(page, [[routeFrame,
    ["message.part", { id: "question-suggestion", kind: "text", delta: "可以保存你提供的海拔资料。" }],
    ["message.part", { id: memoryBlock.blockId, kind: "block", block: memoryBlock }], doneFrame]],
    "这块地海拔 850 米");
  let confirmations = 0;
  await page.route(`**/api/copilot/memories/proposals/${proposalId}/confirm`, async (route) => {
    expect(route.request().method()).toBe("POST");
    expect(route.request().postDataJSON()).toEqual({});
    confirmations++;
    await route.fulfill({ json: { proposal: { proposalId, status: "CONFIRMED" }, memory: { memoryId: "memory-001" } } });
  });
  const card = page.locator('[data-block-type="MemoryProposalCard"]');
  await expect(card.getByText("海拔 850 米", { exact: true })).toBeVisible();
  expect(confirmations).toBe(0);
  await card.getByRole("button", { name: "确认保存" }).click();
  await expect(card.getByText("已保存到长期记忆。")).toBeVisible();
  expect(confirmations).toBe(1);
});
