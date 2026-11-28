import { CloseOutlined } from "@ant-design/icons";
import { Drawer, Empty, Image, Tag } from "antd";
import type { CitationRef } from "@/features/copilot/messageParts";

const citationModalityLabels: Record<CitationRef["modality"], string> = {
  knowledge: "农业知识库",
  camera: "现场图像",
  historical_case: "历史病例",
  weather: "气象证据",
  field: "地块记录",
  atlas: "病虫害图鉴",
};

export type EvidenceSelection = {
  messageId: string;
  evidenceId: string;
};

export type CopilotEvidenceDrawerProps = {
  open: boolean;
  evidence?: CitationRef;
  onClose: () => void;
};

export function CopilotEvidenceDrawer({ open, evidence, onClose }: CopilotEvidenceDrawerProps) {
  return (
    <Drawer
      title="证据溯源"
      placement="right"
      width={420}
      open={open}
      onClose={onClose}
      closeIcon={<CloseOutlined />}
      className="farmap-copilot-evidence-drawer"
    >
      {evidence ? (
        <article className="farmap-evidence-detail" data-evidence-id={evidence.evidenceId}>
          <header>
            <span className="farmap-evidence-detail__index">[{evidence.evidenceId}]</span>
            <Tag color="green">{citationModalityLabels[evidence.modality]}</Tag>
            {evidence.stageLabel && <Tag>{evidence.stageLabel}</Tag>}
          </header>
          <h2>{evidence.title}</h2>
          <dl className="farmap-evidence-detail__meta">
            <div><dt>来源</dt><dd>{evidence.sourceName}</dd></div>
            {evidence.documentName && <div><dt>文档</dt><dd>{evidence.documentName}</dd></div>}
            {evidence.score !== undefined && <div><dt>匹配度</dt><dd>{Math.round(evidence.score * 100)}%</dd></div>}
            {evidence.expertStatus && <div><dt>审核状态</dt><dd>{evidence.expertStatus}</dd></div>}
          </dl>
          {evidence.snippet && (
            <section>
              <h3>检索原文</h3>
              <blockquote>{evidence.snippet}</blockquote>
            </section>
          )}
          {evidence.imageUrl && (
            <Image
              src={evidence.imageUrl}
              alt={`证据 ${evidence.evidenceId} 图像`}
              fallback="data:image/gif;base64,R0lGODlhAQABAAD/ACwAAAAAAQABAAACADs="
            />
          )}
          <small>证据用于解释回答来源，不改变任务授权、用药安全校验或人工审批。</small>
        </article>
      ) : (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description={open ? "这条证据暂未返回可展示内容。" : "选择正文引用或卡片角标查看证据。"}
        />
      )}
    </Drawer>
  );
}
