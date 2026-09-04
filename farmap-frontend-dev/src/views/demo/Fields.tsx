import { Card, Flex, Progress, Tag, Typography } from "antd";

const demoFields = [
  { name: "A-12 地块", crop: "晚熟脐橙", area: "18.6 ha", stage: "果实膨大期", progress: 68 },
  { name: "B-07 地块", crop: "晚熟脐橙", area: "12.4 ha", stage: "转色期", progress: 82 },
  { name: "C-03 地块", crop: "柑橘苗圃", area: "6.8 ha", stage: "营养生长期", progress: 44 },
];

export default function DemoFields() {
  return (
    <Flex vertical gap={16} className="farmap-demo-page">
      <Flex align="center" justify="space-between">
        <Typography.Title level={3} style={{ margin: 0 }}>
          地块管理
        </Typography.Title>
        <Tag color="gold">DEMO MODE · 只读</Tag>
      </Flex>
      <Card title="云阳智慧果园 · 3 个演示地块" bordered={false}>
        <Flex vertical gap={12}>
          {demoFields.map((field) => (
            <Card key={field.name} size="small">
              <Flex justify="space-between" align="center" wrap gap={12}>
                <div>
                  <Typography.Text strong>{field.name}</Typography.Text>
                  <div>
                    <Typography.Text type="secondary">
                      {field.crop} · {field.area} · {field.stage}
                    </Typography.Text>
                  </div>
                </div>
                <Progress type="circle" percent={field.progress} size={52} />
              </Flex>
            </Card>
          ))}
        </Flex>
      </Card>
    </Flex>
  );
}
