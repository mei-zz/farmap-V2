import { CalendarOutlined, CloudOutlined, EnvironmentOutlined, FileSearchOutlined, LineChartOutlined, ReadOutlined } from "@ant-design/icons";
import { Card, List, Space, Statistic, Tag, Typography } from "antd";
import { useEffect, useMemo, useState } from "react";
import { permanence } from "@/utils/permanence";
import { useFarmStore } from "@/store/farm";
import { type AgriculturalContext, type AgriculturalStatus, type CurrentWeatherContext, loadAgriculturalContext } from "./context";

const { Text, Paragraph } = Typography;

const statusLabel: Record<AgriculturalStatus, string> = {
  READY: "已有真实资料",
  EMPTY: "当前农场暂无记录",
  UNAVAILABLE: "暂不可用",
  CONFIGURED_UNAVAILABLE: "已配置但暂不可用",
  NOT_IMPLEMENTED: "尚未建立",
  NOT_CONFIGURED: "尚未配置",
};

const guidanceLabel: Record<string, string> = {
  body: "树体管理",
  fertite: "水肥管理",
  pest: "病虫管理",
  park: "清园操作",
};

function DataState({ status, emptyText }: { status: AgriculturalStatus; emptyText: string }) {
  if (status === "READY") return null;
  return <div className="fm-agri-empty"><b>{statusLabel[status]}</b><span>{emptyText}</span></div>;
}

function formatDate(value?: string) {
  return value ? value.slice(0, 10) : "—";
}

function statusTag(status: AgriculturalStatus) {
  const color = status === "READY" ? "green" : status === "EMPTY" ? "default" : status === "CONFIGURED_UNAVAILABLE" ? "orange" : "blue";
  return <Tag color={color}>{statusLabel[status]}</Tag>;
}

function cropLabel(value?: string) {
  return ({ rice: "水稻", citrus: "柑橘", plum: "李子" } as Record<string, string>)[value ?? ""] ?? value ?? "未标注作物";
}

function guidanceGroups(context: AgriculturalContext) {
  const groups = new Map<string, AgriculturalContext["guidance"]["entries"]>();
  context.guidance.entries.forEach((entry) => {
    const key = entry.guidanceType ?? "other";
    groups.set(key, [...(groups.get(key) ?? []), entry]);
  });
  return [...groups.entries()];
}

function CurrentWeatherCard({ weather }: { weather: CurrentWeatherContext }) {
  const ready = weather.status === "READY";
  return <Card className="fm-agri-card fm-agri-weather-card" title={<><CloudOutlined />&nbsp;实时天气</>} extra={ready ? <Tag color="green">实时来源</Tag> : <Tag color="orange">{statusLabel[weather.status]}</Tag>}>
    {ready ? <>
      <div className="fm-agri-weather-value"><strong>{weather.temperature ?? "—"}°C</strong><span>{weather.condition ?? "—"}</span></div>
      <Text type="secondary">湿度 {weather.humidity ?? "—"} · {weather.windDirection ?? "—"}{weather.windPower ? ` ${weather.windPower}` : ""}</Text>
      <Text type="secondary">未来24小时降雨 {weather.rainfallForecast24h !== undefined ? `${weather.rainfallForecast24h.toFixed(1)} mm` : "—"}{weather.rainfallProbability24h !== undefined ? ` · ${weather.rainfallProbability24h}%概率` : ""} · 未来{weather.rainfallForecastDays ?? 14}天 {weather.rainfall14d !== undefined ? `${weather.rainfall14d.toFixed(1)} mm` : "—"}</Text>
      <div className="fm-agri-card__note">来源：{weather.provider ?? "实时天气服务"} · 更新 {weather.observedAt ?? formatDate(weather.retrievedAt)} · 预测请结合现场观测</div>
    </> : <div className="fm-agri-empty"><b>{weather.status === "NOT_CONFIGURED" ? "天气服务尚未配置" : "天气服务已配置但暂不可用"}</b><span>{weather.reason ?? "当前没有可信实时观测。"}</span></div>}
  </Card>;
}

export type AgriculturalContextPanelProps = {
  farmId: number;
  context?: AgriculturalContext;
  compact?: boolean;
  onOpenDiagnosis?: (diagnosisId: string) => void;
  onCurrentWeather?: (weather: CurrentWeatherContext) => void;
};

