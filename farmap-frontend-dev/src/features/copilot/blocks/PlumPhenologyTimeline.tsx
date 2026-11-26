import { AlertTriangle, Check, Gauge, MapPinned } from "lucide-react";
import type { AgentUiBlock, DraftTaskPayload } from "../uiBlocks";

export type PhenologyStage = {
  code?: string;
  label: string;
  from?: string;
  to?: string;
  threshold?: number;
};

export type PhenologyAccumulation = {
  current: number;
  target?: number;
  progress?: number;
  unit?: string;
};

export type PhenologyTimelineData = {
  fieldId?: string;
  fieldName?: string;
  crop?: string;
  date?: string;
  current?: string;
  previous?: string;
  next?: string;
  currentIndex?: number;
  stages?: PhenologyStage[];
  daysToNext?: number;
  registered?: string;
  seasonal?: string;
  review?: boolean;
  accumulation?: PhenologyAccumulation;
};

const plumStages: PhenologyStage[] = [
  { code: "dormancy", label: "休眠期" },
  { code: "germination", label: "萌芽期" },
  { code: "budding", label: "花蕾期" },
  { code: "flowering", label: "花期" },
  { code: "leafing", label: "展叶期" },
  { code: "fruiting", label: "坐果期" },
  { code: "fruit_development", label: "果实发育期" },
  { code: "ripening", label: "成熟期" },
  { code: "harvest", label: "采收期" },
  { code: "post_harvest", label: "采后恢复期" },
];

const stageAliases: Record<string, string[]> = {
  dormancy: ["越冬", "休眠"],
  germination: ["萌动", "花芽萌动", "萌芽"],
  budding: ["现蕾", "露白", "花蕾"],
  flowering: ["初花", "盛花", "末花", "开花"],
  leafing: ["展叶", "春梢"],
  fruiting: ["幼果", "谢花坐果", "坐果"],
  fruit_development: ["幼果膨大", "硬核期", "膨大", "果实发育"],
  ripening: ["转色", "软化", "成熟"],
  harvest: ["采摘", "上市", "采收"],
  post_harvest: ["采后", "恢复期"],
};

function normalized(value?: string) {
  return value?.trim().toLocaleLowerCase("zh-CN").replace(/[\s_\-—–/（）()]+/gu, "") ?? "";
}

function stageMatch(stage: PhenologyStage, value?: string) {
  const target = normalized(value);
  if (!target) return false;
  const aliases = stage.code ? stageAliases[stage.code] ?? [] : [];
  return [stage.code, stage.label, ...aliases].some((candidate) => {
    const source = normalized(candidate);
    return Boolean(source) && (source === target || source.includes(target) || target.includes(source));
  });
}

function findStageIndex(stages: PhenologyStage[], value?: string) {
  return stages.findIndex((stage) => stageMatch(stage, value));
}

function shortDate(value?: string) {
  if (!value) return undefined;
  const iso = value.match(/\d{4}-(\d{2})-(\d{2})/u);
  if (iso) return `${iso[1]}/${iso[2]}`;
  const monthDay = value.match(/^(\d{1,2})-(\d{1,2})$/u);
  return monthDay ? `${monthDay[1].padStart(2, "0")}/${monthDay[2].padStart(2, "0")}` : value.slice(0, 18);
}

function daysUntil(from: string | undefined, date: string | undefined) {
  if (!from || !date) return undefined;
  const start = new Date(`${date.slice(0, 10)}T12:00:00+08:00`);
  if (!Number.isFinite(start.getTime())) return undefined;
  const monthDay = from.match(/(?:^|\D)(\d{1,2})-(\d{1,2})$/u);
  if (!monthDay) return undefined;
  const month = Number(monthDay[1]);
  const day = Number(monthDay[2]);
  if (month < 1 || month > 12 || day < 1 || day > 31) return undefined;
  let target = new Date(start.getFullYear(), month - 1, day, 12);
  if (target.getTime() < start.getTime()) target = new Date(start.getFullYear() + 1, month - 1, day, 12);
  return Math.max(0, Math.round((target.getTime() - start.getTime()) / 86_400_000));
}

function progressValue(accumulation: PhenologyAccumulation) {
  if (accumulation.progress !== undefined && Number.isFinite(accumulation.progress)) {
    return Math.max(0, Math.min(100, accumulation.progress));
  }
  if (accumulation.target && accumulation.target > 0) {
    return Math.max(0, Math.min(100, accumulation.current / accumulation.target * 100));
  }
  return undefined;
}

type Props = {
  block: AgentUiBlock;
  onDraftTask?: (payload: DraftTaskPayload) => void;
};

