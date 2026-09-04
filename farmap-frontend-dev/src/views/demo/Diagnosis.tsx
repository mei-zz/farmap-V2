import { Button, Card, Flex, Space, Tag, Typography } from "antd";
import { RobotOutlined } from "@ant-design/icons";
import { useCopilotStore } from "@/store/copilot";

const findings = [
  ["长势判断", "整体中等，东侧冠层长势偏弱"],
  ["病虫害风险", "疑似轻度炭疽风险，建议 3 日内复查"],
  ["营养状态", "氮素正常，钾素需要持续关注"],
  ["图像证据", "已关联 3 张演示图片"],
];

export default function DemoDiagnosis() {
  const openCopilot = useCopilotStore((state) => state.open);

  return (
    <Flex vertical gap={16} className="farmap-demo-page">
      <Flex align="center" justify="space-between" wrap gap={8}>
        <div>
          <Typography.Title level={3} style={{ margin: 0 }}>
            AI 农情诊断
          </Typography.Title>
          <Typography.Text type="secondary">A-12 地块 · 多模态诊断演示结果</Typography.Text>
        </div>
        <Space>
          <Tag color="gold">DEMO MODE · 只读</Tag>
          <Button type="primary" ghost icon={<RobotOutlined />} onClick={openCopilot}>
            询问 Copilot
          </Button>
        </Space>
      </Flex>
      <Card title="诊断摘要" bordered={false}>
        <Flex vertical gap={14}>
          <Flex align="center" gap={8}>
            <Tag color="orange">中风险</Tag>
            <Typography.Text strong>建议优先关注东侧冠层与钾素供应</Typography.Text>
          </Flex>
          {findings.map(([label, value]) => (
            <Flex key={label} justify="space-between" gap={16} wrap>
              <Typography.Text type="secondary">{label}</Typography.Text>
              <Typography.Text>{value}</Typography.Text>
            </Flex>
          ))}
        </Flex>
      </Card>
      <Card title="证据链预览">
        <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
          演示数据展示“图像证据 → 农业知识 → 推理结论 → 农事建议”的产品链路。真实图片上传和模型调用仍只对正式登录用户开放。
        </Typography.Paragraph>
      </Card>
    </Flex>
  );
}
