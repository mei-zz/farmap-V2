import { expect, test } from "@playwright/test";

test("Agent diagnoses the selected field and renders the persisted report and evidence without a task write", async ({ page }) => {
  const farmId = 1;
  const fieldId = "field-yunyang-a12";
  const diagnosisId = "diagnosis-copilot-e2e";
  const createdAt = "2026-11-25T00:00:00Z";
  const requested: string[] = [];
  await page.addInitScript(() => {
    localStorage.setItem("token", "copilot-diagnosis-e2e-token");
    localStorage.setItem("user", JSON.stringify({ username: "测试账号", role: "user", farms: [{ id: 1, name: "云阳" }] }));
  });
  await page.route("**/user/validate-token", (route) => route.fulfill({ json: { data: { isValid: true, isExpired: false } } }));
  await page.route("**/api/farms/1/current-weather", (route) => route.fulfill({ json: { data: { farmId, status: "UNAVAILABLE" } } }));
  await page.route("**/api/farms/1/fields", (route) => route.fulfill({ json: { data: [{ fieldId, farmId, name: "A-12", crop: "柑橘" }] } }));
  await page.route("**/api/copilot/memories**", (route) => route.fulfill({ json: [] }));
  const block = (blockId: string, componentType: string, title: string, description: string) => ({
    blockId, componentType, title, schemaVersion: 1, runId: "chat-diagnosis-e2e", createdAt, updatedAt: createdAt, status: "completed",
    data: { diagnosisId, items: [{ label: title, value: description }] }, actions: [], provenance: [],
  });
  page.on("request", (request) => { if (request.method() === "POST") requested.push(new URL(request.url()).pathname); });
  await page.route("**/api/copilot/chat", (route) => {
    const request = route.request().postDataJSON();
    expect(request.pageContext).toMatchObject({ farmId: "1", fieldId });
    expect(request.message).toBe("只诊断 A-12 地块的病害风险，不创建农事任务。");
    return route.fulfill({ contentType: "text/event-stream", body: [
      ["route", { intent: "DIAGNOSIS_REQUEST", resolutionStatus: "RESOLVED", resolvedContext: { farmId: "1", fieldId } }],
      ["diagnosis.job", { jobId: "diagnosis-job-1", message: "诊断任务已创建" }],
      ["ui.block.created", block("diagnosis", "DiagnosisCard", "诊断结果", "花期异常风险调查")],
      ["ui.block.created", block("evidence", "DiagnosisEvidenceCard", "诊断依据", "现场数据不足，需补充照片")],
      ["diagnosis.completed", { diagnosisId, summary: "只读诊断已完成" }],
      ["content.delta", { delta: "只读诊断已完成。" }],
      ["done", { status: "COMPLETED" }],
    ].map(([event, data]) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`).join("") });
  });
  await page.goto("workspace");
  await page.locator(".agent-shell__field-select").click();
  await page.locator(".ant-select-dropdown:visible .ant-select-item-option-content").getByText("A-12", { exact: true }).click();
  await page.getByPlaceholder("描述你的农事需求，或上传作物图片……").fill("只诊断 A-12 地块的病害风险，不创建农事任务。");
  await page.getByRole("button", { name: "发送消息" }).click();
  await expect(page.locator('[data-block-type="DiagnosisCard"]')).toContainText("花期异常风险调查");
  await expect(page.locator('[data-block-type="DiagnosisEvidenceCard"]')).toContainText("现场数据不足，需补充照片");
  await expect(page.getByText("只读诊断已完成。", { exact: true })).toBeVisible();
  expect(requested.filter((path) => /\/operations\/|\/api\/agent\/runs|\/diagnosis\/analyze/.test(path))).toEqual([]);
});
