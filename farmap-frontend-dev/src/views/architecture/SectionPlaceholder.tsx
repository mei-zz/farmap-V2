import { Card, Flex, Typography } from "antd";
import { useNavigate } from "react-router";
import { ArrowRightOutlined, CompassOutlined } from "@ant-design/icons";

const { Paragraph, Text, Title } = Typography;

type SectionPlaceholderProps = {
  title: string;
  description: string;
  nextPath?: string;
  nextLabel?: string;
};

export default function SectionPlaceholder({
  title,
  description,
  nextPath,
  nextLabel = "打开相关页面",
}: SectionPlaceholderProps) {
  const navigate = useNavigate();

  return (
    <Card className="farmap-placeholder" bordered={false}>
      <Flex vertical gap={12}>
        <CompassOutlined className="farmap-placeholder__icon" />
        <Title level={2} style={{ margin: 0 }}>
          {title}
        </Title>
        <Paragraph type="secondary" style={{ maxWidth: 680, marginBottom: 0 }}>
          {description}
        </Paragraph>
        <Text type="secondary">第一阶段已完成产品入口与上下文骨架，业务能力会沿用现有接口逐步接入。</Text>
        {nextPath && (
          <a
            href={nextPath}
            onClick={(event) => {
              event.preventDefault();
              navigate(nextPath);
            }}
          >
            {nextLabel} <ArrowRightOutlined />
          </a>
        )}
      </Flex>
    </Card>
  );
}
