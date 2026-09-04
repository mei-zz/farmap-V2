import { Button, Card, Flex, Space, Tag, Typography } from "antd";
import {
  BookOutlined,
  ExperimentOutlined,
  EnvironmentOutlined,
  RobotOutlined,
  TeamOutlined,
} from "@ant-design/icons";
import { useNavigate } from "react-router";
import { isDemoModeEnabled } from "@/utils/demoSession";

const { Paragraph, Text, Title } = Typography;

const capabilities = [
  {
    title: "多模态感知",
    description: "融合 GIS、农业图像、气象、物候与监控数据。",
    icon: EnvironmentOutlined,
  },
  {
    title: "AI 农情诊断",
    description: "从图像证据出发，生成可解释的农情判断。",
    icon: ExperimentOutlined,
  },
  {
    title: "农业智能 Agent",
    description: "连接检索、推理与任务执行，辅助日常决策。",
    icon: RobotOutlined,
  },
  {
    title: "专家反馈闭环",
    description: "沉淀专家复核结果，持续丰富农业知识与案例。",
    icon: TeamOutlined,
  },
];

export default function Landing() {
  const navigate = useNavigate();

  return (
    <main className="farmap-landing">
      <header className="farmap-landing__header">
        <div className="farmap-landing__brand">
          <div className="farmap-brand__mark">F</div>
          <strong>FarMap</strong>
        </div>
        <Button type="text" onClick={() => navigate("/login")}>
          登录控制台
        </Button>
      </header>

      <section className="farmap-landing__hero">
        <div className="farmap-landing__hero-copy">
          <Tag color="green">Agriculture × GIS × AI</Tag>
          <Title>FarMap</Title>
          <Title level={2}>多模态农业智能决策平台</Title>
          <Text className="farmap-landing__english">
            Multimodal Agricultural Intelligence Platform
          </Text>
          <Paragraph>
            融合 GIS、农业图像、气象、物候、监控数据与领域知识，基于 Multimodal RAG 与 AI
            Agent，实现从农情感知、智能诊断到农业决策与任务执行的完整闭环。
          </Paragraph>
          <Space wrap>
            {isDemoModeEnabled && (
              <Button type="primary" size="large" icon={<RobotOutlined />} onClick={() => navigate("/demo")}>
                进入演示
              </Button>
            )}
            <Button size="large" icon={<BookOutlined />} onClick={() => navigate("/login")}>
              登录控制台
            </Button>
          </Space>
          {!isDemoModeEnabled && <Text type="secondary">演示入口当前未开启，请使用正式账号登录。</Text>}
        </div>

        <div className="farmap-landing__flow" aria-label="FarMap 决策闭环">
          <div className="farmap-landing__flow-label">从感知到行动</div>
          <div className="farmap-landing__flow-line">
            <span>感知</span>
            <i>→</i>
            <span>检索</span>
            <i>→</i>
            <span>推理</span>
            <i>→</i>
            <span>决策</span>
            <i>→</i>
            <span>执行</span>
          </div>
          <div className="farmap-landing__flow-foot">专家反馈 · 可追溯 · 可持续学习</div>
        </div>
      </section>

      <section className="farmap-landing__capabilities">
        <div>
          <Text type="secondary">CORE CAPABILITIES</Text>
          <Title level={3}>围绕农业任务组织智能能力</Title>
        </div>
        <Flex gap={12} wrap>
          {capabilities.map((capability) => {
            const Icon = capability.icon;
            return (
              <Card key={capability.title} className="farmap-landing__capability" bordered={false}>
                <Icon className="farmap-landing__capability-icon" />
                <Title level={4}>{capability.title}</Title>
                <Paragraph type="secondary">{capability.description}</Paragraph>
              </Card>
            );
          })}
        </Flex>
      </section>
    </main>
  );
}