export function PlumPhenologyTimeline({ block, onDraftTask }: Props) {
  const phase = block.data?.phenologyTimeline;
  if (!phase) return <p>当前地块没有可核验的物候记录。</p>;

  const stages = phase.stages?.length ? phase.stages : plumStages;
  const explicitIndex = phase.currentIndex !== undefined && phase.currentIndex >= 0 && phase.currentIndex < stages.length
    ? phase.currentIndex : undefined;
  const matchedIndex = findStageIndex(stages, phase.current);
  const currentIndex = explicitIndex ?? matchedIndex;
  const current = currentIndex >= 0 ? stages[currentIndex] : undefined;
  const next = phase.next ?? (currentIndex >= 0 ? stages[currentIndex + 1]?.label : undefined);
  const nextStage = currentIndex >= 0 ? stages[currentIndex + 1] : undefined;
  const countdown = phase.daysToNext ?? daysUntil(nextStage?.from, phase.date);
  const accumulation = phase.accumulation;
  const accumulationProgress = accumulation ? progressValue(accumulation) : undefined;
  const registeredIndex = findStageIndex(stages, phase.registered);
  const seasonalIndex = findStageIndex(stages, phase.seasonal);
  const mismatch = phase.review === true
    || Boolean(phase.registered && phase.seasonal && normalized(phase.registered) !== normalized(phase.seasonal));
  const fieldId = block.data?.fieldId ?? phase.fieldId;
  const calibration = mismatch && fieldId ? {
    fieldId,
    title: "校准地块物候",
    detail: `登记物候：${phase.registered ?? "未登记"}；系统推算：${phase.seasonal ?? phase.current ?? "待复核"}。请现场复核后更新地块物候。`,
  } satisfies DraftTaskPayload : undefined;

  return <section className="farmap-phenology" aria-label="脆李十期物候时间线">
    <div className="farmap-phenology__meta">
      <span><MapPinned size={14} aria-hidden="true" />{[phase.fieldName, phase.crop].filter(Boolean).join(" · ") || "当前地块"}</span>
      {phase.date && <time>{phase.date}</time>}
    </div>

    {mismatch && <div className="farmap-phenology__conflict" role="status">
      <AlertTriangle size={16} aria-hidden="true" />
      <div>
        <strong>物候记录存在冲突</strong>
        <span>地块登记 {phase.registered ?? "缺失"}，历法/积温推算 {phase.seasonal ?? "缺失"}。请现场复核。</span>
      </div>
      {calibration && <button type="button" disabled={!onDraftTask} onClick={() => onDraftTask?.(calibration)}>一键校准地块物候</button>}
    </div>}

    <div className="farmap-phenology__scroller">
      <ol className="farmap-phenology__track" aria-label="脆李全年物候阶段">
        {stages.map((stage, index) => {
          const state = currentIndex < 0 ? "upcoming" : index < currentIndex ? "done" : index === currentIndex ? "current" : "upcoming";
          const dateRange = [shortDate(stage.from), shortDate(stage.to)].filter(Boolean).join("–");
          return <li key={`${stage.code ?? stage.label}-${index}`} data-state={state} aria-current={state === "current" ? "step" : undefined}>
            <span className="farmap-phenology__dot" aria-hidden="true">{state === "done" ? <Check size={12} /> : index + 1}</span>
            <strong>{stage.label}</strong>
            <small>{dateRange || (state === "current" ? "当前阶段" : `第 ${index + 1} 期`)}</small>
            {(index === registeredIndex || index === seasonalIndex) && <span className="farmap-phenology__marker">
              {index === registeredIndex && "登记"}{index === seasonalIndex && index === registeredIndex ? "/" : ""}{index === seasonalIndex && "推算"}
            </span>}
          </li>;
        })}
      </ol>
    </div>

    <div className="farmap-phenology__summary">
      <div>
        <span>当前阶段</span>
        <strong>{current?.label ?? phase.current ?? "待核对"}</strong>
      </div>
      <div>
        <span>{countdown !== undefined ? "距下一阶段" : "下一阶段"}</span>
        <strong>{countdown !== undefined ? `${countdown} 天` : next ?? "待核定"}</strong>
        {countdown !== undefined && next && <small>{next}</small>}
      </div>
      {accumulation && <div className="farmap-phenology__gdd">
        <span><Gauge size={13} aria-hidden="true" />积温进度</span>
        <strong>{accumulation.current}{accumulation.unit ?? ""}{accumulation.target !== undefined ? ` / ${accumulation.target}${accumulation.unit ?? ""}` : ""}</strong>
        {accumulationProgress !== undefined && <span className="farmap-phenology__gdd-track" aria-hidden="true"><i style={{ width: `${accumulationProgress}%` }} /></span>}
      </div>}
    </div>

    {!current && phase.current && <p role="status">当前阶段“{phase.current}”不在已配置的十期物候轴中，请核对基础资料。</p>}
    {(phase.registered || phase.seasonal) && <p className="farmap-phenology__note">地块登记 {phase.registered ?? "缺失"} · 历法参考 {phase.seasonal ?? "缺失"}</p>}
  </section>;
}
