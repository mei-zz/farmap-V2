import { useEffect, useState } from "react";
import { Card, Flex } from "antd";
import { BarsOutlined, FundViewOutlined, ThunderboltOutlined } from "@ant-design/icons";

import VideoPlayer from "./VideoPlayer";
import MonitorListView from "./list/MonitorListView";

import { req } from "@/utils/reqeust";
import { permanence } from "@/utils/permanence";
import PredictPic from "./PredictPic";

interface AccessTokenResponse {
  data: {
    accessToken: string;
    expiresAt: number;
  };
}

export default function index() {
  // Get the user's access token
  const localAccessToken = permanence.accessToken.useAccessToken();
  const [accessToken, setAccessToken] = useState<string>("");
  useEffect(() => {
    if (localAccessToken && Date.now() < localAccessToken.expiresAt) {
      setAccessToken(localAccessToken.accessToken);
      return;
    }

    // Request when local access token expired
    req.get<AccessTokenResponse>("/monitor/get-accesstoken").then((resp) => {
      setAccessToken(resp.data.accessToken);
      permanence.accessToken.setAccessToken({
        accessToken: resp.data.accessToken,
        expiresAt: resp.data.expiresAt,
      });
    });
  }, []);

  // Select video in monitor list
  const [selectedVideo, setSelectedVideo] = useState<string>(""); // this is the request url for video
  const getSelectVideoUrl = (url: string): void => {
    setSelectedVideo(url);
  };

  return (
    <Flex gap="0.5rem" style={{ height: "100%" }} className="resp-monitor__container">
      <Flex gap="0.5rem" vertical style={{ flexGrow: 1 }}>
        <Card
          title={
            <>
              <BarsOutlined />
              &nbsp;&nbsp;监控列表
            </>
          }
          style={{ flex: "0 0 300px" }}
          styles={{ body: { height: "calc(300px - 100px)" } }}>
          <MonitorListView accessToken={accessToken} onSelect={getSelectVideoUrl} />
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
          <VideoPlayer videoUrl={selectedVideo} />
        </Card>
      </Flex>
      <Card
        title={
          <>
            <ThunderboltOutlined />
            &nbsp;&nbsp;在线推理
          </>
        }
        style={{ flex: "1 0 40%" }}
        styles={{ body: { height: "calc(100% - 60px)" } }}>
        <PredictPic />
      </Card>
    </Flex>
  );
}
