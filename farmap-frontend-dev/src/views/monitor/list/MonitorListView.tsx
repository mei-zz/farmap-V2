import { req } from "@/utils/reqeust";
import { monitorList } from "./monitorList";
import { useMemo, useState } from "react";
import { Flex } from "antd";

interface VideoRequestResponse {
  data: {
    data: { previewUrl: string; playbackUrl: string };
  };
}

interface Props {
  accessToken: string;
  onSelect: (url: string) => void;
}
export default function MonitorListView(props: Props) {
  // PERF: actually each user has his own monitors
  const [selected, setSelected] = useState<number>(-1);
  const selectedMonitorName = useMemo(() => monitorList[selected]?.name, [selected]);

  const handleSelect = async (id: number, deviceSerial: string): Promise<void> => {
    try {
      const resp = await req.get<VideoRequestResponse>(
        `/monitor/preview?accessToken=${props.accessToken}&deviceSerial=${deviceSerial}`,
      );

      // Set preview url to the iframe
      props.onSelect(resp.data.data.previewUrl);
      setSelected(id);
    } catch {
      props.onSelect("");
      setSelected(-1);
    }
  };

  return (
    <div style={{ height: "100%" }}>
      <Flex gap="0.5rem" style={{ marginBottom: "0.5rem" }}>
        <h4 style={{ padding: 0, margin: 0 }}>当前选中摄像头</h4>
        <span
          style={{
            backgroundColor: "#eee",
            padding: "0 5px",
            borderRadius: 8,
          }}>
          {selectedMonitorName ?? "暂未选择"}
        </span>
      </Flex>
      <section
        style={{
          height: "calc(100% - 40px)",
          overflowY: "scroll",
          border: "1px solid #bbb",
          borderRadius: 4,
          paddingLeft: 10,
        }}>
        <ul style={{ padding: 0, margin: 0, listStyle: "none" }}>
          {monitorList.map((li, idx) => (
            <li
              style={{ color: selected === li.id ? "red" : "gray" }}
              key={li.id}
              onClick={() => {
                handleSelect(li.id, li.serial);
              }}>
              {idx + 1}. {li.name}
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
