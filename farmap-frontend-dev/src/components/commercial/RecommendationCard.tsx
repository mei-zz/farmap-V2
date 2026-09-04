import { ArrowRightOutlined, ExperimentOutlined, ThunderboltOutlined } from "@ant-design/icons";
import { Card, Flex, Tag, Typography } from "antd";

const icons = {
  high: <ThunderboltOutlined />,
  medium: <ExperimentOutlined />,
};

export default function RecommendationCard({ title, priority, tone, detail }: {
  title: string;
  priority: string;
  tone: "high" | "medium";
  detail: string;
}) {
  return (
    <Card className={`fm-recommendation fm-recommendation--${tone}`} bordered={false}>
      <Flex align="flex-start" gap={12}>
        <div className="fm-recommendation__icon">{icons[tone]}</div>
        <div className="fm-recommendation__body">
          <Flex justify="space-between" align="center" gap={8}>
            <Typography.Text strong>{title}</Typography.Text>
            <Tag>{priority}</Tag>
          </Flex>
          <Typography.Text type="secondary">{detail}</Typography.Text>
          <ArrowRightOutlined className="fm-recommendation__arrow" />
        </div>
      </Flex>
    </Card>
  );
}
