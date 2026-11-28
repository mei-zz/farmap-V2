import { Alert, Button, Card, Flex, Tag, Typography } from "antd";
import type {
  CommandDiagnosisResult,
  CommandInsightResult,
  CommandPrioritiesResult,
  HistoricalCaseSearchResult,
} from "@/features/copilot/commandService";

const { Text } = Typography;

export type CommandBarResult =
  | { type: "PRIORITIES"; data: CommandPrioritiesResult }
  | { type: "HISTORICAL_CASES"; data: HistoricalCaseSearchResult }
  | { type: "FARM_STATUS"; data: CommandInsightResult }
  | { type: "FIELD_STATUS"; data: CommandInsightResult }
  | { type: "DIAGNOSIS"; data: CommandDiagnosisResult };

export type CopilotLegacyCommandPanelProps = {
  loading?: CommandBarResult["type"];
  result?: CommandBarResult;
  onOpenPath: (path: string) => void;
  onOpenDiagnosisPage: (goal: string) => void;
};

export function CopilotLegacyCommandPanel({
  loading,
  result,
  onOpenPath,
  onOpenDiagnosisPage,
}: CopilotLegacyCommandPanelProps) {
  if (loading === "PRIORITIES") {
    return <Card className="farmap-copilot__command-result" size="small" title="今日优先事项"><Text type="secondary">正在读取诊断、任务与地块上下文…</Text></Card>;
  }
  if (loading === "HISTORICAL_CASES") {
    return <Card className="farmap-copilot__command-result" size="small" title="相似历史病例"><Text type="secondary">正在检索专家确认的历史病例…</Text></Card>;
  }
  if (loading === "FARM_STATUS") {
    return <Card className="farmap-copilot__command-result" size="small" title="当前农场状态"><Text type="secondary">正在读取地块、天气、诊断和任务上下文…</Text></Card>;
  }
  if (loading === "FIELD_STATUS") {
    return <Card className="farmap-copilot__command-result" size="small" title="当前地块状态"><Text type="secondary">正在读取地块、天气和近期农情…</Text></Card>;
  }
  if (loading === "DIAGNOSIS") {
    return <Card className="farmap-copilot__command-result" size="small" title="最近一次诊断"><Text type="secondary">正在读取已保存 Diagnosis 和 Evidence…</Text></Card>;
  }
  if (!result) return null;
  if (result.type === "PRIORITIES") {
    const data = result.data;
    return <Card className="farmap-copilot__command-result" size="small" title="今日优先事项">
      {data.status === "READY" ? <>
        <Text type="secondary">基于当前农场真实诊断与未完成任务</Text>
        <Flex vertical gap={8} className="farmap-copilot__command-list">
          {data.items.map((item) => <div key={`${item.kind}-${item.id}`} className="farmap-copilot__command-item">
            <Flex align="center" justify="space-between" gap={8}>
              <Text strong>{item.title}</Text>
              <Tag color={item.priority === "HIGH" ? "red" : "orange"}>{item.priority === "HIGH" ? "高" : "中"}</Tag>
            </Flex>
            <span>{item.summary}</span>
            <Button type="link" size="small" onClick={() => onOpenPath(item.path)}>{item.kind === "DIAGNOSIS" ? "查看诊断" : "查看任务"}</Button>
          </div>)}
        </Flex>
        <Button type="link" size="small" onClick={() => onOpenPath("/overview")}>查看全部</Button>
      </> : <Alert type={data.status === "EMPTY" ? "info" : "warning"} showIcon message={data.status === "EMPTY" ? "当前没有可验证的优先事项" : "优先事项数据暂不可用"} description={data.reason} />}
    </Card>;
  }
  if (result.type === "FARM_STATUS" || result.type === "FIELD_STATUS") {
    const data = result.data;
    return <Card className="farmap-copilot__command-result" size="small" title={data.title}>
      {data.status === "READY" ? <>
        {data.summary && <Text type="secondary">{data.summary}</Text>}
        <div className="farmap-copilot__evidence-heading">依据 / 证据</div>
        <Flex vertical gap={6} className="farmap-copilot__evidence-list">
          {data.evidence.map((item, index) => <div className="farmap-copilot__evidence-row" key={`${item.label}-${index}`}><span>{item.label}</span><b>{item.value}</b>{item.source && <Tag>{item.source}</Tag>}</div>)}
        </Flex>
        <div className="farmap-copilot__command-meta">来源 · {data.sources.join(" · ") || "暂不可用"}{data.updatedAt ? ` · 更新于 ${data.updatedAt}` : ""}</div>
      </> : <Alert type={data.status === "EMPTY" ? "info" : "warning"} showIcon message={data.status === "EMPTY" ? "当前没有可验证的农情信号" : "农情数据暂不可用"} description={data.reason} />}
    </Card>;
  }
  if (result.type === "DIAGNOSIS") {
    const data = result.data;
    return <Card className="farmap-copilot__command-result" size="small" title={data.title || "最近一次诊断"}>
      {data.status === "READY" ? <>
        <Flex wrap gap={6}>{data.riskLevel && <Tag color={data.riskLevel === "high" ? "red" : data.riskLevel === "medium" ? "orange" : "green"}>风险 · {data.riskLevel}</Tag>}{data.confidence !== undefined && <Tag>置信度 · {Math.round(data.confidence * 100)}%</Tag>}</Flex>
        {data.summary && <Text className="farmap-copilot__diagnosis-goal">{data.summary}</Text>}
        <div className="farmap-copilot__evidence-heading">依据 / 证据</div>
        <Flex vertical gap={6} className="farmap-copilot__evidence-list">
          {data.evidence.map((item, index) => <div className="farmap-copilot__evidence-row" key={`${item.label}-${index}`}><span>{item.label}</span><b>{item.value}</b>{item.source && <Tag>{item.source}</Tag>}</div>)}
        </Flex>
        <div className="farmap-copilot__command-meta">来源 · {data.sources.join(" · ") || "Diagnosis"}{data.updatedAt ? ` · 更新于 ${data.updatedAt}` : ""}</div>
        {data.diagnosisId && <Button type="link" size="small" onClick={() => onOpenPath(`/diagnosis/${encodeURIComponent(data.diagnosisId as string)}`)}>查看完整 Evidence</Button>}
      </> : <Alert type={data.status === "EMPTY" ? "info" : "warning"} showIcon message={data.status === "EMPTY" ? "当前没有已保存的诊断" : "诊断数据暂不可用"} description={data.reason} />}
    </Card>;
  }
  const data = result.data;
  if (data.status === "IMAGE_REQUIRED") {
    return <Card className="farmap-copilot__command-result" size="small" title="相似历史病例">
      <Alert type="info" showIcon message="需要现场图片" description={data.reason} />
      <Button className="farmap-copilot__command-action" onClick={() => onOpenDiagnosisPage("查找与当前情况最相似的历史病例")}>打开诊断页添加图片</Button>
    </Card>;
  }
  return <Card className="farmap-copilot__command-result" size="small" title="相似历史病例">
    {data.status === "READY" ? <>
      <Text type="secondary">来自真实历史病例索引 · 返回 {data.items.length} 条</Text>
      <Flex vertical gap={8} className="farmap-copilot__command-list">
        {data.items.map((item, index) => <div key={item.caseId ?? `case-${index}`} className="farmap-copilot__command-item">
          <Flex align="center" justify="space-between" gap={8}>
            <Text strong>{item.caseId ?? "未命名病例"}</Text>
            {item.similarityScore !== undefined && <Tag color="purple">匹配度 {item.similarityScore}%</Tag>}
          </Flex>
          <span>{[item.treeSpecies, item.diseaseType, item.pestType, item.nutritionStatus].filter(Boolean).join(" · ") || "专家案例，待结合当前证据核验"}</span>
          {item.updatedAt && <span className="farmap-copilot__command-meta">更新时间 · {item.updatedAt}</span>}
        </div>)}
      </Flex>
    </> : <Alert type={data.status === "EMPTY" ? "info" : "warning"} showIcon message={data.status === "EMPTY" ? "没有返回可验证的相似病例" : "历史病例检索暂不可用"} description={data.reason} />}
  </Card>;
}
