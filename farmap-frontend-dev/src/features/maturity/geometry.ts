import type { MaturityParcel } from "./service";

export type Coordinate = [number, number];
export type MaturityShape = { parcelIndex: number; path: Coordinate[]; demo: boolean };
type BoundaryPoint = { longitude: number; latitude: number };
type FieldPosition = { fieldId: string; name: string; longitude?: number; latitude?: number };
const epsilon = 1e-12;

export const maturityMapColor = (stage?: string): string => ({
  UNRIPE: "#8bc5a3", TURNING: "#e7c66e", RIPE: "#e98966",
  OVER_RIPE: "#9e4050", OVERRIPE: "#9e4050", MIXED: "#ac91c3", UNKNOWN: "#aeb8b3",
}[stage ?? "UNKNOWN"] ?? "#aeb8b3");

export function includeUnobservedFields(observed: MaturityParcel[], fields: FieldPosition[]): MaturityParcel[] {
  return [...observed, ...fields.filter((field) => !observed.some((parcel) => parcel.fieldId === field.fieldId))
    .map((field) => ({ ...field, stage: "UNKNOWN", reason: "本次报告未覆盖此地块", observations: [] }))];
}

const same = (a: Coordinate, b: Coordinate) => Math.abs(a[0] - b[0]) < epsilon && Math.abs(a[1] - b[1]) < epsilon;
const cross = (a: Coordinate, b: Coordinate, c: Coordinate) => (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);

export function validMaturityPath(input: unknown): Coordinate[] | undefined {
  if (!Array.isArray(input)) return undefined;
  const path: Coordinate[] = [];
  for (const item of input) {
    if (!Array.isArray(item) || item.length !== 2 || !item.every(Number.isFinite) || Math.abs(item[0]) > 180 || Math.abs(item[1]) > 90) return undefined;
    const point: Coordinate = [item[0], item[1]];
    if (!path.length || !same(point, path[path.length - 1])) path.push(point);
  }
  if (path.length > 1 && same(path[0], path[path.length - 1])) path.pop();
  return path.length >= 3 ? path : undefined;
}

function simplifyArc(points: Coordinate[], tolerance: number): Coordinate[] {
  const keep = new Set([0, points.length - 1]);
  const ranges: [number, number][] = [[0, points.length - 1]];
  while (ranges.length) {
    const [from, to] = ranges.pop()!;
    const [a, b] = [points[from], points[to]];
    const dx = b[0] - a[0]; const dy = b[1] - a[1];
    const lengthSquared = dx * dx + dy * dy;
    let split = -1; let maxDistance = tolerance * tolerance;
    for (let i = from + 1; i < to; i++) {
      const p = points[i];
      const along = lengthSquared ? Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / lengthSquared)) : 0;
      const distance = (p[0] - a[0] - along * dx) ** 2 + (p[1] - a[1] - along * dy) ** 2;
      if (distance > maxDistance) { maxDistance = distance; split = i; }
    }
    if (split >= 0) { keep.add(split); ranges.push([from, split], [split, to]); }
  }
  return points.filter((_, index) => keep.has(index));
}

function simplifyOutline(path: Coordinate[]): Coordinate[] {
  if (path.length <= 180) return path;
  let pivot = 1;
  const distance = (index: number) => (path[index][0] - path[0][0]) ** 2 + (path[index][1] - path[0][1]) ** 2;
  for (let i = 2; i < path.length; i++) if (distance(i) > distance(pivot)) pivot = i;
  const first = path.slice(0, pivot + 1);
  const second = [...path.slice(pivot), path[0]];
  let tolerance = Math.sqrt(distance(pivot)) / 3000;
  for (let attempt = 0; attempt < 18; attempt++) {
    const reduced = [...simplifyArc(first, tolerance).slice(0, -1), ...simplifyArc(second, tolerance).slice(0, -1)];
    if (reduced.length >= 3 && reduced.length <= 180) return reduced;
    tolerance *= 1.55;
  }
  // Some imported rings are cut lines rather than simple polygon paths.
  return convexHull(path);
}

function convexHull(path: Coordinate[]): Coordinate[] {
  const ordered = [...path].sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  function half(points: Coordinate[]) {
    const result: Coordinate[] = [];
    for (const point of points) {
      while (result.length > 1 && cross(result.at(-2)!, result.at(-1)!, point) <= epsilon) result.pop();
      result.push(point);
    }
    return result.slice(0, -1);
  }
  return [...half(ordered), ...half(ordered.reverse())];
}

/** Split a simple farm outline into triangles so clipping remains valid for concave boundaries. */
function triangulate(outline: Coordinate[]): Coordinate[][] {
  const path = [...outline];
  const origin = path[0];
  let area = 0;
  for (let i = 1; i + 1 < path.length; i++) area += cross(origin, path[i], path[i + 1]);
  if (Math.abs(area) <= epsilon) return [];
  if (area < 0) path.reverse();
  const triangles: Coordinate[][] = [];
  while (path.length > 3) {
    let found = false;
    for (let i = 0; i < path.length; i++) {
      const before = (i + path.length - 1) % path.length;
      const after = (i + 1) % path.length;
      const a = path[before];
      const b = path[i];
      const c = path[after];
      const turn = cross(a, b, c);
      if (Math.abs(turn) <= epsilon) { path.splice(i, 1); found = true; break; }
      if (turn < 0) continue;
      const containsVertex = path.some((p, j) => j !== before && j !== i && j !== after
        && cross(a, b, p) >= -epsilon && cross(b, c, p) >= -epsilon && cross(c, a, p) >= -epsilon);
      if (containsVertex) continue;
      triangles.push([a, b, c]);
      path.splice(i, 1);
      found = true;
      break;
    }
    if (!found) return [];
  }
  if (path.length === 3 && Math.abs(cross(path[0], path[1], path[2])) > epsilon) triangles.push(path);
  return triangles;
}

