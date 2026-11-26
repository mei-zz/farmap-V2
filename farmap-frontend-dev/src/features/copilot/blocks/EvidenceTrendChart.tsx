import { useEffect, useMemo, useRef } from "react";
import { init, use } from "echarts/core";
import { BarChart, LineChart } from "echarts/charts";
import { AriaComponent, GridComponent, TooltipComponent } from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
import type { AgentUiBlock } from "../uiBlocks";

use([BarChart, LineChart, AriaComponent, GridComponent, TooltipComponent, CanvasRenderer]);

export function EvidenceTrendChart({ block }: { block: AgentUiBlock }) {
  const host = useRef<HTMLDivElement | null>(null);
  const series = useMemo(() => block.data?.series ?? [], [block.data?.series]);
  const labels = useMemo(() => [...new Set(series.flatMap((line) => line.points.map((point) => point.label)))], [series]);
  const comparison = block.componentType === "ParcelCompareChart";
  useEffect(() => {
    if (!host.current || !series.length) return;
    const target = host.current;
    const chart = init(target);
    chart.setOption({
      aria: { enabled: true },
      grid: { top: 20, right: 22, bottom: 16, left: 40, containLabel: true },
      tooltip: { trigger: "axis", renderMode: "richText" },
      xAxis: { type: "category", data: labels, axisLabel: { hideOverlap: true } },
      yAxis: series.map((line, index) => ({
        type: "value", show: index === 0, name: index === 0 ? line.label : undefined,
      })),
      series: series.map((line, index) => ({
        type: comparison ? "bar" : "line",
        name: line.label,
        yAxisIndex: index,
        data: labels.map((label) => line.points.find((point) => point.label === label)?.value ?? null),
      })),
      color: ["#206647", "#a26828", "#3b7589", "#914756"],
    });
    const resize = new ResizeObserver(() => chart.resize());
    resize.observe(target);
    return () => { resize.disconnect(); chart.dispose(); };
  }, [comparison, labels, series]);

  if (!series.length) return <p>暂无可绘制的核验数据。</p>;
  return <>
    <div className="farmap-evidence-chart" aria-label={block.title} ref={host} />
    <details className="farmap-block__sources"><summary>查看图表数据</summary>
      {series.map((line) => <dl className="farmap-block__rows" key={line.label}>
        {line.points.map((point) => <div key={point.label}><dt>{line.label} · {point.label}</dt><dd>{point.value}</dd></div>)}
      </dl>)}
    </details>
  </>;
}
