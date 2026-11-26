import assert from "node:assert/strict";
import test from "node:test";
import { includeUnobservedFields, maturityShapes } from "../src/features/maturity/geometry.ts";

const boundary = [[0, 0], [4, 0], [4, 1], [1, 1], [1, 4], [0, 4]].map(([longitude, latitude]) => ({ longitude, latitude }));
const parcels = [
  { fieldId: "east", longitude: 3, latitude: 0.5, stage: "RIPE" },
  { fieldId: "north", longitude: 0.5, latitude: 3, stage: "UNKNOWN" },
];
function area(path) {
  return Math.abs(path.reduce((sum, [x, y], i) => {
    const [nextX, nextY] = path[(i + 1) % path.length];
    return sum + x * nextY - y * nextX;
  }, 0) / 2);
}

test("demo partitions cover a concave farm boundary without filling the excluded corner", () => {
  const shapes = maturityShapes(parcels, boundary, true);
  assert.deepEqual([...new Set(shapes.map(({ parcelIndex }) => parcelIndex))], [0, 1]);
  assert.ok(shapes.every(({ demo }) => demo));
  const total = shapes.reduce((sum, { path }) => sum + area(path), 0);
  assert.ok(Math.abs(total - 7) < 1e-8);
  assert.ok(shapes.every(({ path }) => path.every(([x, y]) => x <= 1 + 1e-8 || y <= 1 + 1e-8)));
});

test("single-camera reports leave other registered fields unknown", () => {
  const mapParcels = includeUnobservedFields([parcels[0]], [
    { fieldId: "east", name: "East", longitude: 3, latitude: 0.5 },
    { fieldId: "north", name: "North", longitude: 0.5, latitude: 3 },
  ]);
  assert.equal(mapParcels[1].stage, "UNKNOWN");
  const shapes = maturityShapes(mapParcels, boundary, true);
  assert.ok(shapes.some(({ parcelIndex }) => parcelIndex === 1));
  assert.ok(shapes.filter(({ parcelIndex }) => parcelIndex === 0).reduce((n, { path }) => n + area(path), 0) < 7);
});

test("dense outlines and outside monitoring sites still partition all registered regions", () => {
  const denseRectangle = [[0, 0], [4, 0], [4, 3], [0, 3]].flatMap(([x, y], index, points) => {
    const [nextX, nextY] = points[(index + 1) % points.length];
    return Array.from({ length: 250 }, (_, step) => ({ longitude: x + (nextX - x) * step / 250, latitude: y + (nextY - y) * step / 250 }));
  });
  const fields = [{ fieldId: "inside", longitude: 1, latitude: 1 }, { fieldId: "outside", longitude: 1, latitude: 30 }];
  const shapes = maturityShapes(fields, denseRectangle, true);
  assert.deepEqual(new Set(shapes.map(({ parcelIndex }) => parcelIndex)), new Set([0, 1]));
  assert.ok(Math.abs(shapes.reduce((sum, { path }) => sum + area(path), 0) - 12) < 1e-7);
});

test("real parcel polygons take precedence and demo cells require the opt-in flag", () => {
  assert.deepEqual(maturityShapes(parcels, boundary, false), []);
  const realPath = [[0, 0], [1, 0], [1, 1], [0, 1]];
  assert.deepEqual(maturityShapes([{ ...parcels[0], path: realPath }, parcels[1]], boundary, true), [
    { parcelIndex: 0, path: realPath, demo: false },
  ]);
});
