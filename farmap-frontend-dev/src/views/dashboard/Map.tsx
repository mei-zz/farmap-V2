// 1. 画地图
// 2. 画markers
import "@amap/amap-jsapi-types";
import { load as loadAMap } from "@amap/amap-jsapi-loader";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "react-router";
import type { FarmStoreState } from "@/store/farm";
import { getPercentageColor } from "./utils/color";
import type { SliderProps } from "./slider/Slider";
import type { ModeSelectType } from "./selects/ModeSelect";
import VideoPlayer from "../monitor/VideoPlayer";
import { permanence } from "@/utils/permanence";
import { Flex } from "antd";
import { CloseCircleOutlined } from "@ant-design/icons";
import Window from "@/components/Window";
import { getMonitorAccessToken, getMonitorPreview } from "../monitor/monitorService";
import type { FarmCamera } from "@/features/farm/cameras";
import { cameraBindingLabel, cameraSourceLabel, selectedCameraContext } from "@/features/farm/cameras";
import type { MaturityParcel } from "@/features/maturity/service";
import { maturityMapColor, maturityShapes, type Coordinate } from "@/features/maturity/geometry";
import MaturitySchematic from "@/features/maturity/MaturitySchematic";
import { useCopilotStore } from "@/store/copilot";
import { useAIContextStore } from "@/store/aiContext";

export type MapProps = {
    farmId?: number;
    center: [number, number];
    zoom: number;
    crops: FarmStoreState["crops"];
    farmLocations: FarmStoreState["locations"];
    infoKey: keyof FarmStoreState["crops"][number]["info"];
    modeKey: ModeSelectType["value"];
    slider: Omit<SliderProps, "onChangeEnd" | "disabled">;
    cameras?: FarmCamera[];
    readOnly?: boolean;
    fields?: Array<{ fieldId: string; name: string; longitude?: number; latitude?: number }>;
    onFieldSelect?: (fieldId: string) => void;
    maturityParcels?: MaturityParcel[];
    demoMaturityGeometry?: boolean;
    selectedMaturity?: number;
    onMaturitySelect?: (index: number) => void;
};

