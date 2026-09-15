import { useEffect, useState } from "react";
import type { Prediction } from "@/types/weather";
import { WeatherCard } from "./WeatherCard";
import { AlertCircle, Loader2 } from "lucide-react";
import { permanence } from "@/utils/permanence";
import { useFarmStore } from "@/store/farm";
import { useAIContextStore } from "@/store/aiContext";
import { loadCurrentWeather, type CurrentWeatherContext } from "@/features/agricultural/context";

function iconForWeather(code?: number, condition?: string): string {
  if (code === 0 || condition === "晴") return "100";
  if ((code !== undefined && (code >= 51 || code === 45 || code === 48)) || condition?.includes("雨")) return "305";
  return "101";
}

function toPrediction(day: NonNullable<CurrentWeatherContext["forecast"]>[number]): Prediction {
  const condition = day.condition ?? "—";
  return {
    fxDate: day.date,
    tempMax: day.temperatureMax ?? "—",
    tempMin: day.temperatureMin ?? "—",
    iconDay: iconForWeather(day.weatherCode, condition),
    iconNight: iconForWeather(day.weatherCode, condition),
    textDay: condition,
    textNight: condition,
    windDirDay: "—",
    windDirNight: "—",
    windScaleDay: "—",
    windScaleNight: "—",
    humidity: "—",
    precip: day.precipitationMm === undefined ? "—" : day.precipitationMm.toFixed(1),
    precipProbability: day.precipitationProbability,
    vis: "—",
    pressure: "—",
  };
}

export default function WeatherPrediction() {
  const localFarmStore = permanence.farm.useFarmStore();
  const storeFarm = useFarmStore();
  const currentFarmId = useAIContextStore((state) => state.context.currentFarm.id);
  const farmId = currentFarmId > 0 ? currentFarmId : localFarmStore?.id ?? storeFarm.id;
  const farmAddr = localFarmStore?.address || storeFarm.address || "当前农场";
  const token = permanence.token.useToken();

  const [predictions, setPredictions] = useState<Prediction[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [isError, setIsError] = useState<boolean>(false);
  const [weatherSource, setWeatherSource] = useState("Open-Meteo");

  useEffect(() => {
    if (farmId <= 0) {
      setIsError(true);
      setLoading(false);
      return;
    }

    setLoading(true);
    setIsError(false);
    loadCurrentWeather(farmId, token)
      .then((weather) => {
        if (weather.status !== "READY" || !weather.forecast?.length) throw new Error("No forecast data");
        setPredictions(weather.forecast.map(toPrediction));
        setWeatherSource(weather.provider === "OPEN_METEO" ? "Open-Meteo" : weather.provider ?? "实时天气服务");
        setIsError(false);
      })
      .catch(() => {
        setIsError(true);
        setPredictions([]);
      })
      .finally(() => {
        setLoading(false);
      });
  }, [farmId, token]);

  return (
    <div className="w-full">
      <h2 className="mb-2">
        当前地区：
        <span className="px-2 rounded-xl bg-gray-100 border border-gray-200 cursor-pointer">
          {farmAddr}
        </span>
      </h2>
      {loading ? (
        <div className="w-full h-40 flex items-center justify-center bg-white rounded-xl border border-slate-100">
          <Loader2 className="w-6 h-6 text-blue-500 animate-spin" />
        </div>
      ) : isError ? (
        <div className="w-full h-40 flex flex-col items-center justify-center bg-red-50 rounded-xl border border-red-100">
          <AlertCircle className="w-6 h-6 text-red-400 mb-2" />
          <span className="text-xs text-red-500">无法加载气象数据</span>
        </div>
      ) : (
        <div className="grid grid-cols-3 gap-3 w-full resp-weather__prediction">
          {predictions.map((p) => (
            <WeatherCard key={p.fxDate} data={p} />
          ))}
        </div>
      )}
      {!loading && !isError && predictions.length > 0 && <p className="mt-3 text-[10px] text-slate-400">数据来源：{weatherSource === "Open-Meteo" ? <a href="https://open-meteo.com/en/license" target="_blank" rel="noreferrer" className="underline">Open-Meteo</a> : weatherSource} · 降雨量与概率为天气模型预测，请结合现场观测。</p>}
    </div>
  );
}
