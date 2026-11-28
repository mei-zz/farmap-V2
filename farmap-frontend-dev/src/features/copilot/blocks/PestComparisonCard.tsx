import { useEffect, useState } from "react";
import { ChevronLeft, ChevronRight, Image as ImageIcon, Maximize2, ShieldAlert } from "lucide-react";
import { Modal } from "antd";
import type { AgentUiBlock } from "../uiBlocks";

export type AtlasFigure = {
  id: string;
  label: string;
  url: string;
  match?: string;
  stageCode?: string;
  diseaseKey?: string;
  vigor?: string;
};

export type PestSiteImage = {
  id: string;
  label: string;
  url: string;
  capturedAt?: string;
  source?: string;
};

export type DifferentialFeature = {
  label: string;
  detail?: string;
  present?: boolean;
};

export type PestRecommendation = {
  title?: string;
  detail: string;
};

export type PestPhi = {
  days?: number;
  warning?: string;
  blocked?: boolean;
  label?: string;
};

export type PestComparisonData = {
  diagnosisLabel?: string;
  siteImages?: PestSiteImage[];
  differentialFeatures?: DifferentialFeature[];
  recommendation?: PestRecommendation;
  phi?: PestPhi;
};

const emptySiteImages: PestSiteImage[] = [];
const emptyFeatures: DifferentialFeature[] = [];

function ImageFrame({ image, alt, onOpen }: { image: PestSiteImage | AtlasFigure; alt: string; onOpen?: () => void }) {
  return <div className="farmap-pest__frame">
    <img src={image.url} alt={alt} loading="lazy" referrerPolicy="no-referrer" />
    {onOpen && <button type="button" className="farmap-pest__zoom" aria-label={`放大查看 ${alt}`} onClick={onOpen}>
      <Maximize2 size={15} aria-hidden="true" />放大
    </button>}
  </div>;
}

