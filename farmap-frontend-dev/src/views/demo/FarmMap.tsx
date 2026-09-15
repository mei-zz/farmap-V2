import { Card, Flex, Tag, Typography } from "antd";
import MapContainer from "@/views/dashboard/Map";
import { demoFarm, demoFarmLocations, demoMapCrops } from "@/demo";

const { Text } = Typography;

export default function DemoFarmMap() {
  return (
    <Flex vertical gap={12} className="farmap-demo-page">
      <Flex align="center" justify="space-between" wrap gap={8}>
        <div>
          <Typography.Title level={3} style={{ margin: 0 }}>
            数字农场
          </Typography.Title>
          <Text type="secondary">{demoFarm.name} · A-12 地块演示数据</Text>
        </div>
        <Tag color="gold">DEMO MODE · 只读</Tag>
      </Flex>
      <Card title="GIS 地图" bordered={false}>
        <MapContainer
          center={demoFarm.coordinates}
          zoom={13}
          crops={demoMapCrops}
          farmLocations={demoFarmLocations}
          infoKey="growth"
          modeKey="crop"
          slider={{
            value: { left: 0, right: 3 },
            scale: { min: 0, max: 3 },
            decimal: false,
          }}
          readOnly
        />
      </Card>
    </Flex>
  );
}
