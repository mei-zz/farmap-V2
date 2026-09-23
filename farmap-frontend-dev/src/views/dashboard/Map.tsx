// 1. 画地图
// 2. 画markers
import "@amap/amap-jsapi-types";
import { load as loadAMap } from "@amap/amap-jsapi-loader";

import { useCallback, useEffect, useRef, useState } from "react";
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
};

export default function MapContainer(props: MapProps) {
    const [searchParams] = useSearchParams();
    const focusCameraId = searchParams.get("cameraId");
    const centerLongitude = props.center[0];
    const centerLatitude = props.center[1];
    const setAIContext = useAIContextStore((state) => state.setContext);

    // Show amap and update when farm changes
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
                map = new api.Map("map-container", {
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
                fillColor: "#ccebc5", //多边形填充颜色
                strokeOpacity: 1, //线条透明度
                fillOpacity: 0.5, //填充透明度
                strokeColor: "#2b8cbe", //线条颜色
                strokeWeight: 1, //线条宽度
                strokeStyle: "dashed", //线样式
                strokeDasharray: [5, 5], //轮廓的虚线和间隙的样式
            });

            polygon.on("mouseover", () => {
                polygon.setOptions({
                    fillOpacity: 0.7, //多边形填充透明度
                    fillColor: "#7bccc4",
                });
            });
            polygon.on("mouseout", () => {
                polygon.setOptions({
                    fillOpacity: 0.5,
                    fillColor: "#ccebc5",
                });
            });

            amap.add(polygon);
        },
        [props.farmLocations],
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
        if (!amap || props.modeKey !== "monitor" || !camera || focusedCameraRef.current === camera.cameraId) return;
        focusedCameraRef.current = camera.cameraId;
        amap.setZoomAndCenter(Math.max(props.zoom + 2, 15), [camera.longitude, camera.latitude]);
        void handleSelect(camera);
    }, [focusCameraId, handleSelect, props.cameras, props.modeKey, props.zoom]);

    useEffect(() => {
        const amap = amapRef.current;
        if (!amap) return;

        if (props.modeKey === "crop") {
            drawMarkers(amap, props.crops);
        } else if (props.modeKey === "monitor") {
            drawPolygon(amap);
            drawMonitor(amap);
        } else {
            amap.clearMap();
        }

        // Trigger when farm sitches
    }, [drawMarkers, drawMonitor, drawPolygon, mapReady, props.crops, props.modeKey, props.slider.scale, props.slider.value]);

    // Draggable monitor card
    return (
        <>
            <div id="map-container" style={{ flexGrow: 1, borderRadius: 8, minHeight: 300 }}></div>
            {mapError && (
                <div style={{ color: "#b54708", fontSize: 12, marginTop: 6 }}>{mapError}</div>
            )}
            {props.modeKey === "monitor" && props.cameras?.length === 0 && (
                <div style={{ color: "#667085", fontSize: 12, marginTop: 6 }}>
                    当前农场没有已绑定的实时摄像头；地图仍显示农场边界，摄像头需要由农场管理员完成绑定。
                </div>
            )}
            {selectedCamera && props.modeKey === "monitor" && (
                <div style={{ background: "#f4faf7", border: "1px solid #d6eee2", borderRadius: 8, padding: "8px 10px", marginTop: 6, fontSize: 12 }}>
                    <b>{selectedCamera.name}</b>
                    <span style={{ marginLeft: 8, color: "#667085" }}>{selectedCamera.serial}</span>
                    <span style={{ marginLeft: 8, color: "#16704f" }}>{cameraBindingLabel(selectedCamera)}</span>
                    <span style={{ marginLeft: 8, color: "#667085" }}>{cameraSourceLabel(selectedCamera)}</span>
                </div>
            )}
            {monitorError && props.modeKey === "monitor" && (
                <div style={{ color: "#b54708", fontSize: 12, marginTop: 6 }}>{monitorError}</div>
            )}
            {videoUrl.length > 0 && (
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
