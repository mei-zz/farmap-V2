import { useFarmStore } from "@/store/farm";
import { permanence } from "@/utils/permanence";
import { req, Request } from "@/utils/reqeust";
import * as echarts from "echarts";

import { useEffect, useRef, useState } from "react";

type AccTemp = {
  id: number;
  date: string;
  accTemp: number;
};
type AccTempResult = {
  code: number;
  msg: null;
  data: {
    last: AccTemp[];
    thisYear: AccTemp[];
  };
};
function TemperatureChart() {
  // inits
  const token = permanence.token.useToken();
  // Use local farm store if it exists
  const localFarmStore = permanence.farm.useFarmStore();
  const farmType = localFarmStore ? localFarmStore.type : useFarmStore((s) => s.type);

  // for painting
  const containerRef = useRef<HTMLDivElement | null>(null);
  const [lastAccTemp, setLastAccTemp] = useState<AccTemp[]>([]);
  const [thisAccTemp, setThisAccTemp] = useState<AccTemp[]>([]);

  // show up while failed to fetch data
  const [tip, setTip] = useState<string>("");

  useEffect(() => {
    req
      .get<AccTempResult>(`/weather/accumulated-temperature?farmType=${farmType}`, {
        Authorization: `Bearer ${token}`,
      })
      .then((data) => {
        setLastAccTemp(data.data.last);
        setThisAccTemp(data.data.thisYear);

        setTip("");
      })
      .catch((e) => {
        const msg = Request.getErrorMsg(e);

        setTip(msg);
      });

    if (window.innerWidth >= 1000) {
      // console.log(123);
    }
  }, []);

  useEffect(() => {
    if (!containerRef.current) return;
    const chart = echarts.init(containerRef.current);
    chart.setOption({
      title: {
        text: "两年积温对照曲线图",
      },
      legend: {
        bottom: 0,
        data: ["去年曲线", "今年曲线"],
      },
      tooltip: {
        trigger: "axis",
      },
      grid: {
        top: 20,
        bottom: 20,
        left: 20,
        right: 20,
        containLabel: true,
      },
      xAxis: {
        data: lastAccTemp.map((t) => t.date),
      },
      yAxis: {
        type: "value",
        min: "dataMin",
      },
      series: [
        {
          name: "去年曲线",
          type: "line",
          data: lastAccTemp.map((t) => t.accTemp),
          areaStyle: {
            color: "rgba(0, 123, 255, 0.2)",
          },
        },
        {
          name: "今年曲线",
          type: "line",
          data: thisAccTemp.map((t) => t.accTemp),
          areaStyle: {
            color: "rgba(0, 128, 0, 0.2)",
          },
        },
      ],
    });
    return () => {
      chart.dispose();
    };
  }, [lastAccTemp, thisAccTemp]);
  // TODO: set auto height
  return (
    <>
      {tip.length !== 0 ? (
        <div>{tip}</div>
      ) : (
        <div ref={containerRef} style={{ height: "100%" }}></div>
      )}
    </>
  );
}

export default TemperatureChart;
