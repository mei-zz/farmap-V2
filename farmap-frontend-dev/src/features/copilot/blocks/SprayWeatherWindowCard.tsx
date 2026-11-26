import { CloudRain, Droplets, Leaf, Sun } from "lucide-react";
import type { AgentUiBlock } from "../uiBlocks";

export type SprayWeatherWindow = {
  id: string;
  startsAt: string;
  endsAt?: string;
  score: number;
  wind?: number;
  rain?: number;
  rainProbability?: number;
  temperature?: number;
  humidity?: number;
};

export type WorkSuitabilityLevel = "suitable" | "caution" | "unsuitable" | "unknown";

export type WorkSuitability = {
  level: WorkSuitabilityLevel;
  score?: number;
  reason?: string;
};

export type WorkDayAssessment = {
  date: string;
  spray?: WorkSuitability;
  irrigation?: WorkSuitability;
  harvest?: WorkSuitability;
};

export type SprayWeatherData = {
  source?: string;
  rainHorizonHours?: number;
  restrictionsVerified: boolean;
  windows: SprayWeatherWindow[];
  workDays?: WorkDayAssessment[];
  operationDecision?: string;
  phiDays?: number;
  verifiedProductName?: string;
};

const levelLabels: Record<WorkSuitabilityLevel, string> = {
  suitable: "适宜",
  caution: "谨慎",
  unsuitable: "不宜",
  unknown: "待核验",
};

const operations = [
  { key: "spray" as const, label: "打药", Icon: Droplets },
  { key: "irrigation" as const, label: "施肥灌水", Icon: CloudRain },
  { key: "harvest" as const, label: "采收", Icon: Leaf },
];

function localHour(value: string) {
  return value.replace("T", " ").slice(5, 16);
}

function localDay(value: string) {
  return value.slice(0, 10);
}

function shortDay(value: string) {
  const match = value.match(/^\d{4}-(\d{2})-(\d{2})$/u);
  return match ? `${Number(match[1])}月${Number(match[2])}日` : value;
}

function scoreLevel(score: number): WorkSuitabilityLevel {
  if (score >= 75) return "suitable";
  if (score >= 45) return "caution";
  return "unsuitable";
}

function inferredDays(windows: SprayWeatherWindow[]): WorkDayAssessment[] {
  const grouped = new Map<string, SprayWeatherWindow[]>();
  for (const window of windows) {
    const day = localDay(window.startsAt);
    if (!day) continue;
    const entries = grouped.get(day) ?? [];
    entries.push(window);
    grouped.set(day, entries);
  }
  return [...grouped.entries()].sort(([left], [right]) => left.localeCompare(right)).slice(0, 3).map(([date, entries]) => {
    const best = entries.reduce((current, item) => item.score > current.score ? item : current);
    return {
      date,
      spray: { level: scoreLevel(best.score), score: best.score, reason: `最优气象评分 ${best.score}` },
    };
  });
}

function SuitabilityCell({ assessment }: { assessment?: WorkSuitability }) {
  if (!assessment) return <td data-level="unknown"><span className="farmap-spray-weather__level">待核验</span><small>暂无评估</small></td>;
  return <td data-level={assessment.level}>
    <span className="farmap-spray-weather__level">{levelLabels[assessment.level]}</span>
    {assessment.reason && <small>{assessment.reason}</small>}
  </td>;
}

export function SprayWeatherWindowCard({ block }: { block: AgentUiBlock }) {
  const info = block.data?.sprayWeather;
  if (!info) return <p>暂无可用于评估的逐小时预报。</p>;
  const workDays = info.workDays?.length ? info.workDays.slice(0, 3) : inferredDays(info.windows);

  return <section className="farmap-spray-weather" aria-label="未来农事作业气象窗口">
    <div className="farmap-spray-weather__notice" data-verified={info.restrictionsVerified}>
      <Sun size={16} aria-hidden="true" />
      <p>{!info.restrictionsVerified
        ? "标签、安全间隔期和认证条件仍待核验；矩阵只反映已提供的气象条件，不能替代施药安全审核。"
        : "气象条件已通过作业窗口评估，现场仍需核对标签、安全间隔期和当时天气。"}</p>
    </div>

    {workDays.length > 0 ? <div className="farmap-spray-weather__matrix-wrap">
      <table className="farmap-spray-weather__matrix">
        <caption>未来三天农事适宜度</caption>
        <thead><tr><th scope="col">日期</th>{operations.map(({ key, label, Icon }) =>
          <th key={key} scope="col"><Icon size={14} aria-hidden="true" />{label}</th>)}</tr></thead>
        <tbody>{workDays.map((day) => <tr key={day.date}>
          <th scope="row"><time dateTime={day.date}>{shortDay(day.date)}</time></th>
          <SuitabilityCell assessment={day.spray} />
          <SuitabilityCell assessment={day.irrigation} />
          <SuitabilityCell assessment={day.harvest} />
        </tr>)}</tbody>
      </table>
    </div> : <p>关键气象字段不足，暂无逐日农事适宜度矩阵。</p>}

    {info.windows.length > 0 ? <details className="farmap-spray-weather__windows" open>
      <summary>查看逐小时气象窗口</summary>
      <div className="farmap-spray-weather__table">
        <table>
          <thead><tr><th scope="col">开始时间</th><th scope="col">气象评分</th><th scope="col">风速</th><th scope="col">随后 {info.rainHorizonHours ?? 6} 小时雨量</th></tr></thead>
          <tbody>{info.windows.map((window) => <tr key={window.id}>
            <td>{localHour(window.startsAt)}</td>
            <td><span className={`farmap-spray-weather__score is-${scoreLevel(window.score)}`}>{window.score}</span><span aria-hidden="true"> / 100</span></td>
            <td>{window.wind === undefined ? "缺测" : `${window.wind.toFixed(1)} km/h`}</td>
            <td>{window.rain === undefined ? "缺测" : `${window.rain.toFixed(1)} mm`}</td>
          </tr>)}</tbody>
        </table>
      </div>
    </details> : <p>关键气象字段不足，暂无法评分。</p>}

    {(info.operationDecision || info.phiDays !== undefined || info.verifiedProductName) && <dl className="farmap-spray-weather__decision">
      {info.operationDecision && <div><dt>作业判定</dt><dd>{info.operationDecision}</dd></div>}
      {info.verifiedProductName && <div><dt>已核验药剂</dt><dd>{info.verifiedProductName}</dd></div>}
      {info.phiDays !== undefined && <div><dt>安全间隔期</dt><dd>{info.phiDays} 天</dd></div>}
    </dl>}
    {info.source && <small>气象来源 {info.source} · 北京时间</small>}
  </section>;
}