function clip(path: Coordinate[], distance: (point: Coordinate) => number): Coordinate[] {
  const result: Coordinate[] = [];
  for (let i = 0; i < path.length; i++) {
    const a = path[i];
    const b = path[(i + 1) % path.length];
    const first = distance(a);
    const second = distance(b);
    if (first <= 0) result.push(a);
    if ((first <= 0) !== (second <= 0)) {
      const fraction = first / (first - second);
      result.push([a[0] + fraction * (b[0] - a[0]), a[1] + fraction * (b[1] - a[1])]);
    }
  }
  return result;
}

function closerTo(path: Coordinate[], site: Coordinate, other: Coordinate, longitudeWeight: number): Coordinate[] {
  const midpoint: Coordinate = [(site[0] + other[0]) / 2, (site[1] + other[1]) / 2];
  const direction: Coordinate = [(other[0] - site[0]) * longitudeWeight, other[1] - site[1]];
  return clip(path, (p) => (p[0] - midpoint[0]) * direction[0] + (p[1] - midpoint[1]) * direction[1]);
}

function polygonArea(path: Coordinate[]) {
  let sum = 0;
  for (let i = 1; i + 1 < path.length; i++) sum += cross(path[0], path[i], path[i + 1]);
  return Math.abs(sum) / 2;
}

/** Preserve the ordering of the registered sites when some lie outside the farm outline. */
function ordinalPartitions(sites: { index: number; location: Coordinate }[], triangles: Coordinate[][], longitudeWeight: number): MaturityShape[] {
  const spread = (axis: 0 | 1) => Math.max(...sites.map(({ location }) => location[axis])) - Math.min(...sites.map(({ location }) => location[axis]));
  const axis = spread(0) * Math.sqrt(longitudeWeight) >= spread(1) ? 0 : 1;
  const ordered = [...sites].sort((a, b) => a.location[axis] - b.location[axis] || a.index - b.index);
  const values = triangles.flat().map((point) => point[axis]);
  const min = Math.min(...values); const max = Math.max(...values);
  const area = triangles.reduce((sum, triangle) => sum + polygonArea(triangle), 0);
  const bounds = [min];
  for (let i = 1; i < ordered.length; i++) {
    const target = area * i / ordered.length;
    let low = min; let high = max;
    for (let step = 0; step < 34; step++) {
      const cut = (low + high) / 2;
      const left = triangles.reduce((sum, triangle) => sum + polygonArea(clip(triangle, (p) => p[axis] - cut)), 0);
      if (left < target) low = cut;
      else high = cut;
    }
    bounds.push((low + high) / 2);
  }
  bounds.push(max);
  return ordered.flatMap((site, index) => triangles.flatMap((triangle) => {
    const left = clip(triangle, (p) => bounds[index] - p[axis]);
    const path = validMaturityPath(clip(left, (p) => p[axis] - bounds[index + 1]));
    return path && polygonArea(path) > epsilon ? [{ parcelIndex: site.index, path, demo: true }] : [];
  }));
}

/** Render-only partitions. An observed position and the existing farm outline are the only inputs. */
export function maturityShapes(parcels: MaturityParcel[], boundary: BoundaryPoint[], demoEnabled: boolean): MaturityShape[] {
  const authoritative = parcels.flatMap((parcel, parcelIndex) => {
    const path = validMaturityPath(parcel.path ?? parcel.polygon);
    return path ? [{ parcelIndex, path, demo: false }] : [];
  });
  // Never overlap an authoritative parcel with a generated neighboring cell.
  if (authoritative.length || !demoEnabled) return authoritative;
  const outline = validMaturityPath(boundary.map((point) => [point.longitude, point.latitude]));
  if (!outline || outline.length > 12_000) return [];
  const sites: { index: number; location: Coordinate }[] = [];
  parcels.forEach((parcel, index) => {
    if (!parcel.fieldId || typeof parcel.longitude !== "number" || typeof parcel.latitude !== "number"
      || !Number.isFinite(parcel.longitude) || !Number.isFinite(parcel.latitude)) return;
    const location: Coordinate = [parcel.longitude, parcel.latitude];
    if (!sites.some((site) => same(site.location, location))) sites.push({ index, location });
  });
  if (!sites.length) return [];
  const meanLatitude = outline.reduce((sum, point) => sum + point[1], 0) / outline.length;
  const longitudeWeight = Math.cos(meanLatitude * Math.PI / 180) ** 2;
  let triangles = triangulate(simplifyOutline(outline));
  if (!triangles.length) triangles = triangulate(convexHull(outline));
  if (!triangles.length) return [];
  const cells = sites.flatMap((site) => triangles.flatMap((triangle) => {
    let path = triangle;
    for (const other of sites) {
      if (other === site) continue;
      path = closerTo(path, site.location, other.location, longitudeWeight);
      if (path.length < 3) return [];
    }
    const valid = validMaturityPath(path);
    return valid && polygonArea(valid) > epsilon ? [{ parcelIndex: site.index, path: valid, demo: true }] : [];
  }));
  return new Set(cells.map(({ parcelIndex }) => parcelIndex)).size === sites.length ? cells : ordinalPartitions(sites, triangles, longitudeWeight);
}