export default function MapContainer(props: MapProps) {
    const shapes = useMemo(() => maturityShapes(props.maturityParcels ?? [], props.farmLocations, !!props.demoMaturityGeometry),
        [props.maturityParcels, props.farmLocations, props.demoMaturityGeometry]);
    const [searchParams] = useSearchParams();
    const focusCameraId = searchParams.get("cameraId");
    const centerLongitude = props.center[0];
    const centerLatitude = props.center[1];
    const setAIContext = useAIContextStore((state) => state.setContext);
    const openAgent = useCopilotStore((state) => state.open);
    const setAgentDraft = useCopilotStore((state) => state.setDraft);

    // Show amap and update when farm changes
    const containerRef = useRef<HTMLDivElement>(null);
    const amapApiRef = useRef<typeof AMap | null>(null);
    const amapRef = useRef<AMap.Map | null>(null);
    const [mapError, setMapError] = useState("");
    const [mapReady, setMapReady] = useState(false);
    useEffect(() => {
        if (![centerLongitude, centerLatitude].every((value) => Number.isFinite(value))) return;

        let active = true;
        let map: AMap.Map | null = null;
        setMapError("");
        setMapReady(false);

        const key = import.meta.env.VITE_GAODE_API_KEY;
        if (!key) {
            setMapError("地图服务未配置高德地图 Key，当前无法加载地图。");
            return () => {
                active = false;
                amapApiRef.current = null;
                amapRef.current = null;
            };
        }

        void loadAMap({ key, version: "2.0" })
            .then((api) => {
                if (!active) return;
                amapApiRef.current = api as typeof AMap;
                if (!containerRef.current) return;
                map = new api.Map(containerRef.current, {
                    center: [centerLongitude, centerLatitude],
                    zoom: props.zoom,
                });
                amapRef.current = map;
                setMapReady(true);
            })
            .catch((error: unknown) => {
                if (!active) return;
                setMapError(error instanceof Error ? `地图加载失败：${error.message}` : "地图加载失败，请稍后重试。");
            });

        return () => {
            active = false;
            map?.destroy();
            if (amapRef.current === map) amapRef.current = null;
            amapApiRef.current = null;
            setMapReady(false);
        };
    }, [centerLatitude, centerLongitude, props.zoom]);

    // Show marker
    // PERF: the map will be reset to initial location
    const drawMarkers = useCallback(
        (amap: AMap.Map, crops: FarmStoreState["crops"]) => {
            const api = amapApiRef.current;
            if (!api) return;
            amap.clearMap();

            // Slider range
            const max = props.slider.value.right;
            const min = props.slider.value.left;
            const range = max - min;

            crops.forEach((c) => {
                // Calculate color of markers
                const color = getPercentageColor(
                    Number.isFinite(range) && range > 0
                        ? (((c.info[props.infoKey] as number) - min) / range) * 100
                        : 50,
                );
                const content = `<div style="background-color: ${color}; width: 7px; height: 7px; border-radius: 50%"></div>`;
                const position = new api.LngLat(c.longitude, c.latitude);
                const marker = new api.Marker({
                    content,
                    position,
                });
                amap.add(marker);
            });
        },
        [props.slider.value, props.infoKey],
    );

    // Update display mode
    const drawPolygon = useCallback(
        (amap: AMap.Map) => {
            const api = amapApiRef.current;
            if (!api) return;
            amap.clearMap();

            const pathArr = props.farmLocations.map(
                (l): [number, number] => [l.longitude, l.latitude],
            );
            const polygon = new api.Polygon();
            polygon.setOptions({
                path: pathArr,
                fillColor: props.maturityParcels ? "#e7ece9" : "#ccebc5", // neutral farm boundary for maturity
                strokeOpacity: 1, //线条透明度
                fillOpacity: 0.5, //填充透明度
                strokeColor: props.maturityParcels ? "#8c9b92" : "#2b8cbe", // boundary
                strokeWeight: 1, //线条宽度
                strokeStyle: "dashed", //线样式
                strokeDasharray: [5, 5], //轮廓的虚线和间隙的样式
            });

            if (!props.maturityParcels) polygon.on("mouseover", () => {
                polygon.setOptions({
                    fillOpacity: 0.7, //多边形填充透明度
                    fillColor: "#7bccc4",
                });
            });
            if (!props.maturityParcels) polygon.on("mouseout", () => {
                polygon.setOptions({
                    fillOpacity: 0.5,
                    fillColor: "#ccebc5",
                });
            });

            amap.add(polygon);
            (props.maturityParcels ? [] : props.fields ?? []).forEach((field) => {
                if (!Number.isFinite(field.longitude) || !Number.isFinite(field.latitude)) return;
                const marker = new api.Marker({ position: new api.LngLat(field.longitude!, field.latitude!), title: field.name });
                marker.on("click", () => props.onFieldSelect?.(field.fieldId));
                amap.add(marker);
            });
        },
        [props.farmLocations, props.fields, props.onFieldSelect, props.maturityParcels],
    );

    // Monitors
    const [videoUrl, setVideoUrl] = useState("");
    const [monitorError, setMonitorError] = useState("");
    const [selectedCamera, setSelectedCamera] = useState<FarmCamera>();
    const focusedCameraRef = useRef<string>("");

    useEffect(() => {
        setSelectedCamera(undefined);
        setVideoUrl("");
        setMonitorError("");
        focusedCameraRef.current = "";
    }, [props.farmId]);

    // Get the user's access token before creating marker click handlers.
    const localAccessToken = permanence.accessToken.useAccessToken();
    const [accessToken, setAccessToken] = useState<string>("");
    useEffect(() => {
        if (props.readOnly) return;
        let active = true;
        getMonitorAccessToken()
            .then((token) => {
                if (active) setAccessToken(token.accessToken);
            })
            .catch((error: unknown) => {
                if (active) setMonitorError(error instanceof Error ? error.message : "摄像头服务连接失败");
            });
        return () => { active = false; };
    }, [localAccessToken?.accessToken, localAccessToken?.expiresAt, props.readOnly]);

    const handleSelect = useCallback(
        async (camera: FarmCamera): Promise<void> => {
            setSelectedCamera(camera);
            setAIContext({
                selectedDevice: selectedCameraContext(camera),
                currentField: camera.fieldId ? {
                    id: camera.fieldId,
                    name: camera.fieldName || camera.fieldId,
                    area: camera.fieldArea ?? 0,
                    crop: camera.crop || "未配置作物",
                } : undefined,
                currentCrop: camera.crop ? { name: camera.crop, growthStage: camera.growthStage } : undefined,
                currentPhenology: camera.growthStage ? { stage: camera.growthStage, progress: 0 } : undefined,
                currentLocation: { label: cameraBindingLabel(camera), longitude: camera.longitude, latitude: camera.latitude },
            });
            if (!accessToken) {
                setMonitorError(props.readOnly ? "当前地图为只读模式，无法获取实时画面。" : "摄像头服务正在连接，请稍后重试。");
                return;
            }
            try {
                const preview = await getMonitorPreview(accessToken, camera.serial);
                setMonitorError("");
                setVideoUrl(preview.previewUrl);
                setAIContext({ selectedDevice: selectedCameraContext(camera, true) });
            } catch (error: unknown) {
                setVideoUrl("");
                setMonitorError(error instanceof Error ? error.message : "该摄像头暂时无法获取画面。");
            }
        },
        [accessToken, props.readOnly, setAIContext],
    );

    const drawMonitor = useCallback((amap: AMap.Map) => {
        const api = amapApiRef.current;
        if (!api) return;
        (props.cameras ?? []).forEach((camera) => {
            const content = `<div title="${camera.name}" style="background-color: #0f8b62; border: 2px solid #fff; box-shadow: 0 1px 4px rgba(0,0,0,.35); width: 14px; height: 14px; border-radius: 50%"></div>`;
            const position = new api.LngLat(Number(camera.longitude), Number(camera.latitude));
            const marker = new api.Marker({
                content,
                position,
            });
            marker.on("click", () => {
                void handleSelect(camera);
            });

            amap.add(marker);
        });
    }, [handleSelect, props.cameras]);

    useEffect(() => {
        const amap = amapRef.current;
        const camera = (props.cameras ?? []).find((item) => item.cameraId === focusCameraId);
        if (!amap || props.maturityParcels || props.modeKey !== "monitor" || !camera || focusedCameraRef.current === camera.cameraId) return;
        focusedCameraRef.current = camera.cameraId;
        amap.setZoomAndCenter(Math.max(props.zoom + 2, 15), [camera.longitude, camera.latitude]);
        void handleSelect(camera);
    }, [focusCameraId, handleSelect, props.cameras, props.modeKey, props.zoom, props.maturityParcels]);

    useEffect(() => {
        const amap = amapRef.current;
        if (!amap) return;

        if (props.maturityParcels) {
            drawPolygon(amap);
            const api = amapApiRef.current;
            if (api) for (const shape of shapes) {
                const color = maturityMapColor(props.maturityParcels[shape.parcelIndex].stage);
                const selected = props.selectedMaturity === shape.parcelIndex;
                const polygon = new api.Polygon();
                polygon.setOptions({ path: shape.path, fillColor: color, fillOpacity: .65,
                    strokeColor: selected ? "#26352d" : color, strokeWeight: selected ? 2 : shape.demo ? 0 : 1 });
                polygon.on("click", () => props.onMaturitySelect?.(shape.parcelIndex));
                amap.add(polygon);
            }
            if (api) props.maturityParcels.forEach((parcel, index) => {
                const color = maturityMapColor(parcel.stage);
                if (Number.isFinite(parcel.longitude) && Number.isFinite(parcel.latitude)) {
                    const markerContent = document.createElement("div");
                    const dot = document.createElement("span");
                    dot.style.cssText = `display:inline-block;width:${props.selectedMaturity === index ? 18 : 12}px;height:${props.selectedMaturity === index ? 18 : 12}px;border-radius:50%;background:${color};border:2px solid white;box-shadow:0 0 0 2px ${color}`;
                    const caption = document.createElement("span"); caption.textContent = parcel.name || "观测位置"; caption.style.cssText = "background:white;padding:2px 4px;border-radius:3px;font-size:12px";
                    markerContent.append(dot, caption);
                    const marker = new api.Marker({ position: new api.LngLat(parcel.longitude!, parcel.latitude!), title: parcel.name || "观测位置", content: markerContent });
                    marker.on("click", () => props.onMaturitySelect?.(index)); amap.add(marker);
                }
            });
        } else if (props.modeKey === "crop") {
            drawMarkers(amap, props.crops);
        } else if (props.modeKey === "monitor") {
            drawPolygon(amap);
            drawMonitor(amap);
        } else {
            amap.clearMap();
        }

        // Trigger when farm sitches
    }, [drawMarkers, drawMonitor, drawPolygon, mapReady, props.crops, props.modeKey, props.slider.scale, props.slider.value, props.maturityParcels, props.selectedMaturity, props.onMaturitySelect, shapes]);

    // Draggable monitor card
    return (
        <>
            <div ref={containerRef} hidden={Boolean(mapError && props.maturityParcels)} style={{ flexGrow: 1, borderRadius: 8, minHeight: 300 }}></div>
            {mapError && props.maturityParcels && <MaturitySchematic parcels={props.maturityParcels} shapes={shapes}
                outline={props.farmLocations.map(({ longitude, latitude }): Coordinate => [longitude, latitude])}
                selected={props.selectedMaturity} onSelect={props.onMaturitySelect} />}
            {mapReady && shapes.some(({ demo }) => demo) && <div className="maturity-note">演示分区 · 根据登记位置切分，不作为测绘或采收范围</div>}
            {mapError && !props.maturityParcels && (
                <div style={{ color: "#b54708", fontSize: 12, marginTop: 6 }}>{mapError}</div>
            )}
            {!props.maturityParcels && props.modeKey === "monitor" && props.cameras?.length === 0 && (
                <div style={{ color: "#667085", fontSize: 12, marginTop: 6 }}>
                    当前农场没有已绑定的实时摄像头；地图仍显示农场边界，摄像头需要由农场管理员完成绑定。
                </div>
            )}
            {!props.maturityParcels && selectedCamera && props.modeKey === "monitor" && (
                <div style={{ background: "#f4faf7", border: "1px solid #d6eee2", borderRadius: 8, padding: "8px 10px", marginTop: 6, fontSize: 12 }}>
                    <b>{selectedCamera.name}</b>
                    <span style={{ marginLeft: 8, color: "#667085" }}>{selectedCamera.serial}</span>
                    <span style={{ marginLeft: 8, color: "#16704f" }}>{cameraBindingLabel(selectedCamera)}</span>
                    <span style={{ marginLeft: 8, color: "#667085" }}>{cameraSourceLabel(selectedCamera)}</span>
                    <button type="button" onClick={() => { setAIContext({ selectedDevice: selectedCameraContext(selectedCamera) }); setAgentDraft(`请判断摄像头 ${selectedCamera.name}（${selectedCamera.cameraId}）当前画面中可见果实的成熟度，说明依据和不确定性。`); openAgent(); }}>让 Agent 判断此摄像头成熟度</button>
                </div>
            )}
            {monitorError && props.modeKey === "monitor" && (
                <div style={{ color: "#b54708", fontSize: 12, marginTop: 6 }}>{monitorError}</div>
            )}
            {!props.maturityParcels && videoUrl.length > 0 && (
                <Window
                    title={
                        <Flex justify="space-between">
                            <span>监控预览</span>
                            <CloseCircleOutlined
                                style={{ color: "#dd0000", marginLeft: 5 }}
                                onClick={() => {
                                    setVideoUrl("");
                                }}
                            />
                        </Flex>
                    }
                    style={{
                        zIndex: 1000,
                        position: "fixed",
                    }}>
                    <VideoPlayer videoUrl={videoUrl} />
                </Window>
            )}
        </>
    );
}



