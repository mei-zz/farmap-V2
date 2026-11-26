import { expect, test } from "@playwright/test";

test("public landing chats in memory and blocks private business navigation", async ({ page }) => {
  const requests: Array<{ message: string; history: Array<{ role: string; text: string }> }> = [];
  let privateCalls = 0;
  await page.route("**/api/copilot/conversations**", (route) => { privateCalls++; return route.fulfill({ status: 401 }); });
  await page.route("**/api/copilot/guest/chat", (route) => {
    expect(route.request().headers().authorization).toBeUndefined();
    requests.push(route.request().postDataJSON());
    return route.fulfill({ contentType: "text/event-stream", body: [
      'event: content.delta\ndata: {"delta":"先检查土壤含水状况。"}\n\n',
      'event: done\ndata: {"status":"COMPLETED"}\n\n',
    ].join("") });
  });
  await page.goto("");
  await expect(page).toHaveURL(/\/workspace$/);
  await expect(page.getByRole("heading", { name: "你的农事智能助手" })).toBeVisible();
  await expect(page.getByRole("link", { name: "生产监测", exact: true })).toHaveCount(0);
  const composer = page.getByRole("textbox", { name: "描述你的农事需求" });
  await composer.fill("临时问题：柑橘怎么灌溉？");
  await page.getByRole("button", { name: "发送消息" }).click();
  await expect(page.getByText("先检查土壤含水状况。", { exact: true })).toBeVisible();
  await composer.fill("那下雨之前呢？");
  await page.getByRole("button", { name: "发送消息" }).click();
  await expect.poll(() => requests.length).toBe(2);
  expect(Object.keys(requests[1]).sort()).toEqual(["history", "message"]);
  expect(requests[1].history.map(({ role }) => role)).toEqual(["user", "assistant"]);
  expect(await page.evaluate(() => JSON.stringify([localStorage, sessionStorage]))).not.toContain("临时问题");
  await page.reload();
  await expect(page.getByText("临时问题：柑橘怎么灌溉？")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "历史" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "访问摄像头" })).toHaveCount(0);
  for (const route of ["monitoring", "farm/devices", "knowledge", "admin", "diagnosis/history"]) {
    await page.goto(route);
    await expect(page).toHaveURL(/\/workspace$/);
  }
  expect(privateCalls).toBe(0);
});

test("account login opens in place and enables only authorized navigation", async ({ page }) => {
  await page.route("**/user/login", (route) => route.fulfill({ json: { code: 1, data: {
    user: { name: "测试账号", role: "user" }, token: "member-token", farms: [{ id: 1, name: "青溪果园" }],
  } } }));
  await page.route("**/user/validate-token", (route) => route.fulfill({ json: { data: { isValid: true, isExpired: false } } }));
  await page.route("**/api/farms/1/fields", (route) => route.fulfill({ json: { data: [{ fieldId: "field-1", name: "东坡", crop: "柑橘" }] } }));
  await page.route("**/api/farms/1/current-weather", (route) => route.fulfill({ json: { data: { status: "UNAVAILABLE" } } }));
  await page.route("**/api/copilot/memories**", (route) => route.fulfill({ json: [] }));
  await page.goto("");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page).toHaveURL(/\/workspace$/);
  const login = page.getByRole("dialog", { name: "登录 FarMap" });
  await login.getByLabel("账号").fill("test");
  await login.getByLabel("密码").fill("fixture-password");
  await login.getByRole("button", { name: "登录", exact: true }).click();
  await expect(login).toHaveCount(0);
  for (const view of ["地块空间", "生产监测", "知识中心"]) {
    await expect(page.getByRole("link", { name: view, exact: true })).toBeVisible();
  }
  await expect(page.locator(".agent-shell__nav").getByRole("link", { name: "农事计划" })).toHaveCount(0);
  await expect(page.getByRole("navigation", { name: "专业数据视图" }).getByRole("link", { name: "任务台账" })).toBeVisible();
  await expect(page.getByRole("link", { name: "系统管理" })).toHaveCount(0);
  await page.goto("admin");
  await expect(page).toHaveURL(/\/workspace$/);
  await page.getByRole("button", { name: "账号菜单" }).click();
  await page.getByText("退出登录", { exact: true }).click();
  await expect(page.getByRole("button", { name: "登录", exact: true })).toBeVisible();
  expect(await page.evaluate(() => localStorage.getItem("token"))).toBeNull();
});

