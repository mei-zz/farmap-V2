import { CloudOutlined, DownOutlined, FileSearchOutlined } from "@ant-design/icons";
import { Tag, Typography } from "antd";
import type { DemoEvidence } from "@/demo";

const { Text } = Typography;

export default function EvidenceCard({ evidence, index }: { evidence: DemoEvidence; index: number }) {
  const visual = evidence.category === "监控图像" || evidence.category === "历史案例" ? (
    <img src={evidence.asset ?? ""} alt={evidence.title} />
  ) : evidence.category === "气象数据" ? (
    <div className="fm-evidence-visual fm-evidence-visual--weather"><span><i style={{ height: "35%" }} /><i style={{ height: "52%" }} /><i style={{ height: "42%" }} /><i style={{ height: "76%" }} /><i style={{ height: "63%" }} /></span><b>58 mm</b><small>14 天降雨</small></div>
  ) : evidence.category === "土壤数据" ? (
    <div className="fm-evidence-visual fm-evidence-visual--soil"><b>84%</b><small>Soil Sensor</small><span><i /><i /><i /><i /><i /></span></div>
  ) : evidence.category === "农业知识" ? (
    <div className="fm-evidence-visual fm-evidence-visual--knowledge"><b>§5.3</b><small>《柑橘栽培技术规程》</small><span>根域长期高湿会降低根系活力……</span></div>
  ) : (
    <div className="fm-evidence-visual fm-evidence-visual--space"><CloudOutlined /><b>GIS</b><small>空间分析</small></div>
  );

  return (
    <details className="fm-evidence-card">
      <summary>
        <span className="fm-evidence-card__index">{index + 1}</span>
        <span className="fm-evidence-card__visual">{visual}</span>
        <span className="fm-evidence-card__body">
          <strong>{evidence.title}</strong>
          <small>{evidence.source} · {evidence.observedAt}</small>
        </span>
        <Tag className={`fm-evidence-card__weight fm-evidence-card__weight--${evidence.weight}`}>{evidence.category}</Tag>
        <DownOutlined className="fm-evidence-card__arrow" />
      </summary>
      <div className="fm-evidence-card__detail">
        <div className="fm-evidence-card__claim"><FileSearchOutlined /> <b>支持结论：</b>{evidence.claim}</div>
        <Text type="secondary">{evidence.detail}</Text>
      </div>
    </details>
  );
}
