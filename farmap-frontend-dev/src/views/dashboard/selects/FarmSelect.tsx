import type { FarmPreviewType } from "@/store/user";
import { Flex, Select } from "antd";
import { useMemo } from "react";

export type FarmSelectType = {
  value: number;
  label: string;
};

interface Props {
  value: number;
  options: FarmPreviewType[];
  disabled: boolean;
  onChange: (newId: number) => void;
}
export default function FarmSelect(props: Props) {
  const farmOptions = useMemo(
    () => props.options.map((op) => ({ value: op.id, label: op.name })),
    [props.options],
  );
  return (
    <Flex gap="0.5rem" align="center" style={{ flexGrow: 1 }}>
      <span>当前农场</span>
      <Select
        disabled={props.disabled}
        style={{ flexGrow: 1 }}
        defaultValue={props.value}
        options={farmOptions}
        onChange={(id: number) => {
          props.onChange(id);
        }}
      />
    </Flex>
  );
}
