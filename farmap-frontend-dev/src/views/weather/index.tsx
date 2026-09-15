import WeatherPrediction from "./prediction/WeatherPredcition";
import TemperatureChart from "./TemperatureChart";

import { Card, Flex } from "antd";
import { CloseCircleOutlined, LineChartOutlined, SunOutlined } from "@ant-design/icons";
import PhenoIntro from "./pheno/PhenoIntro";

export default function Weather() {
  return (
    <Flex vertical gap="0.5rem" style={{ height: "100%" }}>
      <Card
        title={
          <>
            <SunOutlined />
            &nbsp;&nbsp;天气预报
          </>
        }
        style={{ flex: "1 0 30%" }}>
        <WeatherPrediction />
      </Card>
      <Flex gap="0.5rem" style={{ flexGrow: 1 }} className="resp-weather__charts">
        <Card
          title={
            <>
              <CloseCircleOutlined />
              &nbsp;&nbsp;物候期详情
            </>
          }
          style={{ flexGrow: 1 }}
          styles={{ body: { height: "calc(100% - 60px)" } }}>
          <PhenoIntro />
        </Card>
        <Card
          title={
            <>
              <LineChartOutlined />
              &nbsp;&nbsp;积温曲线
            </>
          }
          style={{ flexGrow: 1 }}
          styles={{ body: { height: "calc(100% - 60px)" } }}
          className="resp-weather__charts-temp">
          <TemperatureChart />
        </Card>
      </Flex>
    </Flex>
  );
}
