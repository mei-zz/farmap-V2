import { EnvironmentOutlined, FieldTimeOutlined, ThunderboltOutlined } from "@ant-design/icons";
import { Card, Flex, Typography } from "antd";
import type { DemoField } from "@/demo";
import StatusBadge from "./StatusBadge";

const { Text } = Typography;

export default function FieldContextCard({ field, compact = false, onClick }: {
  field: DemoField;
  compact?: boolean;
  onClick?: () => void;
}) {
  return (
    <Card className={`fm-field-context ${compact ? "fm-field-context--compact" : ""}`} bordered={false} onClick={onClick}>
      <Flex justify="space-between" align="flex-start" gap={12}>
        <div>
          <div className="fm-eyebrow"><EnvironmentOutlined /> FIELD CONTEXT</div>
          <Typography.Title level={compact ? 4 : 3} style={{ margin: "6px 0 2px" }}>{field.name}</Typography.Title>
          <Text type="secondary">{field.crop} · {field.area} ha · {field.growthStage}</Text>
        </div>
        <StatusBadge tone={field.status} label={field.statusLabel} />
      </Flex>
      {!compact && (
        <>
          <div className="fm-field-context__facts">
            <span>海拔 {field.elevation} m</span><i />
            <span>坡度 {field.slope}°</span><i />
            <span>{field.soilType}</span>
          </div>
          <div className="fm-field-context__metrics">
            <div><span>健康度</span><strong>{field.health}<small>/100</small></strong></div>
            <div><span>NDVI</span><strong>{field.ndvi.toFixed(2)}</strong></div>
            <div><span>土壤湿度</span><strong>{field.soilMoisture}%</strong></div>
            <div><span>14日降雨</span><strong>{field.rainfall14d} mm</strong></div>
          </div>
          <div className="fm-field-context__phenology"><FieldTimeOutlined /> 物候进度 <b>{field.phenologyProgress}%</b><span><em style={{ width: `${field.phenologyProgress}%` }} /></span></div>
        </>
      )}
      {compact && <div className="fm-field-context__mini"><ThunderboltOutlined /> 健康度 {field.health}<small>/100</small><span>NDVI {field.ndvi.toFixed(2)}</span></div>}
    </Card>
  );
}
