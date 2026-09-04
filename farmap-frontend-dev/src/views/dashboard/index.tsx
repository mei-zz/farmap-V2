import { useEffect, useState } from "react";
import type { FarmSelectType } from "./selects/FarmSelect";
import type { ModeSelectType } from "./selects/ModeSelect";
import type { InfoSelectType } from "./selects/InfoSelect";
import FarmSelect from "./selects/FarmSelect";
import { Card, Flex } from "antd";
import InfoSelect from "./selects/InfoSelect";
import ModeSelect from "./selects/ModeSelect";
import MapContainer from "./Map";
import Slider from "./slider/Slider";
import type { SliderProps } from "./slider/Slider";
import { GlobalOutlined, MacCommandOutlined } from "@ant-design/icons";
import { permanence } from "@/utils/permanence";
import { useFarmStore, type Crop, type FarmStoreState } from "@/store/farm";
import { req } from "@/utils/reqeust";
import { useUserStore } from "@/store/user";
import { Loader2 } from "lucide-react";

export default function Map() {
    // Pass farm status to map
    const farmCenter = useFarmStore((s) => s.center)
        .split(",")
        .map((c) => Number(c)) as [number, number];
    const farmZoom = useFarmStore((s) => s.zoom);
    const farmCrops = useFarmStore((s) => s.crops);
    const farmLocations = useFarmStore((s) => s.locations);

    // Get farm select informations from local user storage
    const localUserStore = permanence.user.useUserStore()!;
    const farmOptions = localUserStore?.farms ?? useUserStore((s) => s.farms);
    const [farm, setSelectFarm] = useState<FarmSelectType["value"]>(farmOptions[0]?.id ?? -1);

    // Fetch the first farm content and store
    const token = permanence.token.useToken();
    const setFarmStore = useFarmStore((s) => s.setFarm);
    const setLocalFarmStore = permanence.farm.setFarmStore;
    useEffect(() => {
        fetchFarm(farm);
    }, []);

    // Update the selected farm store
    const changeFarmStore: (newId: number) => void = (newId) => {
        setSelectFarm(newId);
        fetchFarm(newId);
    };

    // Mode visibility
    const [farmModes, setFarmModes] = useState<ModeSelectType["value"][]>(["none"]);

    // Loading
    const [fetchFarmLoading, setFetchFarmLoading] = useState(false);
    const fetchFarm: (id: number) => Promise<void> = async (id: number) => {
        try {
            setFetchFarmLoading(true);
            const resp = await req.get<{ data: FarmStoreState }>(`/user/get-farm?farmId=${id}`, {
                Authorization: `Bearer ${token}`,
            });

            if (resp.data.type === "rice") {
                setInfo("phenology");
            } else {
                setInfo("yield");
            }
            setFarmStore(resp.data);
            // Store to local storage
            setLocalFarmStore(resp.data);
            // Set visible farm modes
            const hasCrop = resp.data.crops.length > 0;
            const hasLocation = resp.data.locations.length > 0;
            const visibles: ModeSelectType["value"][] = [];
            if (hasCrop) visibles.push("crop");
            if (hasLocation) visibles.push("monitor");
            // NOTE: always push a monitor to visibles
            // visibles.push("monitor");
            // Push none to visibles when no content
            if (!visibles.length) visibles.push("none");

            setFarmModes(visibles);
            setMode(visibles[0]);
        } finally {
            setFetchFarmLoading(false);
        }
    };

    // THe mode & info select states
    const [mode, setMode] = useState<ModeSelectType["value"]>("none");
    const [info, setInfo] = useState<InfoSelectType["value"]>("yield");

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
        const values = crops.map((c) => Number(c.info[info as keyof Crop["info"]]));
        let max = 0;
        let min = 0;
        if (shouldManualSet.includes(info)) {
            if (info === "phenology") {
                max = 9;
                min = 1;
            } else {
                max = 3;
                min = 1;
            }
        } else {
            max = Math.max(...values);
            min = Math.min(...values);
        }

        // Update slider range and values
        setSlider({
            value: {
                left: (max - min) * 0.2 + min,
                right: (max - min) * 0.8 + min,
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
                    <MapContainer
                        center={farmCenter}
                        zoom={farmZoom}
                        crops={farmCrops}
                        farmLocations={farmLocations}
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
        </Flex>
    );
}
