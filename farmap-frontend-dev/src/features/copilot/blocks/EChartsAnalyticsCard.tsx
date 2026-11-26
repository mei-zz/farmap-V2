import { useEffect, useMemo, useRef, useState } from "react";
import { Maximize2 } from "lucide-react";
import { Modal } from "antd";
import { init, use, type ECharts } from "echarts/core";
import { BarChart, LineChart } from "echarts/charts";
import { AriaComponent, GridComponent, LegendComponent, MarkLineComponent, TooltipComponent } from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
import type { EChartsOption } from "echarts";
import type { AgentUiBlock, ChartSeries, ChartThreshold } from "../uiBlocks";

use([BarChart, LineChart, AriaComponent, GridComponent, LegendComponent, MarkLineComponent, TooltipComponent, CanvasRenderer]);

const palette = ["#176b4d", "#b0792b", "#397a97", "#8b4c60"];

function seriesName(series: ChartSeries) {
  return series.unit ? `${series.label} (${series.unit})` : series.label;
}

function AnalyticsCanvas({ series, thresholds, title, height }: { series: ChartSeries[]; thresholds: ChartThreshold[]; title: string; height: string }) {
  const host = useRef<HTMLDivElement | null>(null);
  const labels = useMemo(() => [...new Set(series.flatMap((line) => line.points.map((point) => point.label)))], [series]);
  const hasRight = series.some((line) => line.axis === "right");

  useEffect(() => {
    const target = host.current;
    if (!target || !series.length) return;
    let chart: ECharts | undefined;
    try {
      chart = init(target, undefined, { renderer: "canvas" });
      const names = series.map(seriesName);
      const option: EChartsOption = {
        animationDuration: 320,
        aria: { enabled: true, decal: { show: false } },
        color: series.map((line, index) => line.color ?? palette[index % palette.length]),
        grid: { top: 42, right: hasRight ? 54 : 22, bottom: 34, left: 52, containLabel: true },
        legend: { top: 2, left: 8, right: 8, type: "scroll", textStyle: { color: "#425648", fontSize: 11 } },
        tooltip: { trigger: "axis", renderMode: "richText", axisPointer: { type: "cross" } },
        xAxis: {
          type: "category",
          name: series[0]?.axisLabel,
          data: labels,
          axisLabel: { color: "#68766c", hideOverlap: true },
          axisLine: { lineStyle: { color: "#dce6de" } },
        },
        yAxis: hasRight ? [
          { type: "value", name: series.find((line) => line.axis !== "right")?.unit, axisLabel: { color: "#68766c" }, splitLine: { lineStyle: { color: "#edf1ed" } } },
          { type: "value", name: series.find((line) => line.axis === "right")?.unit, axisLabel: { color: "#68766c" }, splitLine: { show: false } },
        ] : [
          { type: "value", name: series[0]?.unit, axisLabel: { color: "#68766c" }, splitLine: { lineStyle: { color: "#edf1ed" } } },
        ],
        series: series.map((line, index) => {
          const axis = line.axis === "right" ? 1 : 0;
          const markLines: Array<{ name: string; yAxis: number }> = [];
          if (line.threshold !== undefined) markLines.push({ name: `${line.label} ${line.threshold}${line.unit ?? ""}`, yAxis: line.threshold });
          const firstOnAxis = series.findIndex((candidate) => (candidate.axis === "right" ? 1 : 0) === axis) === index;
          if (firstOnAxis) {
            for (const threshold of thresholds) {
              if (threshold.seriesLabel && threshold.seriesLabel !== line.label) continue;
              if (!threshold.seriesLabel && (threshold.axis === "right" ? 1 : 0) !== axis) continue;
              markLines.push({ name: `${threshold.label} ${threshold.value}${threshold.unit ?? ""}`, yAxis: threshold.value });
            }
          }
          const color = line.color ?? palette[index % palette.length];
          return {
            type: line.type ?? "line",
            name: names[index],
            yAxisIndex: axis,
            stack: line.stack,
            smooth: (line.type ?? "line") === "line",
            showSymbol: line.points.length <= 18,
            symbolSize: 6,
            barMaxWidth: 34,
            itemStyle: { color },
            lineStyle: { width: 2.3 },
            emphasis: { focus: "series" },
            data: labels.map((label) => line.points.find((point) => point.label === label)?.value ?? null),
            ...(markLines.length ? {
              markLine: {
                symbol: "none",
                silent: true,
                lineStyle: { color: "#b07b2b", type: [6, 4], width: 1.3 },
                label: { color: "#76511c", fontSize: 10, formatter: "{b}" },
                data: markLines,
              },
            } : {}),
          };
        }),
      };
      chart.setOption(option);
    } catch {
      if (chart) chart.dispose();
      return;
    }
    const resize = new ResizeObserver(() => chart?.resize());
    resize.observe(target);
    return () => { resize.disconnect(); chart?.dispose(); };
  }, [hasRight, labels, series, thresholds]);

  return <div className="farmap-analytics__chart" ref={host} style={{ height }} role="img" aria-label={title} />;
}

export function EChartsAnalyticsCard({ block }: { block: AgentUiBlock }) {
  const series = block.data?.series ?? [];
  const thresholds = block.data?.thresholds ?? [];
  const [fullscreen, setFullscreen] = useState(false);
  if (!series.length) return <p>暂无可绘制的核验数据。</p>;

  return <section className="farmap-analytics" aria-label={block.title}>
    <div className="farmap-analytics__toolbar">
      <span>共 {series.length} 组数据 · {Math.max(...series.map((line) => line.points.length))} 个时点</span>
      <button type="button" onClick={() => setFullscreen(true)}><Maximize2 size={14} aria-hidden="true" />全屏查看</button>
    </div>
    <AnalyticsCanvas series={series} thresholds={thresholds} title={block.title} height="16rem" />
    <details className="farmap-block__sources">
      <summary>查看图表数据</summary>
      <div className="farmap-analytics__data">
        {series.map((line) => <div key={line.label}>
          <strong>{seriesName(line)}</strong>
          <dl className="farmap-block__rows">
            {line.points.map((point) => <div key={point.label}><dt>{point.label}</dt><dd>{point.value}{line.unit ?? ""}</dd></div>)}
          </dl>
        </div>)}
      </div>
    </details>
    <Modal open={fullscreen} title={block.title} onCancel={() => setFullscreen(false)} footer={null}
      width="min(96vw, 1180px)" className="farmap-analytics-modal" centered>
      <AnalyticsCanvas series={series} thresholds={thresholds} title={block.title} height="min(66vh, 620px)" />
    </Modal>
  </section>;
}
