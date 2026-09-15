import { Flex } from "antd";
import SeasonWheel from "./SeasenWheel";
import { useFarmStore } from "@/store/farm";
import { useEffect, useState } from "react";
import { req } from "@/utils/reqeust";
import { permanence } from "@/utils/permanence";

interface WeatherIntro {
    id: number;
    text: string;
}
type WeatherIntroResult = {
    code: number;
    msg: null;
    data: {
        intro: WeatherIntro[][];
    };
};

export default function PhenoIntro() {
    // Request token
    const token = permanence.token.useToken();

    // Season wheel event
    const [selectedMonth, setSelectedMonth] = useState<number>(new Date().getMonth());
    const onMonthChange = (month: number): void => {
        setSelectedMonth(month);
    };

    // Introductions
    // Use local farm store if it exists
    const localFarmStore = permanence.farm.useFarmStore();
    const farmId = localFarmStore ? localFarmStore.id : useFarmStore((s) => s.id);

    // Store introductions
    const [introList, setIntroList] = useState<WeatherIntro[]>([]);
    useEffect(() => {
        req.get<WeatherIntroResult>(`/weather/intro?farmId=${farmId}`, {
            Authorization: `Bearer ${token}`,
        })
            .then((res) => {
                // This is for reshaping the strange response structure
                const list: WeatherIntro[] = [];
                res.data.intro.forEach((i) => {
                    list.push(...i);
                });

                setIntroList(list);
            })
            .catch(() => {});
    }, []);
    const intro = <div>{introList[selectedMonth]?.text}</div>;

    return (
        <Flex justify="center" style={{ height: "100%" }} className="resp-weather__charts-pheno">
            <SeasonWheel size={210} onMonthChange={onMonthChange} />
            <div className="resp-weather__charts-pheno-text">{intro}</div>
        </Flex>
    );
}
