import { useCallback, useEffect, useRef, useState } from "react";
import { useSearchParams } from "react-router";
import type { FarmSelectType } from "./selects/FarmSelect";
import type { ModeSelectType } from "./selects/ModeSelect";
import type { InfoSelectType } from "./selects/InfoSelect";
import FarmSelect from "./selects/FarmSelect";
import { Alert, Card, Flex } from "antd";
import InfoSelect from "./selects/InfoSelect";
import ModeSelect from "./selects/ModeSelect";
import MapContainer from "./Map";
import Slider from "./slider/Slider";
import type { SliderProps } from "./slider/Slider";
import { GlobalOutlined, MacCommandOutlined } from "@ant-design/icons";
import { permanence } from "@/utils/permanence";
import { useFarmStore, type Crop } from "@/store/farm";
import { req } from "@/utils/reqeust";
import { useUserStore } from "@/store/user";
import { Loader2 } from "lucide-react";
import { useAIContextStore } from "@/store/aiContext";
import { toFarmStore, type CanonicalFarmMapData } from "@/features/farm/mapData";
import AgriculturalContextPanel from "@/features/agricultural/AgriculturalContextPanel";
import { loadFarmCameras, type FarmCamera } from "@/features/farm/cameras";

export default function Map() {
    const [searchParams] = useSearchParams();
    const requestedMode = searchParams.get("mode");
    const setAIContext = useAIContextStore((s) => s.setContext);
    // Pass farm status to map
    const farmCenter = useFarmStore((s) => s.center)
        .split(",")
        .map((c) => Number(c)) as [number, number];
    const farmZoom = useFarmStore((s) => s.zoom);
    const farmCrops = useFarmStore((s) => s.crops);
    const farmLocations = useFarmStore((s) => s.locations);

    // Get farm select informations from local user storage
    const localUserStore = permanence.user.useUserStore()!;
    const storedFarmOptions = useUserStore((s) => s.farms);
    const farmOptions = localUserStore?.farms ?? storedFarmOptions;
    const currentContextFarmId = useAIContextStore((s) => s.context.currentFarm.id);
    const [farm, setSelectFarm] = useState<FarmSelectType["value"]>(
        currentContextFarmId > 0 ? currentContextFarmId : farmOptions[0]?.id ?? -1,
    );
    const [cameras, setCameras] = useState<FarmCamera[]>([]);

    // Fetch the first farm content and store
    const token = permanence.token.useToken();
    const setFarmStore = useFarmStore((s) => s.setFarm);
    const setLocalFarmStore = permanence.farm.setFarmStore;
    useEffect(() => {
        setAIContext({ currentPage: "/farm/map" });
    }, [setAIContext]);

    // The header and the map selector must share the same farm context.  The
    // previous implementation only updated the map's local state, which made
    // the header and map disagree while the next request was still loading.
    const currentFarmContext = useAIContextStore((s) => s.context.currentFarm);
    useEffect(() => {
        const contextFarmIsAvailable = currentContextFarmId > 0 && farmOptions.some((option) => option.id === currentContextFarmId);
        const nextFarm = contextFarmIsAvailable ? currentContextFarmId : farmOptions[0]?.id ?? -1;
        if (nextFarm > 0 && nextFarm !== farm) setSelectFarm(nextFarm);
    }, [currentContextFarmId, farm, farmOptions]);

    const changeFarmStore: (newId: number) => void = (newId) => {
        const selectedFarm = farmOptions.find((option) => option.id === newId);
        if (!selectedFarm || selectedFarm.id === farm) return;
        setSelectFarm(selectedFarm.id);
        setAIContext({
            currentFarm: {
                ...currentFarmContext,
                id: selectedFarm.id,
                name: selectedFarm.name,
            },
            currentField: undefined,
            currentCrop: undefined,
            currentLocation: undefined,
            currentPhenology: undefined,
            selectedDevice: undefined,
            selectedDiagnosis: undefined,
            selectedImages: [],
            currentWeather: undefined,
            agentRunId: undefined,
            taskId: undefined,
        });
    };

    // Mode visibility
    const [farmModes, setFarmModes] = useState<ModeSelectType["value"][]>(["none"]);

    // Loading
    const [fetchFarmLoading, setFetchFarmLoading] = useState(false);
    const [fetchFarmError, setFetchFarmError] = useState<string>();
    const fetchSequence = useRef(0);
    const activeFarmRef = useRef(farm);
    // THe mode & info select states
    const [mode, setMode] = useState<ModeSelectType["value"]>("none");
    const [info, setInfo] = useState<InfoSelectType["value"]>("yield");

    useEffect(() => {
        activeFarmRef.current = farm;
    }, [farm]);

    // Clear the old farm's visual mode immediately. This prevents a previous
    // farm's point cloud from appearing while the new farm is being fetched.
    useEffect(() => {
        setMode("none");
        setFarmModes(["none"]);
        setCameras([]);
        setFetchFarmError(undefined);
    }, [farm]);

    const fetchFarm = useCallback(async (id: number): Promise<void> => {
        const sequence = ++fetchSequence.current;
        const isCurrentRequest = () => sequence === fetchSequence.current && activeFarmRef.current === id;
        if (id <= 0) {
            setFetchFarmLoading(false);
            return;
        }

        try {
            setFetchFarmLoading(true);
            setFetchFarmError(undefined);
            const resp = await req.get<{ data: CanonicalFarmMapData }>(`/api/farms/${id}/map-data`, {
                Authorization: `Bearer ${token}`,
            });
            const farmData = toFarmStore(resp.data, { username: localUserStore?.username });
            const farmCameras = await loadFarmCameras(id, token).catch(() => []);

            // A slow response from the previous farm must never overwrite the
            // farm that the user selected most recently.
            if (!isCurrentRequest()) return;

            if (farmData.type === "rice") {
                setInfo("phenology");
            } else {
                setInfo("yield");
            }
            setFarmStore(farmData);
            setCameras(farmCameras);
            setAIContext({
                currentFarm: { id: farmData.id, name: farmData.name, address: farmData.address },
                currentPage: "/farm/map",
                currentField: undefined,
                currentCrop: undefined,
                currentPhenology: undefined,
                currentLocation: undefined,
                selectedDevice: undefined,
                selectedImages: [],
                selectedDiagnosis: undefined,
                agentRunId: undefined,
                taskId: undefined,
            });
            // Store to local storage
            setLocalFarmStore(farmData);
            // Set visible farm modes
            const hasCrop = farmData.crops.length > 0;
            const hasLocation = farmData.locations.length > 0;
            const visibles: ModeSelectType["value"][] = [];
            if (hasCrop) visibles.push("crop");
            if (hasLocation || farmCameras.length > 0) visibles.push("monitor");
            // NOTE: always push a monitor to visibles
            // visibles.push("monitor");
            // Push none to visibles when no content
            if (!visibles.length) visibles.push("none");

            setFarmModes(visibles);
            setMode(requestedMode === "monitor" && visibles.includes("monitor") ? "monitor" : visibles[0]);
        } catch (error: unknown) {
            if (!isCurrentRequest()) return;
            setCameras([]);
            setFarmModes(["none"]);
            setMode("none");
            setFetchFarmError(error instanceof Error ? error.message : "农场地图加载失败，请重试。");
        } finally {
            if (isCurrentRequest()) setFetchFarmLoading(false);
        }
    }, [localUserStore?.username, requestedMode, setAIContext, setFarmStore, setLocalFarmStore, token]);

    useEffect(() => {
        fetchFarm(farm);
    }, [farm, fetchFarm]);

    // Slider
    const [slider, setSlider] = useState<Omit<SliderProps, "onChangeEnd" | "disabled">>({
        value: {
            left: 10,
            right: 90,
        },
        scale: {
            min: 0,
            max: 100,
        },
        decimal: false,
    });
    const crops = useFarmStore((s) => s.crops);
    useEffect(() => {
        const shouldManualSet: Array<InfoSelectType["value"]> = [
            "phenology",
            "growth",
            "impact",
            "nitrogen",
            "potassium",
            "phosphorus",
        ];
        const values = crops
            .map((c) => Number(c.info[info as keyof Crop["info"]]))
            .filter((value) => Number.isFinite(value));
        let max = 0;
        let min = 0;
        let left = 0;
        let right = 1;
        if (shouldManualSet.includes(info)) {
            if (info === "phenology") {
                max = 9;
                min = 1;
            } else {
                max = 3;
                min = 1;
            }
        } else {
            min = values.length ? Math.min(...values) : 0;
            max = values.length ? Math.max(...values) : 1;
            // A flat or absent data series has no mathematical slider range.
            // Keep a one-unit visual range and select every value by default.
            if (max <= min) max = min + 1;
        }

        const range = max - min;
        if (shouldManualSet.includes(info)) {
            left = range * 0.2 + min;
            right = range * 0.8 + min;
        } else if (values.length && Math.max(...values) > Math.min(...values)) {
            left = range * 0.2 + min;
            right = range * 0.8 + min;
        } else {
            left = min;
            right = max;
        }

        // Update slider range and values
        setSlider({
            value: {
                left,
                right,
            },
            scale: {
                max,
                min,
            },
            decimal: true,
            // decimal: info === "yield" ? false : true,
        });
    }, [crops, info]);

    const sliderChange: SliderProps["onChangeEnd"] = (left, right) => {
        setSlider((prev) => ({
            ...prev,
            value: {
                left,
                right,
            },
        }));
    };

    return (
        <Flex vertical gap="0.5rem" style={{ height: "100%" }}>
            <Card
                title={
                    <Flex align="center">
                        <GlobalOutlined />
                        &nbsp;&nbsp;地图详情
                        {fetchFarmLoading && (
                            <Loader2 className="w-6 h-6 ml-2 text-blue-500 animate-spin" />
                        )}
                    </Flex>
                }
                style={{ height: "100%" }}
                styles={{ body: { height: "calc(100% - 60px)" } }}>
                <Flex gap="0.5rem" vertical style={{ height: "100%" }}>
                    <Flex
                        gap="0.5rem"
                        style={{ width: "100%" }}
                        className="resp-dashboard__selects">
                        <FarmSelect
                            value={farm}
                            options={farmOptions}
                            disabled={fetchFarmLoading}
                            onChange={changeFarmStore}
                        />
                        <ModeSelect
                            value={mode}
                            modes={farmModes}
                            disabled={fetchFarmLoading}
                            onChange={(newMode) => {
                                setMode(newMode);
                            }}
                        />
                        <InfoSelect
                            value={info}
                            disabled={mode !== "crop" || fetchFarmLoading}
                            onChange={(newInfo) => {
                                setInfo(newInfo);
                            }}
                        />
                    </Flex>
                    {fetchFarmError && <Alert
                        type="error"
                        showIcon
                        message="农场地图加载失败"
                        description={fetchFarmError}
                        action={<a onClick={() => void fetchFarm(farm)}>重试</a>}
                    />}
            <MapContainer
                        farmId={farm}
                        center={farmCenter}
                        zoom={farmZoom}
                        crops={farmCrops}
                        farmLocations={farmLocations}
                        cameras={cameras}
                        infoKey={info}
                        modeKey={mode}
                        slider={slider}
                    />
                </Flex>
            </Card>
            <Card
                title={
                    <>
                        <MacCommandOutlined />
                        &nbsp;&nbsp;调整分布
                    </>
                }
                styles={{ body: { padding: "0 24px" } }}>
                <Slider
                    {...slider}
                    onChangeEnd={sliderChange}
                    disabled={fetchFarmLoading || mode !== "crop"}
                />
            </Card>
            {farm > 0 && <AgriculturalContextPanel
                farmId={farm}
                compact
                onCurrentWeather={(weather) => setAIContext({
                    currentWeather: {
                        summary: weather.condition ?? (weather.status === "NOT_CONFIGURED" ? "天气服务尚未配置" : "天气服务已配置但暂不可用"),
                        temperature: weather.status === "READY" && weather.temperature ? `${weather.temperature}°C` : "—",
                        precipitation: weather.rainfallForecast24h !== undefined
                            ? `未来24小时 ${weather.rainfallForecast24h.toFixed(1)} mm${weather.rainfallProbability24h !== undefined ? ` · ${weather.rainfallProbability24h}%概率` : ""}`
                            : "暂无降雨预报",
                        updatedAt: weather.observedAt ?? weather.retrievedAt ?? new Date().toISOString(),
                        rainfall14d: weather.rainfall14d,
                        rainfallForecast24h: weather.rainfallForecast24h,
                        rainfallProbability24h: weather.rainfallProbability24h,
                        rainfallForecast: weather.rainfallForecast,
                        rainfallForecastDays: weather.rainfallForecastDays,
                        forecast: weather.forecast,
                    },
                })}
            />}
        </Flex>
    );
}
