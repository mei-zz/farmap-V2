import { Tag } from "antd";
import type { OverviewMode, OverviewRecommendationState } from "@/types/overview";

const stateLabels: Record<OverviewRecommendationState, string> = {
  PROPOSED: "AI 提案",
  NEEDS_REVIEW: "需要复核",
  PENDING_APPROVAL: "待审批",
  APPROVED: "已批准",
  REJECTED: "已拒绝",
};

export default function RecommendationStatus({ state, mode }: { state: OverviewRecommendationState; mode: OverviewMode }) {
  return <Tag className={`fm-recommendation-center__status fm-recommendation-center__status--${state.toLowerCase()}`}>{mode === "DEMO" ? `演示 · ${stateLabels[state]}` : stateLabels[state]}</Tag>;
}
