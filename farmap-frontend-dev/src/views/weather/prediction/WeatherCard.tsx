import React from "react";
import { Droplets, CloudRain, Wind } from "lucide-react";
import type { Prediction } from "@/types/weather";
import { WeatherIcon } from "./WeatherIcon";

interface WeatherCardProps {
  data: Prediction;
}

const formatDate = (dateString: string) => {
  const date = new Date(dateString);
  const today = new Date();

  // Format: 12-07 (Week)
  const dateStr = new Intl.DateTimeFormat("zh-CN", {
    month: "numeric",
    day: "numeric",
  }).format(date);

  const weekStr = new Intl.DateTimeFormat("zh-CN", {
    weekday: "short",
  }).format(date);

  if (date.toDateString() === today.toDateString()) {
    return `今日 · ${weekStr}`;
  }
  return `${dateStr} · ${weekStr}`;
};

export const WeatherCard: React.FC<WeatherCardProps> = ({ data }) => {
  return (
    <div className="bg-white rounded-xl p-4 border border-slate-100 shadow-sm hover:shadow-md transition-all duration-300 flex flex-col justify-between h-full min-w-0">
      {/* Top: Date & Condition Text */}
      <div className="flex justify-between items-start mb-3">
        <div>
          <h3 className="text-sm font-semibold text-slate-700">
            {formatDate(data.fxDate)}
          </h3>
          <p className="text-xs text-slate-400 mt-0.5">
            {data.textDay} / {data.textNight}
          </p>
        </div>
        <WeatherIcon code={data.iconDay} className="w-8 h-8" />
      </div>

      {/* Middle: Temperatures */}
      <div className="flex items-baseline gap-2 mb-4">
        <span className="text-3xl font-bold text-slate-800 tracking-tight">
          {data.tempMax}°
        </span>
        <span className="text-lg text-slate-400 font-medium">
          / {data.tempMin}°
        </span>
      </div>

      {/* Bottom: Compact Stats */}
      <div className="grid grid-cols-3 gap-1 pt-3 border-t border-slate-50">
        {/* Wind */}
        <div className="flex flex-col items-center justify-center p-1">
          <Wind className="w-3.5 h-3.5 text-indigo-400 mb-1" />
          <span className="text-[10px] text-slate-400 scale-90">风力</span>
          <span className="text-xs font-medium text-slate-600">
            {data.windScaleDay}级
          </span>
        </div>

        {/* Rain */}
        <div className="flex flex-col items-center justify-center p-1 border-l border-slate-50">
          <CloudRain className="w-3.5 h-3.5 text-blue-400 mb-1" />
          <span className="text-[10px] text-slate-400 scale-90">降水</span>
          <span className="text-xs font-medium text-slate-600">
            {data.precip}mm
          </span>
        </div>

        {/* Humidity */}
        <div className="flex flex-col items-center justify-center p-1 border-l border-slate-50">
          <Droplets className="w-3.5 h-3.5 text-cyan-400 mb-1" />
          <span className="text-[10px] text-slate-400 scale-90">湿度</span>
          <span className="text-xs font-medium text-slate-600">
            {data.humidity}%
          </span>
        </div>
      </div>
    </div>
  );
};
