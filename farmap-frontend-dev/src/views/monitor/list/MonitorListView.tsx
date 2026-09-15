import { cameraSourceLabel, type FarmCamera } from "@/features/farm/cameras";
import { getMonitorPreview } from "../monitorService";
import { useMemo, useState } from "react";
import { Flex, Typography } from "antd";

interface Props {
  accessToken: string;
  cameras: FarmCamera[];
  onSelect: (monitor: FarmCamera, url: string) => void;
  onLocate?: (monitor: FarmCamera) => void;
  onError?: (message: string) => void;
}
export default function MonitorListView(props: Props) {
  const [selected, setSelected] = useState<string>("");
  const selectedMonitor = useMemo(
    () => props.cameras.find((monitor) => monitor.cameraId === selected),
    [props.cameras, selected],
  );

  const handleSelect = async (monitor: FarmCamera): Promise<void> => {
    setSelected(monitor.cameraId);
    props.onError?.("");

    if (!props.accessToken) {
      props.onSelect(monitor, "");
      props.onError?.("摄像头服务令牌尚未就绪，请稍后重试。");
      return;
    }

    try {
      const preview = await getMonitorPreview(props.accessToken, monitor.serial);
      props.onSelect(monitor, preview.previewUrl);
    } catch (error) {
      props.onSelect(monitor, "");
      setSelected("");
      props.onError?.(error instanceof Error ? error.message : "该摄像头暂时无法获取画面。");
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
          {selectedMonitor?.name ?? "暂未选择"}
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
        {!props.accessToken && (
          <Typography.Text type="secondary" style={{ display: "block", padding: "0.75rem 0.5rem" }}>
            正在连接摄像头服务，设备清单已加载。
          </Typography.Text>
        )}
        <ul style={{ padding: 0, margin: 0, listStyle: "none" }}>
          {props.cameras.map((li, idx) => (
            <li
              style={{
                color: selected === li.cameraId ? "#1677ff" : "#59636e",
                cursor: "pointer",
                padding: "0.35rem 0.25rem",
                borderBottom: "1px solid #f0f0f0",
              }}
              key={li.cameraId}
              onClick={() => {
                void handleSelect(li);
              }}>
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 8 }}>
                <span>{idx + 1}. {li.name}</span>
                {props.onLocate && (
                  <button
                    type="button"
                    onClick={(event) => {
                      event.stopPropagation();
                      props.onLocate?.(li);
                    }}
                    style={{ border: "1px solid #d0e7dc", background: "#f4faf7", color: "#16704f", borderRadius: 4, padding: "2px 6px", cursor: "pointer", fontSize: 11 }}
                  >
                    地图定位
                  </button>
                )}
              </div>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {li.serial} · {li.fieldName || "未绑定地块"} · {li.pointId || "未绑定地图点"} · {cameraSourceLabel(li)}
              </Typography.Text>
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
