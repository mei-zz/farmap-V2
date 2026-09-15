import { Card, Flex, Tag, Typography } from "antd";

const demoDevices = [
  { name: "A-12 东侧摄像头", id: "CAM-A12-01", location: "A-12 地块东侧", status: "在线" },
  { name: "果园气象站", id: "WS-YANG-01", location: "园区北侧", status: "在线" },
  { name: "B-07 环境传感器", id: "SEN-B07-02", location: "B-07 地块", status: "待同步" },
];

export default function DemoDevices() {
  return (
    <Flex vertical gap={16} className="farmap-demo-page">
      <Flex align="center" justify="space-between">
        <div>
          <Typography.Title level={3} style={{ margin: 0 }}>
            设备与监控
          </Typography.Title>
          <Typography.Text type="secondary">设备控制在演示模式下不可用</Typography.Text>
        </div>
        <Tag color="gold">DEMO MODE · 只读</Tag>
      </Flex>
      <Flex gap={12} wrap>
        {demoDevices.map((device) => (
          <Card key={device.id} title={device.name} style={{ flex: "1 1 260px" }}>
            <Flex vertical gap={6}>
              <Typography.Text type="secondary">设备 ID：{device.id}</Typography.Text>
              <Typography.Text>{device.location}</Typography.Text>
              <Tag color={device.status === "在线" ? "green" : "orange"}>
                {device.status}
              </Tag>
            </Flex>
          </Card>
        ))}
      </Flex>
    </Flex>
  );
}
