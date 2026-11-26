import { getLatestMaturity, type MaturityReport } from "@/features/maturity/service";
import { useEffect, useState } from "react";
import { Alert, Button, Card, Empty, Spin, Typography } from "antd";
import { useNavigate } from "react-router";
import {
  loadAgriculturalContext,
  type AgriculturalDiagnosisSummary,
} from "@/features/agricultural/context";
import { getDiagnosis } from "@/features/diagnosis/service";
import type { DiagnosisRecommendation } from "@/features/diagnosis/contract";
import { permanence } from "@/utils/permanence";
import { useAIContextStore } from "@/store/aiContext";
import { useCopilotStore } from "@/store/copilot";

type Entry = {
  diagnosis: AgriculturalDiagnosisSummary;
  recommendations: DiagnosisRecommendation[];
  unavailable?: boolean;
};
export default function PlanningRecommendations({
  farmId,
  scopeFieldId,
  compact = false,
}: {
  farmId: number;
  scopeFieldId?: string | null;
  compact?: boolean;
}) {
  const [maturity, setMaturity] = useState<MaturityReport>();
  const [entries, setEntries] = useState<Entry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const navigate = useNavigate();
  const selectedFieldId = useAIContextStore((state) => state.context.currentField?.id);
  const fieldId = scopeFieldId === undefined ? selectedFieldId : scopeFieldId ?? undefined;
  useEffect(() => {
    let active = true;
    setMaturity(undefined);
    if (farmId > 0) void getLatestMaturity(farmId).then((report) => {
      if (!active || (report.kind && report.kind !== "MATURITY")) return;
      if (fieldId && !report.parcels?.some((parcel) => parcel.fieldId === fieldId
        && parcel.observations?.some((item) => item.status === "SUCCESS" && /^(RIPE|OVER_RIPE)$/.test(item.stage ?? "")))) return;
      setMaturity(report);
    }).catch(() => undefined);
    return () => { active = false; };
  }, [farmId, fieldId]);
  useEffect(() => {
    let active = true;
    if (farmId <= 0) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(undefined);
    loadAgriculturalContext(farmId, permanence.token.useToken(), {
      diagnosisLimit: compact ? 4 : 10,
      fieldId,
    })
      .then(async (context) => {
        if (context.recentDiagnoses.status === "UNAVAILABLE")
          throw new Error("诊断记录暂不可用");
        const data = await Promise.all(
          context.recentDiagnoses.items
            .filter((diagnosis) => !fieldId || diagnosis.fieldId === fieldId)
            .map(async (diagnosis) => {
              try {
                const snapshot = await getDiagnosis(diagnosis.diagnosisId);
                return {
                  diagnosis,
                  recommendations: snapshot.recommendations ?? [],
                };
              } catch {
                return { diagnosis, recommendations: [], unavailable: true };
              }
            }),
        );
        if (active) setEntries(data);
      })
      .catch((e: unknown) => {
        if (active)
          setError(e instanceof Error ? e.message : "诊断记录暂不可用");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [farmId, fieldId, compact]);
  return (
    <Card className="fm-pro-card" title="诊断与农事建议">
      <Typography.Paragraph type="secondary">
        建议需要人工确认；进入 Agent 分析后可处理需要批准的行动。
      </Typography.Paragraph>
      <div className="fm-pro-suggestion">
        <b>成熟度与采收建议</b>
        <p>{(fieldId ? maturity?.parcels?.find((parcel) => parcel.fieldId === fieldId)?.reason : maturity?.summary) || "当前范围暂无可用的采收建议"}</p>
        {maturity?.recommendations?.map((item, i) => <p key={i}>{typeof item === "string" ? item : [item.title, item.detail].filter(Boolean).join("：")}</p>)}
        {maturity?.recommendations?.length ? <Button type="link" onClick={() => {
          const copilot = useCopilotStore.getState();
          copilot.openAnalysis({ runId: maturity.runId, farmId, fieldId });
          copilot.setDraft("根据当前成熟度报告，帮我制定采收复核计划草稿。");
        }}>在 Agent 中安排复核</Button> : null}
      </div>
      {loading ? (
        <Spin />
      ) : error ? (
        <Alert type="error" showIcon title={error} />
      ) : !entries.length ? (
        <Empty description="当前农场暂无诊断记录" />
      ) : (
        entries.map(({ diagnosis, recommendations, unavailable }) => (
          <div className="fm-pro-suggestion" key={diagnosis.diagnosisId}>
            <div>
              <b>{diagnosis.crop || "农事诊断"}</b>
              <span>{diagnosis.createdAt}</span>
            </div>
            <p>{diagnosis.summary || "诊断摘要未返回"}</p>
            {unavailable && <Alert type="warning" title="诊断详情暂不可用" />}
            {recommendations.map((item) => (
              <details key={item.id}>
                <summary>{item.title}</summary>
                <p>{item.detail}</p>
                <small>关联依据 {item.evidence_ids?.length ?? 0} 条</small>
              </details>
            ))}
            <Button
              type="link"
              onClick={() =>
                navigate(
                  `/diagnosis/${encodeURIComponent(diagnosis.diagnosisId)}`,
                )
              }
            >
              查看诊断报告
            </Button>
          </div>
        ))
      )}
    </Card>
  );
}

