import { EnvironmentOutlined } from "@ant-design/icons";
import { Flex, Typography } from "antd";
import type { OverviewMap, OverviewMode, OverviewRiskPriority } from "@/types/overview";
import MapContainer from "@/views/dashboard/Map";

const { Text, Title } = Typography;

export default function FarmRiskMapPanel({ map, selectedRisk, mode }: { map: OverviewMap; selectedRisk?: OverviewRiskPriority; mode: OverviewMode }) {
  const center = selectedRisk?.mapReference?.center ?? map.center;
  const zoom = selectedRisk?.mapReference?.zoom ?? map.zoom;
  const fieldLabel = selectedRisk?.fieldName ?? "全农场风险范围";

  return (
    <section className="fm-risk-map-panel" aria-labelledby="risk-map-panel-title">
      <Flex className="fm-section-heading" justify="space-between" align="center">
        <div>
          <div className="fm-eyebrow">SPATIAL RISK VIEW</div>
          <Title level={4} id="risk-map-panel-title">风险空间态势</Title>
          <Text type="secondary">选择风险后，地图聚焦对应地块。</Text>
        </div>
        <span className={`fm-risk-map-panel__mode fm-risk-map-panel__mode--${mode.toLowerCase()}`}>{mode === "DEMO" ? "演示地图" : mode === "STALE" ? "数据较旧" : mode === "UNAVAILABLE" ? "风险数据不可用" : "风险地图"}</span>
      </Flex>
      <div className="fm-risk-map-frame">
        <MapContainer
          center={center}
          zoom={zoom}
          crops={map.crops}
          farmLocations={map.locations}
          infoKey="growth"
          modeKey="crop"
          slider={{ value: { left: 0, right: 100 }, scale: { min: 0, max: 100 }, decimal: false }}
          readOnly
        />
        <div className="fm-risk-map-frame__legend"><span><i className="fm-dot fm-dot--green" />健康</span><span><i className="fm-dot fm-dot--yellow" />需关注</span><span><i className="fm-dot fm-dot--red" />风险</span></div>
        {selectedRisk ? (
          <div className="fm-risk-map-frame__selection">
            <div className="fm-eyebrow">SELECTED FIELD</div>
            <strong>{fieldLabel}</strong>
            <small>{selectedRisk.crop ?? "全农场"}{selectedRisk.fieldArea !== undefined ? ` · ${selectedRisk.fieldArea} ha` : ""}</small>
            <span><EnvironmentOutlined /> {selectedRisk.typeLabel ?? "风险信号"}</span>
          </div>
        ) : (
          <div className="fm-risk-map-frame__empty">选择右侧风险以聚焦地图</div>
        )}
      </div>
      <div className="fm-risk-map-panel__note"><EnvironmentOutlined /> {selectedRisk?.mapReference ? `已聚焦 ${fieldLabel} · 地图点击选择暂不可用` : "当前为全农场视角，选中具体地块风险后将自动聚焦"}</div>
    </section>
  );
}