test("business views retain the field and reference date when returning to Agent", async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem("token", "workspace-account-token");
    localStorage.setItem("user", JSON.stringify({ username: "测试账号", role: "user", farms: [{ id: 1, name: "青溪果园" }] }));
  });
  await page.route("**/user/validate-token", (route) => route.fulfill({ json: { data: { isValid: true, isExpired: false } } }));
  await page.route("**/api/farms/1/fields", (route) => route.fulfill({ json: { data: [{ fieldId: "field-1", name: "东坡", crop: "柑橘" }] } }));
  await page.route("**/api/farms/1/current-weather", (route) => route.fulfill({ json: { data: { farmId: 1, status: "UNAVAILABLE" } } }));
  await page.route("**/api/farms/1/cameras", (route) => route.fulfill({ json: { data: [] } }));
  await page.route("**/api/farms/1/map-data", (route) => route.fulfill({ status: 503 }));
  await page.route("**/api/farms/1/agricultural-context**", (route) => route.fulfill({ status: 503 }));
  await page.route("**/api/copilot/memories**", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/copilot/chat", (route) => {
    const body = route.request().postDataJSON();
    expect(body.pageContext).toMatchObject({ farmId: "1", fieldId: "field-1", route: "/knowledge", referenceDate: "2026-11-19" });
    return route.fulfill({ contentType: "text/event-stream", body: [
      'event: content.delta\ndata: {"delta":"正在结合东坡地块信息。"}\n\n',
      'event: done\ndata: {"status":"COMPLETED"}\n\n',
    ].join("") });
  });

  await page.goto("farm/map");
  await expect(page).toHaveURL(/\/fields$/);
  await expect(page.getByRole("heading", { name: "地块空间" })).toBeVisible();
  await page.getByRole("button", { name: /东坡/ }).click();
  await expect(page.getByRole("heading", { name: "东坡" })).toBeVisible();
  await page.getByLabel("参考日期").fill("2026-11-19");
  await page.screenshot({ path: "../.runtime/design-qa/01-spatial.png" });
  for (const [name, route] of [["生产监测", "monitoring"], ["知识中心", "knowledge"]]) {
    await page.getByRole("link", { name, exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/${route}$`));
    await expect(page.getByRole("heading", { name, exact: true })).toBeVisible();
    await expect(page.getByLabel("参考日期")).toHaveValue("2026-11-19");
    await expect(page.locator(".agent-shell__field-select")).toContainText("东坡");
    await page.screenshot({ path: `../.runtime/design-qa/${route}.png` });
  }
  await page.getByRole("button", { name: "让 Agent 接管当前上下文" }).click();
  await expect(page).toHaveURL(/\/workspace$/);
  await page.getByPlaceholder("描述你的农事需求，或上传作物图片……").fill("帮我分析当前地块");
  await page.getByRole("button", { name: "发送" }).click();
  await expect(page.getByText("正在结合东坡地块信息。", { exact: true })).toBeVisible();
});

test("administrator diagnostics are grouped in one management workspace", async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem("token", "admin-fixture");
    localStorage.setItem("user", JSON.stringify({ username: "管理员", role: "admin", farms: [] }));
  });
  await page.route("**/user/validate-token", (route) => route.fulfill({ json: { data: { isValid: true, isExpired: false } } }));
  await page.route("**/user/all", (route) => route.fulfill({ json: { users: [{ id: 7, name: "测试账号", role: "user" }] } }));
  await page.route("**/api/knowledge/sources", (route) => route.fulfill({ json: { code: 1, data: [] } }));
  await page.route("**/ai-model/availability", (route) => route.fulfill({ json: { code: 1, data: { provider: "opencode", models: [] } } }));
  await page.route("**/api/runtime/admin/health", (route) => {
    expect(route.request().headers().authorization).toBe("Bearer admin-fixture");
    return route.fulfill({ json: { code: 1, data: { knowledgeQwenIndex: { status: "READY" } } } });
  });
  await page.goto("settings/system");
  await expect(page).toHaveURL(/\/admin\?view=system$/);
  await expect(page.getByRole("heading", { name: "系统管理" })).toBeVisible();
  await expect(page.getByText("knowledgeQwenIndex", { exact: true })).toBeVisible();
  await page.getByRole("tab", { name: "用户管理" }).click();
  await expect(page.getByText("测试账号", { exact: true })).toBeVisible();
  await page.screenshot({ path: "../.runtime/design-qa/admin.png" });
});
