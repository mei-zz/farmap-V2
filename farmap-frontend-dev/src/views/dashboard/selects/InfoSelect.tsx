import { useFarmStore } from "@/store/farm";
import { Flex, Select } from "antd";

type InfoType =
    | "yield"
    | "size"
    | "rate"
    | "phenology"
    | "growth"
    | "impact"
    | "disease"
    | "potassium"
    | "phosphorus"
    | "nitrogen";
export type InfoSelectType = {
    value: InfoType;
    label: string;
};

interface Props {
    value: InfoType;
    disabled: boolean;
    onChange: (newInfo: InfoType) => void;
}
export default function InfoSelect(props: Props) {
    const allOptions: InfoSelectType[] = [
        {
            value: "yield",
            label: "作物产量",
        },
        {
            value: "rate",
            label: "病虫害率",
        },
        {
            value: "size",
            label: "冠层大小",
        },
        {
            value: "phenology",
            label: "物候期",
        },
        {
            value: "growth",
            label: "生长周期",
        },
        {
            value: "impact",
            label: "病虫影响",
        },
        {
            value: "potassium",
            label: "氮",
        },
        {
            value: "phosphorus",
            label: "磷",
        },
        {
            value: "nitrogen",
            label: "钾",
        },
    ];

    const crop = useFarmStore((s) => s.crops)[0];
    if (!crop) return null;

    const growthProps = Object.entries(crop.info)
        .filter(([, v]) => v !== null)
        .map(([k]) => k);

    const options = allOptions.filter((o) => growthProps.includes(o.value));

    return (
        <Flex gap="0.5rem" align="center" style={{ flexGrow: 1 }}>
            <span> | 展示信息</span>
            <Select
                style={{ flexGrow: 1 }}
                value={props.value}
                disabled={props.disabled}
                options={options}
                onChange={(info) => {
                    props.onChange(info);
                }}
            />
        </Flex>
    );
}