export function PestComparisonCard({ block }: { block: AgentUiBlock }) {
  const comparison = block.data?.pestComparison;
  const figures = block.data?.atlasFigures ?? [];
  const siteImages = comparison?.siteImages ?? emptySiteImages;
  const features = comparison?.differentialFeatures ?? emptyFeatures;
  const [selectedFigure, setSelectedFigure] = useState(0);
  const [selectedSite, setSelectedSite] = useState(0);
  const [previewType, setPreviewType] = useState<"site" | "atlas">();
  const [checked, setChecked] = useState<Set<number>>(new Set());

  useEffect(() => {
    setSelectedFigure((current) => figures.length ? Math.min(current, figures.length - 1) : 0);
  }, [figures.length]);

  useEffect(() => {
    setChecked(new Set(features.flatMap((feature, index) => feature.present ? [index] : [])));
  }, [features]);

  const figure = figures[selectedFigure];
  const site = siteImages[Math.min(selectedSite, siteImages.length - 1)];
  const preview = previewType === "site" ? site : figure;
  const phi = comparison?.phi;

  if (!figure && !site) return <p>没有查到现场图或匹配的图鉴记录。</p>;

  const moveFigure = (offset: number) => {
    if (!figures.length) return;
    setSelectedFigure((current) => (current + offset + figures.length) % figures.length);
  };
  const toggleFeature = (index: number) => {
    setChecked((current) => {
      const next = new Set(current);
      if (next.has(index)) next.delete(index); else next.add(index);
      return next;
    });
  };

  return <section className="farmap-pest" aria-label="病虫害现场与图鉴对照">
    <div className="farmap-pest__comparison">
      <figure className="farmap-pest__side">
        <figcaption><span>现场观测</span>{site?.source && <small>{site.source}</small>}</figcaption>
        {site ? <ImageFrame image={site} alt={site.label || "现场观测图片"} onOpen={() => setPreviewType("site")} />
          : <div className="farmap-pest__empty"><ImageIcon size={24} aria-hidden="true" /><span>本轮没有可展示的现场图</span></div>}
        {site && <p>{site.label}{site.capturedAt ? ` · ${site.capturedAt}` : ""}</p>}
        {siteImages.length > 1 && <div className="farmap-pest__thumbs" aria-label="现场图缩略图">
          {siteImages.map((entry, index) => <button key={entry.id} type="button"
            aria-label={`查看第 ${index + 1} 张现场图`} aria-current={index === selectedSite ? "true" : undefined}
            onClick={() => setSelectedSite(index)}>
            <img src={entry.url} alt="" loading="lazy" referrerPolicy="no-referrer" />
          </button>)}
        </div>}
      </figure>
      <figure className="farmap-pest__side">
        <figcaption>
          <span>标准图鉴</span>
          {figures.length > 1 && <small>{selectedFigure + 1} / {figures.length}</small>}
        </figcaption>
        {figure ? <>
          <div className="farmap-pest__atlas">
            <ImageFrame image={figure} alt={figure.label || "标准病害图鉴"} onOpen={() => setPreviewType("atlas")} />
            {figures.length > 1 && <>
              <button type="button" className="farmap-pest__nav is-prev" aria-label="上一张图鉴" onClick={() => moveFigure(-1)}><ChevronLeft size={18} /></button>
              <button type="button" className="farmap-pest__nav is-next" aria-label="下一张图鉴" onClick={() => moveFigure(1)}><ChevronRight size={18} /></button>
            </>}
          </div>
          <p>{figure.label}{figure.match ? ` · 匹配 ${figure.match}` : ""}</p>
          {figures.length > 1 && <div className="farmap-pest__thumbs" aria-label="图鉴缩略图">
            {figures.map((entry, index) => <button key={entry.id} type="button" aria-label={`查看${entry.label}`}
              aria-current={index === selectedFigure ? "true" : undefined} onClick={() => setSelectedFigure(index)}>
              <img src={entry.url} alt="" loading="lazy" referrerPolicy="no-referrer" />
            </button>)}
          </div>}
        </> : <div className="farmap-pest__empty"><ImageIcon size={24} aria-hidden="true" /><span>没有匹配的标准图鉴</span></div>}
      </figure>
    </div>

    {(comparison?.diagnosisLabel || features.length > 0) && <div className="farmap-pest__features">
      <div className="farmap-pest__features-heading">
        <strong>{comparison?.diagnosisLabel ?? "典型鉴别特征"}</strong>
        {features.length > 0 && <small>已核对 {checked.size} / {features.length}</small>}
      </div>
      {features.length > 0 && <ul>{features.map((feature, index) => <li key={`${feature.label}-${index}`}>
        <label>
          <input type="checkbox" checked={checked.has(index)} onChange={() => toggleFeature(index)} />
          <span>{feature.label}</span>
        </label>
        {feature.detail && <small>{feature.detail}</small>}
      </li>)}</ul>}
    </div>}

    {comparison?.recommendation && <div className="farmap-pest__recommendation">
      <strong>{comparison.recommendation.title ?? "推荐处置方案"}</strong>
      <p>{comparison.recommendation.detail}</p>
    </div>}

    {phi && (phi.days !== undefined || phi.warning || phi.blocked || phi.label) && <div className="farmap-pest__phi" data-blocked={phi.blocked} role="alert">
      <ShieldAlert size={17} aria-hidden="true" />
      <div>
        <strong>{phi.blocked ? "安全间隔期拦截" : "安全间隔期提醒"}</strong>
        <span>{phi.label ?? (phi.days !== undefined ? `登记标签安全间隔期 ${phi.days} 天` : "药剂安全间隔期需核验。")}</span>
        {phi.warning && <small>{phi.warning}</small>}
      </div>
    </div>}

    <small className="farmap-pest__note">图鉴供对照，现场症状以观测、采样和专家复核记录为准。</small>

    <Modal open={previewType !== undefined} title={preview?.label ?? "图片预览"} onCancel={() => setPreviewType(undefined)} footer={null}
      width="min(94vw, 980px)" centered>
      {preview && <img className="farmap-pest__preview" src={preview.url} alt={preview.label} referrerPolicy="no-referrer" />}
    </Modal>
  </section>;
}
