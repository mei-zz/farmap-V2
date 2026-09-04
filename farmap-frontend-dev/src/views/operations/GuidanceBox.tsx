import type { AntdIconProps } from "@ant-design/icons/lib/components/AntdIcon";
import { Card, Divider } from "antd";

export interface Guidance {
  id: number;
  text: string;
  isFormula: boolean;
}
interface Props {
  list: Guidance[];
  type: string;
  iconClass: React.FC<AntdIconProps>;
}
export default function GuidanceBox(props: Props) {
  return (
    <Card
      title={
        <>
          <props.iconClass />
          &nbsp;&nbsp;{props.type}
        </>
      }
      style={{ flexGrow: 1, height: "100%" }}
      styles={{
        body: {
          height: "calc(100% - 60px)",
          paddingTop: 0,
        },
      }}
      classNames={{ body: "resp-operations__guidance-text" }}>
      <div>
        {props.list
          .filter((l) => !l.isFormula)
          .map((l) => (
            <div key={l.id}>
              <span>
                <span style={{ color: "#dd0000" }}>*&nbsp;</span>操作建议：
              </span>
              {l.text}
            </div>
          ))}
        {props.list.filter((l) => l.isFormula).length !== 0 && <Divider style={{ margin: 8 }} />}
        {props.list
          .filter((l) => l.isFormula)
          .map((l) => (
            <div key={l.id}>
              <span style={{ color: "#dd0000" }}>*&nbsp;</span>配方推荐：
              {l.text}
            </div>
          ))}
      </div>
    </Card>
  );
}
