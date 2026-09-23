import { useEffect, useState } from "react";
import { useNavigate } from "react-router";
import { Alert, Card, Flex, Tag } from "antd";
import { BarsOutlined, FundViewOutlined, RobotOutlined } from "@ant-design/icons";

import VideoPlayer from "./VideoPlayer";
import MonitorListView from "./list/MonitorListView";

import { permanence } from "@/utils/permanence";
import PredictPic from "./PredictPic";
import { useAIContextStore } from "@/store/aiContext";
import { getMonitorAccessToken } from "./monitorService";
import { loadFarmCameras, selectedCameraContext, cameraBindingLabel, cameraSourceLabel, type FarmCamera } from "@/features/farm/cameras";

export default function Monitor() {
  const navigate = useNavigate();
  const setAIContext = useAIContextStore((state) => state.setContext);
  const currentFarm = useAIContextStore((state) => state.context.currentFarm);
  const authToken = permanence.token.useToken();
  const [cameras, setCameras] = useState<FarmCamera[]>([]);
  const [cameraLoading, setCameraLoading] = useState(true);
  useEffect(() => { setAIContext({ currentPage: "/farm/devices" }); }, [setAIContext]);
  useEffect(() => {
    let active = true;
    if (currentFarm.id <= 0) { setCameras([]); setCameraLoading(false); return () => { active = false; }; }
    setCameraLoading(true);
    loadFarmCameras(currentFarm.id, authToken)
      .then((items) => { if (active) setCameras(items); })
      .catch(() => { if (active) setCameras([]); })
      .finally(() => { if (active) setCameraLoading(false); });
    return () => { active = false; };
  }, [authToken, currentFarm.id]);
  // Get the user's access token
  const localAccessToken = permanence.accessToken.useAccessToken();
  const [accessToken, setAccessToken] = useState<string>("");
  const [tokenLoading, setTokenLoading] = useState(true);
  const [tokenError, setTokenError] = useState("");
  useEffect(() => {
    let active = true;
    setTokenLoading(true);
    setTokenError("");
    getMonitorAccessToken()
      .then((token) => {
        if (!active) return;
        setAccessToken(token.accessToken);
      })
      .catch((error: unknown) => {
        if (!active) return;
        setAccessToken("");
        setTokenError(error instanceof Error ? error.message : "摄像头服务连接失败");
      })
      .finally(() => {
        if (active) setTokenLoading(false);
      });
    return () => { active = false; };
  }, [localAccessToken?.accessToken, localAccessToken?.expiresAt]);

  // Select video in monitor list
  const [selectedVideo, setSelectedVideo] = useState<string>(""); // this is the request url for video
  const [selectedDevice, setSelectedDevice] = useState<FarmCamera>();
  const [previewError, setPreviewError] = useState("");
  const getSelectVideoUrl = (monitor: FarmCamera, url: string): void => {
    setSelectedVideo(url);
    setSelectedDevice(monitor);
    setAIContext({ selectedDevice: selectedCameraContext(monitor, Boolean(url)), currentLocation: { label: cameraBindingLabel(monitor), longitude: monitor.longitude, latitude: monitor.latitude } });
  };
  const locateOnMap = (monitor: FarmCamera): void => {
    navigate(`/farm/map?mode=monitor&cameraId=${encodeURIComponent(monitor.cameraId)}`);
  };

  return (
    <Flex gap="0.5rem" style={{ height: "100%" }} className="resp-monitor__container">
      <Flex gap="0.5rem" vertical style={{ flexGrow: 1 }}>
        <Card
          title={
            <Flex align="center" justify="space-between">
              <span>
              <BarsOutlined />
              &nbsp;&nbsp;监控列表
              </span>
              <Tag color={tokenLoading || cameraLoading ? "processing" : accessToken ? "green" : "red"}>
                {tokenLoading || cameraLoading ? "连接中" : accessToken ? `已连接 · ${cameras.length} 路` : "未连接"}
              </Tag>
            </Flex>
          }
          style={{ flex: "0 0 300px" }}
          styles={{ body: { height: "calc(300px - 100px)" } }}>
          <MonitorListView
            accessToken={accessToken}
            cameras={cameras}
            onSelect={getSelectVideoUrl}
            onLocate={locateOnMap}
            onError={setPreviewError}
          />
        </Card>
        <Card
          title={
            <>
              <FundViewOutlined />
              &nbsp;&nbsp;视频预览
            </>
          }
          style={{ flex: "1 0 400px" }}
          styles={{ body: { height: "calc(100% - 60px)" } }}>
          {(tokenError || previewError) && (
            <Alert
              type="warning"
              showIcon
              closable
              message={tokenError || previewError}
              onClose={() => {
                setTokenError("");
                setPreviewError("");
              }}
              style={{ marginBottom: 8 }}
            />
          )}
          {selectedDevice && (
            <div style={{ color: "#667085", fontSize: 12, marginBottom: 8 }}>
              当前设备：{selectedDevice.name}（{selectedDevice.serial}） · {cameraBindingLabel(selectedDevice)} · {cameraSourceLabel(selectedDevice)}
            </div>
          )}
          {!cameraLoading && cameras.length === 0 && (
            <Alert type="info" showIcon message="当前农场没有已绑定摄像头" description="请切换到拥有真实摄像头的农场，或由管理员完成摄像头归属绑定。" style={{ marginBottom: 8 }} />
          )}
          <VideoPlayer videoUrl={selectedVideo} />
        </Card>
      </Flex>
      <Card
        title={
          <>
            <RobotOutlined />
            &nbsp;&nbsp;Agent 农事分析
          </>
        }
        style={{ flex: "1 0 40%" }}
        styles={{ body: { height: "calc(100% - 60px)" } }}>
        <PredictPic />
      </Card>
    </Flex>
  );
}
