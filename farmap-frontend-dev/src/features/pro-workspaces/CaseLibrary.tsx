import { useEffect, useState } from "react";
import { Alert, Button, Empty, Spin, Tag } from "antd";
import { useNavigate } from "react-router";
import { useAIContextStore } from "@/store/aiContext";
import { permanence } from "@/utils/permanence";
import { req } from "@/utils/reqeust";
type Case = {
  id: string;
  title: string;
  summary?: string;
  diagnosisId?: string;
  fieldId?: string;
  updatedAt?: string;
  status?: string;
};
export default function CaseLibrary() {
  const farmId = useAIContextStore((s) => s.context.currentFarm.id);
  const fieldId = useAIContextStore((s) => s.context.currentField?.id);
  const [items, setItems] = useState<Case[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const navigate = useNavigate();
  useEffect(() => {
    let active = true;
    setItems([]);
    setError(undefined);
    if (farmId <= 0)
      return () => {
        active = false;
      };
    setLoading(true);
    const params = new URLSearchParams({ farmId: String(farmId) });
    if (fieldId) params.set("fieldId", fieldId);
    req
      .get<{ data?: { items?: Case[] }; msg?: string }>(
        `/api/historical-cases/library?${params}`,
        { Authorization: `Bearer ${permanence.token.useToken()}` },
      )
      .then((res) => {
        if (!res.data || !Array.isArray(res.data.items))
          throw new Error(res.msg || "历史案例不可用");
        if (active) setItems(res.data.items);
      })
      .catch((e: unknown) => {
        if (active) setError(e instanceof Error ? e.message : "历史案例不可用");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [farmId, fieldId]);
  if (farmId <= 0) return <Empty description="请选择农场" />;
  if (loading) return <Spin />;
  if (error) return <Alert type="error" showIcon title={error} />;
  if (!items.length) return <Empty description="当前范围暂无历史案例" />;
  return (
    <div>
      {items.map((item) => (
        <div className="fm-pro-suggestion" key={item.id}>
          <div>
            <b>{item.title}</b> {item.status && <Tag>{item.status}</Tag>}
          </div>
          {item.summary && <p>{item.summary}</p>}
          <small>{item.updatedAt}</small>
          {item.diagnosisId && (
            <Button
              type="link"
              onClick={() =>
                navigate(
                  `/diagnosis/${encodeURIComponent(item.diagnosisId as string)}`,
                )
              }
            >
              查看关联诊断
            </Button>
          )}
        </div>
      ))}
    </div>
  );
}
