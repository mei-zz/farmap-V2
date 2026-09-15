import { expect, test } from "@playwright/test";

type FarmScope = "脆李时期" | "柑橘数据";

const sourceByScope: Record<FarmScope, string> = {
  "脆李时期": "source-cuili",
  "柑橘数据": "source-citrus",
};

function graphFor(scope: FarmScope) {
  const sourceId = sourceByScope[scope];
  const documentId = `document-${sourceId}`;
  const versionId = `version-${sourceId}`;
  return {
    status: "READY",
    generatedAt: new Date().toISOString(),
    nodes: [
      { id: sourceId, type: "KNOWLEDGE_SOURCE", label: scope, status: "ACTIVE", metadata: {} },
      { id: documentId, type: "KNOWLEDGE_DOCUMENT", label: `${scope}文档`, status: "ACTIVE", metadata: { sourceId } },
      { id: versionId, type: "KNOWLEDGE_VERSION", label: "Version 1", status: "READY", metadata: { documentId } },
    ],
    edges: [
      { id: `edge-${sourceId}-document`, source: sourceId, target: documentId, type: "HAS_DOCUMENT" },
      { id: `edge-${documentId}-version`, source: documentId, target: versionId, type: "HAS_VERSION" },
    ],
    summary: { sourceCount: 1, documentCount: 1, versionCount: 1, chunkCount: 1, productionChunkCount: 1, productionVectorCount: 1, evidenceCount: 0, diagnosisLinkedCount: 0, expertReviewCount: 0, historicalCandidateCount: 0 },
    coverage: { totalSourceFiles: 1, readyDocuments: 1, unsupportedFiles: 0, corruptNoTextFiles: 0, registeredFiles: 1, status: "PARTIAL", note: "" },
    collections: {
      productionKnowledge: { collection: "farmap_knowledge_chunks_qwen3_v1", status: "READY", rowCount: 1, embeddingModel: "qwen3.7-text-embedding-flash", dimension: 1024, metric: "COSINE" },
      historicalImage: { collection: "farmap_image_vectors_new", status: "READY_EMPTY", rowCount: 0, dimension: 512, metric: "L2" },
    },
    pipeline: { status: "READY", stages: [] },
    retrieval: { status: "READY", candidateTopN: 20, finalTopK: 5, embeddingProvider: "BAILIAN", embeddingModel: "qwen3.7-text-embedding-flash", rerankerModel: "gte-rerank-v2", rerankerStatus: "READY" },
  };
}

test("knowledge graph follows the selected farm corpus", async ({ page }) => {
  const graphSourceIds: string[] = [];

  await page.addInitScript(() => {
    localStorage.setItem("token", "knowledge-graph-e2e-token");
    localStorage.setItem("user", JSON.stringify({
      username: "knowledge-graph-e2e",
      role: "user",
      farms: [{ id: 1, name: "云阳" }, { id: 8, name: "大足" }],
    }));
  });

  await page.route("**/user/validate-token", async (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ data: { isExpired: false } }),
  }));
  await page.route("**/api/farms/*/current-weather", async (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ data: { status: "READY", condition: "晴", temperature: 25, rainfallForecast24h: 0, rainfallProbability24h: 0 } }),
  }));
  await page.route("**/api/knowledge/sources", async (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ data: [
      { sourceId: sourceByScope["脆李时期"], name: "FarMap 管理员农业知识库 / 脆李时期", originUri: "admin://脆李时期", status: "ACTIVE" },
      { sourceId: sourceByScope["柑橘数据"], name: "FarMap 管理员农业知识库 / 柑橘数据", originUri: "admin://柑橘数据", status: "ACTIVE" },
    ] }),
  }));
  await page.route("**/api/knowledge/graph**", async (route) => {
    const sourceId = new URL(route.request().url()).searchParams.get("sourceId") ?? "";
    graphSourceIds.push(sourceId);
    const scope = sourceId === sourceByScope["柑橘数据"] ? "柑橘数据" : "脆李时期";
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: graphFor(scope) }) });
  });
  await page.route("**/api/expert-reviews**", async (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) }));
  await page.route("**/api/expert-review**", async (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) }));

  await page.goto("knowledge/library");
  await expect(page.getByRole("heading", { name: "Knowledge Intelligence Center" })).toBeVisible();
  await expect(page.getByText("云阳 · 脆李时期", { exact: true })).toBeVisible();
  await expect(page.getByText("当前知识域：脆李时期", { exact: true })).toBeVisible();
  await expect.poll(() => graphSourceIds.at(-1)).toBe(sourceByScope["脆李时期"]);

  await page.locator(".farmap-header__farm-select").click();
  await page.locator(".ant-select-dropdown").getByText("大足", { exact: true }).click();
  await expect.poll(() => graphSourceIds.at(-1)).toBe(sourceByScope["柑橘数据"]);
  await expect(page.getByText("大足 · 柑橘数据", { exact: true })).toBeVisible();
  await expect(page.getByText("当前知识域：柑橘数据", { exact: true })).toBeVisible();

  await page.locator(".farmap-header__farm-select").click();
  await page.locator(".ant-select-dropdown").getByText("云阳", { exact: true }).click();
  await expect.poll(() => graphSourceIds.at(-1)).toBe(sourceByScope["脆李时期"]);
  await expect(page.getByText("云阳 · 脆李时期", { exact: true })).toBeVisible();
});
