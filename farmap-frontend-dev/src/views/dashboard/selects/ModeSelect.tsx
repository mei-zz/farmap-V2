import { Flex, Select } from "antd";
import { useEffect, useState } from "react";

type ModeType = "crop" | "monitor" | "none";
export type ModeSelectType = {
  value: ModeType;
  label: string;
};

interface Props {
  value: ModeType;
  modes: ModeType[];
  disabled: boolean;
  onChange: (newMode: ModeType) => void;
}
export default function ModeSelect(props: Props) {
  const options: ModeSelectType[] = [
    { value: "none", label: "没有数据" },
    { value: "crop", label: "作物详情" },
    // { value: "farm", label: "园区总览" },
    { value: "monitor", label: "农场总览" },
  ];

  // Set visible modes after fecthing data
  const [visibleOptions, setVisibleOptions] = useState<ModeSelectType[]>(options);
  useEffect(() => {
    setVisibleOptions(() => options.filter((op) => props.modes.includes(op.value)));
  }, [props.modes]);

  return (
    <Flex gap="0.5rem" align="center" style={{ flexGrow: 1 }}>
      <span> | 地图模式</span>
      <Select
        disabled={props.disabled}
        style={{ flexGrow: 1 }}
        defaultValue={props.value}
        value={props.value}
        options={visibleOptions}
        onChange={(mode) => {
          props.onChange(mode);
        }}
      />
    </Flex>
  );
}
