import { useEffect, useState } from "react";

import GuidanceBox from "./GuidanceBox";
import type { Guidance } from "./GuidanceBox";

import { Flex, Card, List, Tag, Typography } from "antd";
import { useFarmStore } from "@/store/farm";
import { req, Request } from "@/utils/reqeust";
import {
  BgColorsOutlined,
  BugOutlined,
  BulbOutlined,
  HourglassOutlined,
  ShopOutlined,
} from "@ant-design/icons";
import { permanence } from "@/utils/permanence";
import { useNavigate } from "react-router";

type GuidanceSet = {
  body: Guidance[];
  fertile: Guidance[];
  park: Guidance[];
  pest: Guidance[];
};

type GuidanceResult = {
  code: number;
  msg: null;
  data: GuidanceSet;
};
type AgentTask = { taskId: string; title: string; fieldId?: string; diagnosisId?: string; agentRunId?: string; status: string; priority?: string; source?: string; createdAt?: string; evidenceIds?: string[] | string };
export default function Operations() {
  const navigate = useNavigate();
  // Request token
  const token = permanence.token.useToken();

  // Use local farm store if it exists
  const localFarmStore = permanence.farm.useFarmStore();
  const farmId = localFarmStore ? localFarmStore.id : useFarmStore((s) => s.id);
  const farmType = localFarmStore ? localFarmStore.type : useFarmStore((s) => s.type);
  const month = new Date().getMonth();

  const [guideList, setGuideList] = useState<GuidanceSet>({
    body: [],
    fertile: [],
    park: [],
    pest: [],
  });
  const [agentTasks, setAgentTasks] = useState<AgentTask[]>([]);
  const getErrorTips = (msg: string): GuidanceSet => ({
    body: [{ id: 0, text: msg, isFormula: false }],
    fertile: [{ id: 0, text: msg, isFormula: false }],
    park: [{ id: 0, text: msg, isFormula: false }],
    pest: [{ id: 0, text: msg, isFormula: false }],
  });

  useEffect(() => {
    req
      .get<GuidanceResult>(`/guidance/get?farmId=${farmId}&farmType=${farmType}&month=${month}`, {
        Authorization: `Bearer ${token}`,
      })
      .then((res) => {
        if (!res.data) {
          setGuideList(getErrorTips("请求参数出错，请联系管理员"));
          return;
        }

        setGuideList(res.data);
      })
      .catch((e) => {
        const msg = Request.getErrorMsg(e);
        setGuideList(getErrorTips(" / " + msg));
      });
  }, []);
  useEffect(() => {
    req.get<{ code: number; data?: { items?: AgentTask[] } }>("/api/operations/tasks", { Authorization: `Bearer ${token}` })
      .then((res) => setAgentTasks(res.data?.items || []))
      .catch(() => setAgentTasks([]));
  }, [token]);
  return (
    <Flex gap="0.5rem" className="resp-operations__content">
      <div
        style={{
          height: "100%",
          display: "grid",
          gap: "0.5rem",
        }}
        className="resp-operations__content-guidance">
        <GuidanceBox list={guideList.body} type="树体管理" iconClass={HourglassOutlined} />
        <GuidanceBox list={guideList.fertile} type="水肥管理" iconClass={BgColorsOutlined} />
        <GuidanceBox list={guideList.pest} type="病虫管理" iconClass={BugOutlined} />
        <GuidanceBox list={guideList.park} type="清园操作" iconClass={ShopOutlined} />
      </div>
      <Card title="Agent 任务" style={{ flex: "1 0 420px" }} bordered={false}>
        {agentTasks.length === 0 ? <Typography.Text type="secondary">审批后的 Agent 农事任务会出现在这里。</Typography.Text> : <List dataSource={agentTasks} renderItem={(task) => <List.Item actions={task.agentRunId ? [<a key="run" onClick={() => navigate(`/analysis/${task.agentRunId}`)}>查看 Agent 运行</a>] : []}><List.Item.Meta title={task.title} description={`${task.fieldId || "当前地块"} · ${task.createdAt || "刚刚"} · ${task.source || "operations"} · evidence ${Array.isArray(task.evidenceIds) ? task.evidenceIds.length : 0}`} /><Flex gap={6}><Tag color={task.priority === "HIGH" ? "red" : "blue"}>{task.priority || "NORMAL"}</Tag><Tag>{task.status}</Tag></Flex></List.Item>} />}
      </Card>
      <Card
        title={
          <>
            <BulbOutlined />
            &nbsp;&nbsp;问专家
          </>
        }
        style={{ flex: "1 0 400px" }}
        styles={{ body: { padding: 0, height: "calc(100% - 60px)" } }}
        className="resp-operations__content-professor">
        <iframe
          src="https://chat.archivemodel.cn"
          style={{
            border: "none",
            width: "100%",
            height: "100%",
            borderRadius: "0 0 8px 8px",
          }}
          onLoad={() => {
            // const iframe = e.currentTarget;
            // try {
            //   if (!iframe.contentDocument || iframe.contentDocument.body.innerHTML === "") {
            //     setHasExpert(false);
            //   } else {
            //     setHasExpert(true);
            //   }
            // } catch {
            //   setHasExpert(false);
            // }
          }}></iframe>
      </Card>
    </Flex>
  );
}
