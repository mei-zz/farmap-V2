import { maturityMapColor, validMaturityPath, type Coordinate, type MaturityShape } from "./geometry";
import { stageLabel, type MaturityParcel } from "./service";
import "./maturity.css";

export default function MaturitySchematic({ parcels, shapes, outline, selected, onSelect }: {
  parcels: MaturityParcel[];
  shapes: MaturityShape[];
  outline: Coordinate[];
  selected?: number;
  onSelect?: (index: number) => void;
}) {
  const patternId = `no-data-${useId().replace(/[^a-zA-Z0-9_-]/g, "")}`;
  const points = parcels.flatMap((parcel, index) => typeof parcel.longitude === "number" && typeof parcel.latitude === "number"
    && Number.isFinite(parcel.longitude) && Number.isFinite(parcel.latitude)
    ? [{ index, point: [parcel.longitude, parcel.latitude] as Coordinate }]
    : []);
  const boundary = validMaturityPath(outline) ?? [];
  const coordinates = [...boundary, ...shapes.flatMap(({ path }) => path), ...points.map(({ point }) => point)];
  if (!coordinates.length) return <p className="maturity-note">没有可展示的区域坐标或边界，请查看区域清单。</p>;
  const west = Math.min(...coordinates.map(([longitude]) => longitude));
  const east = Math.max(...coordinates.map(([longitude]) => longitude));
  const south = Math.min(...coordinates.map(([, latitude]) => latitude));
  const north = Math.max(...coordinates.map(([, latitude]) => latitude));
  const x = (longitude: number) => east === west ? 320 : 20 + (longitude - west) / (east - west) * 600;
  const y = (latitude: number) => north === south ? 140 : 260 - (latitude - south) / (north - south) * 240;
  const polygon = (path: Coordinate[]) => path.map(([longitude, latitude]) => `${x(longitude)},${y(latitude)}`).join(" ");
  return <figure className="farmap-maturity-schematic">
    <svg viewBox="0 0 640 280" width="100%" height="100%" role="img" aria-label="农场成熟度区域示意图">
      <defs><pattern id={patternId} width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
        <rect width="6" height="6" fill="#e1e7e4" /><line y2="6" stroke="#aeb8b3" strokeWidth="2" />
      </pattern></defs>
      {boundary.length > 0 && <polygon points={polygon(boundary)} stroke="#92a39a" fill="#ecf2ed" />}
      {shapes.map(({ parcelIndex, path, demo }, index) => {
        const parcel = parcels[parcelIndex];
        const stage = parcel.stage ?? "UNKNOWN";
        return <polygon key={index} data-demo-parcel={demo ? parcelIndex : undefined} points={polygon(path)} fill={stage === "UNKNOWN" ? `url(#${patternId})` : maturityMapColor(stage)}
          stroke={selected === parcelIndex ? "#26352d" : demo ? "none" : "#fff"} strokeWidth={selected === parcelIndex ? 2 : 1}
          onClick={() => onSelect?.(parcelIndex)}><title>{parcel.name ?? "区域"} · {stageLabel(stage)}</title></polygon>;
      })}
      {points.map(({ index, point }) => <g key={index} onClick={() => onSelect?.(index)}>
        <circle cx={x(point[0])} cy={y(point[1])} r={selected === index ? 10 : 7} fill={maturityMapColor(parcels[index].stage)} stroke="#fff" strokeWidth="2" />
        <title>{parcels[index].name} · {stageLabel(parcels[index].stage)}</title>
      </g>)}
      <text x="620" y="15" textAnchor="end" fill="#40564a" fontSize="11">北 ↑</text>
    </svg>
    <figcaption>{shapes.some(({ demo }) => demo) ? "演示分区 · 根据登记位置切分，不作为测绘或采收范围" : "相对位置示意 · 无底图"}</figcaption>
  </figure>;
}
import { useId } from "react";