export default function AgriculturalContextPanel({ farmId, context: initialContext, compact = false, onOpenDiagnosis, onCurrentWeather }: AgriculturalContextPanelProps) {
  const token = permanence.token.useToken();
  const farmName = useFarmStore((state) => state.name);
  const farmType = useFarmStore((state) => state.type);
  const farmLocations = useFarmStore((state) => state.locations);
  const [context, setContext] = useState<AgriculturalContext | undefined>(initialContext);
  const [loading, setLoading] = useState(!initialContext && farmId > 0);
  const [error, setError] = useState<string>();

  useEffect(() => {
    let active = true;
    if (initialContext) {
      setContext(initialContext);
      setLoading(false);
      setError(undefined);
      return () => { active = false; };
    }
    if (farmId <= 0) {
      setContext(undefined);
      setLoading(false);
      return () => { active = false; };
    }
    setLoading(true);
    loadAgriculturalContext(farmId, token, { temperatureLimit: compact ? 30 : 90, diagnosisLimit: 5 })
      .then((value) => { if (active) { setContext(value); setError(undefined); } })
      .catch((reason: unknown) => { if (active) setError(reason instanceof Error ? reason.message : "农业上下文加载失败"); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [compact, farmId, initialContext, token]);

  const temperatureBars = useMemo(() => {
    const points = context?.historicalTemperature.series ?? [];
    const recent = points.slice(-30);
    const values = recent.map((point) => point.accumulatedTemperature ?? 0);
    const max = Math.max(...values, 1);
    return recent.map((point, index) => ({ key: `${point.date ?? "point"}-${index}`, height: Math.max(8, ((point.accumulatedTemperature ?? 0) / max) * 100) }));
  }, [context?.historicalTemperature.series]);

  useEffect(() => {
    if (context?.currentWeather) onCurrentWeather?.(context.currentWeather);
  }, [context?.currentWeather, onCurrentWeather]);

  if (loading) return <section className="fm-agricultural-context"><Card loading title="真实农业数据" /></section>;
  if (error || !context) return <section className="fm-agricultural-context"><Card title="真实农业数据"><div className="fm-agri-error">{error ?? "当前农场上下文尚未加载。"}</div></Card></section>;

  const { seasonalContext: seasonal, historicalTemperature: temperature, guidance, cropObservations: crops, recentDiagnoses: diagnoses, availability, healthPrerequisites, currentWeather } = context;
  const groups = guidanceGroups(context);
  const primaryCrop = crops.cropTypes[0] ?? farmType ?? seasonal.farmType;
  const coverage = [
    ["map", "已有地图数据"],
    ["field", "已确认地块"],
    ["cropObservations", "已有作物观测"],
    ["seasonalContext", "季节农情"],
    ["historicalTemperature", "历史积温"],
    ["guidance", "农业指导"],
    ["diagnosis", "诊断记录"],
    ["operations", "农事任务"],
    ["realtimeWeather", "实时天气"],
    ["realtimeCamera", "实时监控"],
    ["vision", "AI视觉分析"],
    ["sensor", "传感器"],
    ["soil", "土壤数据"],
  ] as const;
  const renderGuidance = () => guidance.status === "READY" ? <div className="fm-agri-guidance-list">
    {groups.map(([type, entries]) => <details key={type} open={!compact} className="fm-agri-guidance-group">
      <summary><Tag>{guidanceLabel[type] ?? "农业指导"}</Tag><span>{entries.length} 条真实指导</span></summary>
      <div>{entries.map((entry) => <p key={entry.id ?? `${type}-${entry.text.slice(0, 12)}`}>{entry.formula ? <b>配方推荐：</b> : <b>操作建议：</b>}{entry.text}</p>)}</div>
    </details>)}
  </div> : <DataState status={guidance.status} emptyText="当前农场暂无本月农业指导。" />;

  return (
    <section className={`fm-agricultural-context${compact ? " fm-agricultural-context--compact" : ""}`} aria-labelledby="agricultural-context-title">
      <div className="fm-agricultural-context__header">
        <div>
          <div className="fm-eyebrow">REAL AGRICULTURAL CONTEXT</div>
          <Typography.Title level={3} id="agricultural-context-title">真实农业数据</Typography.Title>
          <Text type="secondary">只展示当前农场已有的资料；缺少的能力保持紧凑且诚实。</Text>
        </div>
        <Tag icon={<EnvironmentOutlined />}>farm {farmId}</Tag>
      </div>

      <div className="fm-agricultural-context__grid">
        {compact && <Card className="fm-agri-card fm-agri-primary-card" title="农场摘要">
          <div className="fm-agri-farm-summary"><strong>{farmName || `farm ${farmId}`}</strong><span>{cropLabel(primaryCrop)} · {crops.totalObservations} 条作物观测 · 最新 {formatDate(crops.latestObservationDate)}</span></div>
          {crops.status === "READY" && farmLocations.length === 0 && <div className="fm-agri-card__note">当前农场暂无边界数据，已显示 {crops.totalObservations} 个作物观测点。</div>}
        </Card>}

        <CurrentWeatherCard weather={currentWeather} />

        <Card className="fm-agri-card" title={<><EnvironmentOutlined />&nbsp;作物观测摘要</>} extra={statusTag(crops.status)}>
          {crops.status === "READY" ? <>
            <div className="fm-agri-stat-grid fm-agri-stat-grid--four">
              <Statistic title="观测数量" value={crops.totalObservations} />
              <Statistic title="有效产量" value={crops.validYieldCount || "—"} />
              <Statistic title="有效养分" value={crops.validNutrientCount || "—"} />
              <Statistic title="图片记录" value={crops.imageCount} />
            </div>
            <Text type="secondary">{crops.cropTypes.map(cropLabel).join("、") || "作物未标注"} · {formatDate(crops.startDate)} → {formatDate(crops.endDate)}</Text>
            <div className="fm-agri-card__note">病害信号：{crops.diseaseSignalCount > 0 ? `${crops.diseaseSignalCount} 条可用记录` : "缺少可信病害记录"}。该摘要不计算健康评分。</div>
          </> : <DataState status={crops.status} emptyText="当前农场暂无作物观测记录。" />}
        </Card>

        {(!compact || seasonal.status === "READY") && <Card className="fm-agri-card" title={<><CalendarOutlined />&nbsp;本月农情</>} extra={statusTag(seasonal.status)}>
          {seasonal.status === "READY" ? <><Text type="secondary">{seasonal.month + 1} 月 · 季节性参考 · 来源：历史季节资料</Text><Paragraph className="fm-agri-card__prose">{seasonal.summary}</Paragraph></> : <DataState status={seasonal.status} emptyText="当前农场暂无本月季节农情资料。" />}
        </Card>}

        {(!compact || temperature.status === "READY") && <Card className="fm-agri-card" title={<><LineChartOutlined />&nbsp;历史积温</>} extra={statusTag(temperature.status)}>
          {temperature.status === "READY" ? <><div className="fm-agri-stat-grid"><Statistic title="最新积温" value={temperature.latestAccumulatedTemperature ?? "—"} /><Statistic title="观测数量" value={temperature.observationCount} /></div><Text type="secondary">{formatDate(temperature.startDate)} → {formatDate(temperature.endDate)} · 最新 {formatDate(temperature.latestDate)}</Text><div className="fm-agri-bars" aria-label="历史积温趋势">{temperatureBars.map((bar) => <i key={bar.key} style={{ height: `${bar.height}%` }} />)}</div></> : <DataState status={temperature.status} emptyText="当前农场暂无历史积温记录。" />}
        </Card>}

        {(!compact || guidance.status === "READY") && <Card className="fm-agri-card fm-agri-guidance-card" title={<><ReadOutlined />&nbsp;当前农业指导</>} extra={statusTag(guidance.status)}>{renderGuidance()}</Card>}

        {(!compact || diagnoses.status === "READY") && <Card className="fm-agri-card" title={<><FileSearchOutlined />&nbsp;最近诊断</>} extra={statusTag(diagnoses.status)}>
          {diagnoses.status === "READY" ? <List size="small" dataSource={diagnoses.items} renderItem={(item) => <List.Item className={onOpenDiagnosis ? "fm-agri-diagnosis-item" : undefined} onClick={() => onOpenDiagnosis?.(item.diagnosisId)}><div><b>{item.status === "COMPLETED" ? "已完成诊断" : item.status ?? "诊断记录"}</b><small>{formatDate(item.createdAt)} · {item.fieldId ?? "未关联地块"}</small><span>{item.summary || "已保存诊断快照，可进入详情查看。"}</span></div></List.Item>} /> : <DataState status={diagnoses.status} emptyText="当前农场暂无可验证的诊断记录。" />}
          <Text type="secondary">列表仅供浏览；点击后按 diagnosisId 查看详情，不自动选择当前诊断。</Text>
        </Card>}

        <Card className="fm-agri-card fm-agri-card--availability" title="数据覆盖">
          <Space wrap size={[6, 6]}>{coverage.map(([key, label]) => { const value = availability[key]; return <Tag key={key} color={value === "READY" ? "green" : value === "EMPTY" ? "default" : "orange"}>{label} · {statusLabel[value]}</Tag>; })}</Space>
          <div className="fm-agri-health-boundary"><b>健康综合评分尚未启用</b><span>可用真实信号：{healthPrerequisites.availableSignals.join("、") || "暂无"}</span><span>缺少信号：{healthPrerequisites.missingSignals.join("、") || "暂无"}</span></div>
        </Card>
      </div>
    </section>
  );
}
